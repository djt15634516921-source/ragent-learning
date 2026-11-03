// 独立 PostgreSQL、Redis 容器及应用端口；只调用本机假模型，不连接已有业务数据。
// 根目录先 mvn clean verify dependency:build-classpath "-Dmdep.outputFile=target/parser-classpath.txt"
// 再 node scripts/verify-knowledge.mjs。PDF/DOCX 测试样本由本地 Java 库实际生成。
import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {createServer as createHttpServer} from 'node:http';
import {mkdirSync, openSync, closeSync, readdirSync, readFileSync, writeFileSync} from 'node:fs';
import {resolve, join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {verifyBrowser} from './verify-browser.mjs';
import {verifyChunks} from './verify-chunks.mjs';
import {verifyRag} from './verify-rag.mjs';
import {mockEmbedding} from './mock-embedding.mjs';
import {mockRewrite} from './mock-rewrite.mjs';
import {verifyRewrite} from './verify-rewrite.mjs';

const root = resolve(import.meta.dirname, '..');
const runId = randomUUID().slice(0, 8);
const container = `ragent-knowledge-test-${runId}`;
const redisContainer = `ragent-auth-test-${runId}`;
const redisPrefix = `ragent-learning:test:${runId}:`;
const work = join(root, 'target', `knowledge-test-${runId}`);
const storage = join(work, 'files');
mkdirSync(work, {recursive: true});
const checks = [];
let application;
let containerCreated = false;
let redisCreated = false;
let mockModel;
const modelCalls = [];
const docker = (...args) => execFileSync('docker', args, {encoding: 'utf8', windowsHide: true}).trim();
const sql = query => docker('exec', container, 'psql', '-U', 'postgres', '-d', 'knowledge_test', '-Atc', query);
const files = path => {
  try { return readdirSync(path, {withFileTypes: true}).flatMap(entry => entry.isDirectory()
    ? files(join(path, entry.name)) : [join(path, entry.name)]); }
  catch (error) { if (error.code === 'ENOENT') return []; throw error; }
};
function passed(name) { checks.push(name); console.log(`PASS ${name}`); }
async function freePort() {
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function until(check, description) {
  for (let i = 0; i < 120; i++) {
    if (await check()) return;
    await delay(500);
  }
  throw new Error(`等待超时：${description}；日志目录 ${work}`);
}
async function stopApplication() {
  if (!application || application.exitCode !== null || application.signalCode !== null) return;
  const child = application;
  const stopped = new Promise(resolve => child.once('exit', resolve));
  child.kill();
  await stopped;
}

try {
  const moduleDependencies = {
    framework: [], 'infra-ai': ['framework'], system: ['framework'],
    rag: ['framework', 'infra-ai'], bootstrap: ['framework', 'system', 'rag']
  };
  const artifacts = {};
  assert.match(readFileSync(join(root, 'pom.xml'), 'utf8'), /<packaging>pom<\/packaging>/);
  for (const [module, expectedDependencies] of Object.entries(moduleDependencies)) {
    const modulePom = readFileSync(join(root, module, 'pom.xml'), 'utf8');
    const ownDependencies = [...modulePom.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)]
      .filter(match => /<groupId>com\.dujiteng<\/groupId>/.test(match[1]))
      .map(match => match[1].match(/<artifactId>([^<]+)<\/artifactId>/)[1]);
    assert.deepEqual(ownDependencies.sort(), [...expectedDependencies].sort(), module + ' 模块依赖方向');
    const target = join(root, module, 'target');
    const jars = readdirSync(target).filter(name => name.endsWith('.jar')
      && !name.endsWith('-sources.jar') && !name.endsWith('-javadoc.jar'));
    assert.equal(jars.length, 1, module + ' 应只有一个当前版本主 JAR，请先 clean verify');
    artifacts[module] = join(target, jars[0]);
    const entries = execFileSync('jar', ['tf', artifacts[module]], {encoding: 'utf8', windowsHide: true});
    if (module !== 'bootstrap') assert.doesNotMatch(entries, /^BOOT-INF\//m, module + ' 必须为普通类库 JAR');
    else {
      for (const library of ['framework', 'infra-ai', 'system', 'rag'])
        assert.ok(entries.includes('BOOT-INF/lib/' + library + '-'), '启动包包含 ' + library);
      for (const entry of ['application.yaml', 'db/chat-schema.sql', 'static/login-test.html', 'static/knowledge-test.html',
        'com/dujiteng/ragentlearning/RagentLearningApplication.class',
        'com/dujiteng/ragentlearning/bootstrap/web/ChatExceptionHandler.class'])
        assert.ok(entries.includes('BOOT-INF/classes/' + entry), '启动包包含 ' + entry);
      assert.ok(!entries.includes('BOOT-INF/classes/com/dujiteng/ragentlearning/chat/'), '业务类由 rag 类库提供');
    }
  }
  passed('五模块依赖方向、普通类库 JAR、唯一可运行 JAR 与配置/页面/SQL 资源装配');
  const fixtures = join(work, 'fixtures');
  const parserClasspath = readFileSync(join(root, 'rag/target/parser-classpath.txt'), 'utf8').trim();
  execFileSync('java', ['-cp', parserClasspath, join(root, 'scripts/fixtures/ParserFixtures.java'), fixtures],
    {encoding: 'utf8', windowsHide: true});
  const password = randomUUID();
  docker('run', '--detach', '--rm', '--name', container,
    '-e', `POSTGRES_PASSWORD=${password}`, '-e', 'POSTGRES_DB=knowledge_test',
    '-p', '127.0.0.1::5432', 'pgvector/pgvector:pg16');
  containerCreated = true;
  await until(() => {
    try { return docker('exec', container, 'pg_isready', '-U', 'postgres', '-d', 'knowledge_test').includes('accepting'); }
    catch { return false; }
  }, '临时 PostgreSQL 启动');
  const dbPort = docker('port', container, '5432/tcp').split(':').at(-1);
  docker('run', '--detach', '--rm', '--name', redisContainer,
    '-p', '127.0.0.1::6379', 'redis:latest', 'redis-server', '--requirepass', password);
  redisCreated = true;
  const redisPort = docker('port', redisContainer, '6379/tcp').split(':').at(-1);
  const redis = (...args) => execFileSync('docker', ['exec', '-e', 'REDISCLI_AUTH', redisContainer, 'redis-cli', '--raw', ...args],
    {encoding: 'utf8', windowsHide: true, env: {...process.env, REDISCLI_AUTH: password}}).trim();
  await until(() => { try { return redis('PING') === 'PONG'; } catch { return false; } }, '临时 Redis 启动');
  mockModel = createHttpServer(async (request, response) => {
    try {
      let body = ''; for await (const chunk of request) body += chunk;
      const input = JSON.parse(body);
      if (request.url === '/embeddings') { await mockEmbedding(input, response); return; }
      // 其余接口仍须是 Chat Completions 载荷。
      if (!Array.isArray(input.messages)) { response.writeHead(400).end(); return; }
      if (await mockRewrite(input, response)) return;
      modelCalls.push(input);
      const question = input.messages.at(-1).content;
      const rag = question.startsWith('知识库问答输入') ? JSON.parse(question.slice(question.indexOf('\n') + 1)) : null;
      const answer = rag ? '知识库测试回答：' + rag.sources[0].content.slice(0, 500) + ' [1]' : '测试回答：' + question;
      if (question === 'model-error') { response.writeHead(503).end('mock unavailable'); return; }
      if (!input.stream) {
        if (question === 'slow-first') await delay(250);
        response.writeHead(200, {'Content-Type': 'application/json'});
        response.end(JSON.stringify({choices: [{message: {content: answer}}]})); return;
      }
      response.writeHead(200, {'Content-Type': 'text/event-stream'});
      const chunk = content => response.write('data: ' + JSON.stringify({choices: [{delta: {content}}]}) + '\n\n');
      chunk(rag ? '知识库' : '测试');
      if (question === 'cancel-stream' || question === 'timeout-stream') {
        for (let i=0; i<40 && !response.destroyed; i++) { await delay(50); if (!response.destroyed) chunk('片段'); }
        if (response.destroyed) return;
      }
      chunk(rag ? answer.slice(3) : '回答：' + question);
      if (question !== 'incomplete-stream') {
        response.write('data: ' + JSON.stringify({choices: [{delta: {}, finish_reason: 'stop'}]}) + '\n\n');
        response.write('data: [DONE]\n\n');
      }
      response.end();
    } catch (error) { if (!response.destroyed) response.destroy(error); }
  });
  await new Promise(resolve => mockModel.listen(0, '127.0.0.1', resolve));
  const modelPort = mockModel.address().port;
  // 先装入 D8 结构及一条旧记录，验证 D9 的 ALTER 对已存在表确实生效。
  const d8Schema = readFileSync(join(root, 'bootstrap/src/main/resources/db/chat-schema.sql'), 'utf8').split('-- D9：')[0];
  execFileSync('docker', ['exec', '-i', container, 'psql', '-U', 'postgres', '-d', 'knowledge_test', '-v', 'ON_ERROR_STOP=1'],
    {input: d8Schema, encoding: 'utf8', windowsHide: true});
  sql("INSERT INTO app_user (username, password_hash) VALUES ('legacy_user', 'fixture-only')");
  sql("INSERT INTO knowledge_base (user_id, name) SELECT id, 'D8 fixture' FROM app_user WHERE username = 'legacy_user'");
  sql("INSERT INTO knowledge_document (knowledge_base_id, original_filename, file_type, file_size, storage_key) SELECT id, 'legacy.md', 'md', 1, 'legacy/old.md' FROM knowledge_base WHERE name = 'D8 fixture'");
  const appPort = await freePort();
  const origin = `http://127.0.0.1:${appPort}`;
  const env = {...process.env,
    SERVER_PORT: String(appPort),
    SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${dbPort}/knowledge_test`,
    SPRING_DATASOURCE_USERNAME: 'postgres', SPRING_DATASOURCE_PASSWORD: password,
    KNOWLEDGE_STORAGE_ROOT: storage,
    REDIS_HOST: '127.0.0.1', REDIS_PORT: redisPort, REDIS_PASSWORD: password, REDIS_DATABASE: '0',
    AUTH_REDIS_PREFIX: redisPrefix,
    CHAT_STREAM_TIMEOUT_MS: '1500',
    RAG_REWRITE_ENABLED: 'true', RAG_REWRITE_TIMEOUT_MS: '400',
    EMBEDDING_URL: `http://127.0.0.1:${modelPort}/embeddings`, EMBEDDING_API_KEY: 'local-test',
    LLM_CHAT_URL: `http://127.0.0.1:${modelPort}/chat`, LLM_MODEL: 'local-test', LLM_API_KEY: 'local-test'};
  async function startApplication(logName) {
    const log = openSync(join(work, logName), 'w');
    application = spawn('java', ['-jar', artifacts.bootstrap], {cwd: root, env, stdio: ['ignore', log, log], windowsHide: true});
    closeSync(log);
    await until(async () => {
      if (application.exitCode !== null) throw new Error(`Java 启动失败：${join(work, logName)}`);
      try { return (await fetch(`${origin}/login-test.html`, {signal: AbortSignal.timeout(1000)})).status === 200; }
      catch { return false; }
    }, '独立应用启动');
  }
  await startApplication('first-start.log');
  assert.equal(sql("SELECT count(*) FROM knowledge_document WHERE original_filename = 'legacy.md' AND status = 'PENDING' AND parsed_text IS NULL"), '1');
  passed('D8 已有表增量迁移，旧文档记录保留');
  function client() {
    let token;
    async function request(path, options = {}) {
      const response = await fetch(origin + path, {...options, redirect: 'manual',
        signal: AbortSignal.timeout(20000),
        headers: {...(token ? {Authorization: 'Bearer ' + token} : {}), ...options.headers}});
      return response;
    }
    async function post(path, body, type) {
      return request(path, {method: 'POST', body, headers: {
        ...(type ? {'Content-Type': type} : {})}});
    }
    const json = (path, data) => post(path, JSON.stringify(data), 'application/json');
    async function login(username) {
      const response = await json('/api/auth/login', {username, password: 'local-test-123'});
      assert.equal(response.status, 200, await response.clone().text());
      const data = await response.json(); token = data.token;
      assert.ok(token); assert.equal(data.passwordHash, undefined); return data;
    }
    async function register(username) {
      assert.equal((await json('/api/auth/register', {username, password: 'local-test-123'})).status, 201);
      await login(username);
    }
    function upload(id, filename, data = '用于学习的原文。') {
      const form = new FormData();
      if (filename !== null) form.append('file', new Blob([data]), filename);
      return post(`/api/knowledge-bases/${id}/documents`, form);
    }
    return {request, post, json, login, register, upload, token: () => token};
  }
  const a = client(), b = client();
  assert.equal((await a.request('/api/knowledge-bases')).status, 401);
  const page = await a.request('/knowledge-test.html');
  assert.equal(page.status, 200);
  assert.match(await page.text(), /知识库与文档/);
  for (const path of ['/login-test.html', '/knowledge-test.html', '/stream-test.html', '/auth.js']) {
    assert.equal((await a.request(path)).headers.get('cache-control'), 'no-store', path + ' 防止旧页面沿用旧协议');
  }
  passed('未登录 API 被拦截，测试页可打开');
  await a.register('study_a');
  assert.equal((await a.json('/api/auth/register', {username: 'STUDY_A', password: 'local-test-123'})).status, 409);
  assert.equal((await a.json('/api/auth/register', {username: '1bad', password: 'local-test-123'})).status, 400);
  assert.equal((await a.json('/api/auth/register', {username: 'weak_password', password: 'short'})).status, 400);
  const anonymous = client();
  const badPassword = await anonymous.json('/api/auth/login', {username: 'study_a', password: 'wrong-password'});
  assert.equal(badPassword.status, 401);
  assert.equal((await badPassword.json()).code, 'AUTH_INVALID_CREDENTIALS');
  assert.equal((await anonymous.request('/api/auth/me')).status, 401);
  assert.match(sql("SELECT password_hash FROM app_user WHERE username='study_a'"), /^\$2[aby]\$/);
  assert.ok(redis('--scan', '--pattern', redisPrefix + '*').split('\n').filter(Boolean).length >= 2);
  assert.equal((await anonymous.request('/api/auth/me?Authorization=' + encodeURIComponent(a.token()))).status, 401);
  assert.equal((await anonymous.request('/api/auth/me', {headers: {Cookie: 'Authorization=' + a.token()}})).status, 401);
  assert.equal((await anonymous.request('/api/auth/csrf')).status, 410);
  assert.equal((await anonymous.request('/api/auth/me', {headers: {Authorization: a.token()}})).status, 401);
  passed('BCrypt、错误密码、重复账号、真实 Redis 会话和仅 Bearer 认证，旧 CSRF 明确废止');
  const a2 = client(); await a2.login('study_a');
  assert.ok(a.token() !== a2.token());
  assert.equal((await a.request('/api/auth/me')).status, 200);
  assert.equal((await a2.request('/api/auth/me')).status, 200);
  const expiring = client(); await expiring.login('study_a');
  const tokenKey = redis('--scan', '--pattern', redisPrefix + '*').split('\n').find(k => k.endsWith(':token:' + expiring.token()));
  assert.ok(tokenKey, '登录 Token 必须实际存在于独立 Redis 命名空间');
  assert.ok(Number(redis('TTL', tokenKey)) > 28000);
  redis('EXPIRE', tokenKey, '1'); await delay(1100);
  assert.equal((await expiring.request('/api/auth/me')).status, 401);
  assert.equal((await a.request('/api/auth/me')).status, 200);
  passed('同账号多端独立 Token、固定八小时有效期和 Redis 到期失效');
  assert.equal((await a.request('/api/knowledge-bases', {method: 'POST', body: '{}',
    headers: {'Content-Type': 'application/json', Origin: 'https://other.example'}})).status, 403);
  assert.equal((await a.json('/api/knowledge-bases', {name: ' '})).status, 400);
  assert.equal((await a.json('/api/knowledge-bases', {name: 'a'.repeat(101)})).status, 400);
  passed('注册登录、非同源写请求拒绝和知识库名称校验');
  const created = await a.json('/api/knowledge-bases', {name: 'Java 学习', description: '原文与元数据'});
  assert.equal(created.status, 201);
  const kb = await created.json();
  const second = await a.json('/api/knowledge-bases', {name: '第二个知识库'});
  assert.equal(second.status, 201);
  const secondKb = await second.json();
  assert.equal((await (await a.request('/api/knowledge-bases')).json()).length, 2);
  assert.equal((await (await a.request(`/api/knowledge-bases/${secondKb.id}/documents`)).json()).length, 0);
  passed('创建两个知识库并独立查询文档');
  const content = '学习代号：朱之文01\n中文与单引号 \' 原样保存。';
  const uploaded = await a.upload(kb.id, '../../学习说明.MD', content);
  assert.equal(uploaded.status, 201, await uploaded.clone().text());
  const document = await uploaded.json();
  assert.equal(document.filename, '学习说明.MD');
  assert.equal(document.status, 'PENDING');
  assert.equal(document.size, Buffer.byteLength(content));
  assert.equal(document.storageKey, undefined);
  assert.equal(files(storage).length, 1);
  const filePath = `/api/knowledge-bases/${kb.id}/documents/${document.id}/file`;
  const downloaded = await a.request(filePath);
  assert.equal(downloaded.status, 200);
  assert.match(downloaded.headers.get('content-disposition'), /^attachment;/);
  assert.equal(await downloaded.text(), content);
  assert.equal((await (await a.request(`/api/knowledge-bases/${kb.id}/documents`)).json()).length, 1);
  passed('中文文件上传、路径名清理、PENDING 状态和原文下载');
  await b.register('study_b');
  const chat = (owner, conversationId, question, extra = {}) => owner.json('/api/chat', {conversationId, question, ...extra});
  const stream = (owner, conversationId, question) => owner.json('/api/chat/stream', {conversationId, question});
  const history = async (owner, id) => (await owner.request(`/api/conversations/${id}/messages`)).json();
  const chatId = 'chat-basic-' + runId;
  const firstChat = await chat(a, chatId, '你好', {userId: (await (await b.request('/api/auth/me')).json()).userId});
  assert.equal(firstChat.status, 200, await firstChat.clone().text());
  assert.equal((await firstChat.json()).answer, '测试回答：你好');
  assert.deepEqual((await history(a, chatId)).map(m => m.role), ['user', 'assistant']);
  assert.equal(sql(`SELECT u.username FROM chat_conversation c JOIN app_user u ON u.id=c.user_id WHERE conversation_id='${chatId}'`), 'study_a');
  assert.equal(modelCalls[0].messages[0].content,
    '你是一名Java后端学习助手，请用中文回答。回答第一行固定为【Ragent学习助手】。随后给出简短解释和一个具体例子。');
  const callsBefore = modelCalls.length;
  assert.equal((await chat(b, chatId, '越权问题')).status, 404);
  assert.equal((await stream(b, chatId, '越权问题')).status, 404);
  assert.equal((await b.request(`/api/conversations/${chatId}/messages`)).status, 404);
  assert.equal(modelCalls.length, callsBefore);
  sql("INSERT INTO chat_conversation (conversation_id) VALUES ('legacy-unowned')");
  assert.equal((await chat(a, 'legacy-unowned', '不能认领旧记录')).status, 404);
  assert.equal(sql("SELECT count(*) FROM chat_conversation WHERE conversation_id='legacy-unowned' AND user_id IS NULL"), '1');
  passed('普通聊天、提示词保留、可信 userId、会话归属和无归属旧记录保护');
  const racedId = 'chat-race-' + runId;
  const raced = await Promise.all([chat(a, racedId, 'account-a'), chat(b, racedId, 'account-b')]);
  assert.deepEqual(raced.map(r => r.status).sort(), [200, 404]);
  assert.equal(sql(`SELECT count(*) FROM chat_message WHERE conversation_id='${racedId}'`), '2');
  const windowId = 'chat-window-' + runId;
  for (let i=0; i<8; i++) assert.equal((await chat(a, windowId, 'round-' + i)).status, 200);
  assert.equal(sql(`SELECT count(*) FROM chat_message WHERE conversation_id='${windowId}'`), '16');
  const recent = await history(a, windowId);
  assert.equal(recent.length, 12); assert.equal(recent[0].content, 'round-2');
  assert.equal(modelCalls.at(-1).messages.length, 14);
  assert.equal(modelCalls.at(-1).messages[1].content, 'round-1');
  passed('最近六轮按消息编号排序，模型窗口受限且数据库完整历史保留');
  const concurrentId = 'chat-concurrent-' + runId;
  const slow = chat(a, concurrentId, 'slow-first');
  await until(() => modelCalls.at(-1)?.messages.at(-1)?.content === 'slow-first', '并发测试首个模型请求');
  const fast = chat(a, concurrentId, 'fast-second');
  assert.equal((await slow).status, 200); assert.equal((await fast).status, 200);
  assert.deepEqual((await history(a, concurrentId)).map(m => m.content),
    ['slow-first', '测试回答：slow-first', 'fast-second', '测试回答：fast-second']);
  assert.equal(modelCalls.at(-1).messages[1].content, 'slow-first');
  passed('同一会话并发请求按完整轮次协调，后续模型请求看到已保存历史');
  const streamId = 'chat-stream-' + runId;
  const streamed = await stream(a, streamId, '流式问题');
  assert.equal(streamed.status, 200);
  const eventText = await streamed.text();
  assert.match(eventText, /event:start/); assert.match(eventText, /event:delta/); assert.match(eventText, /event:done/);
  assert.deepEqual((await history(a, streamId)).map(m => m.content), ['流式问题', '测试回答：流式问题']);
  const incompleteId = 'chat-incomplete-' + runId;
  const incomplete = await (await stream(a, incompleteId, 'incomplete-stream')).text();
  assert.match(incomplete, /MODEL_STREAM_INCOMPLETE/); assert.doesNotMatch(incomplete, /event:done/);
  assert.equal((await history(a, incompleteId)).length, 0);
  assert.equal((await chat(a, 'model-error-' + runId, 'model-error')).status, 502);
  const cancelId = 'chat-cancel-' + runId;
  const cancelled = await stream(a, cancelId, 'cancel-stream');
  const reader = cancelled.body.getReader();
  let cancelText = ''; const decoder = new TextDecoder();
  while (!cancelText.includes('event:delta')) { const part = await reader.read(); if(part.done)break; cancelText += decoder.decode(part.value); }
  await reader.cancel(); await delay(500);
  assert.equal((await history(a, cancelId)).length, 0);
  const timeoutId = 'chat-timeout-' + runId;
  const timeoutText = await (await stream(a, timeoutId, 'timeout-stream')).text();
  assert.doesNotMatch(timeoutText, /event:done/);
  assert.equal((await history(a, timeoutId)).length, 0);
  passed('SSE start/delta/done 协议与保存顺序，模型失败、半截流、取消及超时不写成功记录');
  const rollbackId = 'chat-rollback-' + runId;
  sql(`ALTER TABLE chat_message ADD CONSTRAINT test_chat_rollback CHECK (conversation_id <> '${rollbackId}' OR role <> 'assistant')`);
  const rollback = await chat(a, rollbackId, '应回滚用户和助手消息');
  assert.equal(rollback.status, 503); assert.equal((await rollback.json()).code, 'CHAT_HISTORY_SAVE_FAILED');
  assert.equal(sql(`SELECT count(*) FROM chat_message WHERE conversation_id='${rollbackId}'`), '0');
  const streamRollback = await (await stream(a, rollbackId, '流式写入也应回滚')).text();
  assert.match(streamRollback, /CHAT_HISTORY_SAVE_FAILED/); assert.doesNotMatch(streamRollback, /event:done/);
  assert.equal(sql(`SELECT count(*) FROM chat_message WHERE conversation_id='${rollbackId}'`), '0');
  sql('ALTER TABLE chat_message DROP CONSTRAINT test_chat_rollback');
  assert.equal((await chat(a, rollbackId, '解除约束后恢复')).status, 200);
  passed('真实数据库约束触发普通/SSE 整轮事务回滚，失败不发 done，解除故障后恢复');
  assert.equal((await (await b.request('/api/knowledge-bases')).json()).length, 0);
  assert.equal((await b.request(`/api/knowledge-bases/${kb.id}/documents`)).status, 404);
  assert.equal((await b.request(filePath)).status, 404);
  assert.equal((await b.upload(kb.id, 'forbidden.md')).status, 404);
  assert.equal(files(storage).length, 1);
  assert.equal((await a.request(`/api/knowledge-bases/${secondKb.id}/documents/${document.id}/file`)).status, 404);
  passed('跨用户列表、读取、下载、上传和跨知识库编号访问被拒绝');
  const bKb = await (await b.json('/api/knowledge-bases', {name: 'B 的资料'})).json();
  assert.equal((await b.upload(bKb.id, 'own.txt')).status, 201);
  passed('第二个用户可以正常管理自己的资料');
  assert.equal((await a.upload(kb.id, null)).status, 400);
  assert.equal((await a.upload(kb.id, 'empty.txt', '')).status, 400);
  assert.equal((await a.upload(kb.id, 'program.exe')).status, 415);
  const tooLarge = await a.upload(kb.id, 'large.txt', Buffer.alloc(10 * 1024 * 1024 + 1, 65));
  assert.equal(tooLarge.status, 413, await tooLarge.clone().text());
  assert.equal((await tooLarge.json()).code, 'FILE_TOO_LARGE');
  assert.equal((await a.request('/api/knowledge-bases/not-a-number/documents')).status, 400);
  assert.equal(files(storage).length, 2);
  passed('缺失、空文件、类型、超限和错误编号校验，无原文件残留');
  const before = sql('SELECT count(*) FROM knowledge_document');
  sql("ALTER TABLE knowledge_document ADD CONSTRAINT test_reject CHECK (original_filename <> 'rollback.md')");
  const failed = await a.upload(kb.id, 'rollback.md');
  assert.equal(failed.status, 503, await failed.clone().text());
  assert.equal(sql('SELECT count(*) FROM knowledge_document'), before);
  assert.equal(files(storage).length, 2);
  sql('ALTER TABLE knowledge_document DROP CONSTRAINT test_reject');
  passed('真实数据库约束失败：无文档记录，刚保存的文件已清理');

  const pathFor = (id, operation) => `/api/knowledge-bases/${kb.id}/documents/${id}/${operation}`;
  const parse = id => a.post(pathFor(id, 'parse'), '');
  const text = async id => (await a.request(pathFor(id, 'text'))).json();
  assert.equal((await text(document.id)).status, 'PENDING');
  assert.equal((await text(document.id)).text, null);
  assert.equal((await a.request(pathFor(document.id, 'parse'), {method: 'POST', headers: {Origin: 'null'}})).status, 403);
  assert.equal((await b.post(pathFor(document.id, 'parse'), '')).status, 404);
  assert.equal((await b.request(pathFor(document.id, 'text'))).status, 404);
  assert.equal((await a.post(`/api/knowledge-bases/${secondKb.id}/documents/${document.id}/parse`, '')).status, 404);
  passed('待处理结果查询、解析来源校验和跨用户/跨库解析权限');
  assert.equal((await parse(document.id)).status, 200);
  assert.equal((await text(document.id)).text, content);
  assert.equal((await history(a, chatId)).length, 2);
  assert.ok((await text(document.id)).parsedAt);
  assert.equal((await (await a.request(`/api/knowledge-bases/${kb.id}/documents`)).json())[0].status, 'SUCCESS');
  const utf8Document = await (await a.upload(kb.id, 'utf8.txt', '\uFEFF第一行\r\n第二行🙂\r\n')).json();
  assert.equal((await parse(utf8Document.id)).status, 200);
  assert.equal((await text(utf8Document.id)).text, '第一行\n第二行🙂');
  assert.equal((await text(utf8Document.id)).characterCount, 8);
  passed('UTF-8 中文、BOM/换行规范化、Markdown 原文与字符数');
  for (const [filename, expected] of [['text.pdf', 'Ragent PDF parser'], ['text.docx', '朱之文01']]) {
    const row = await (await a.upload(kb.id, filename, readFileSync(join(fixtures, filename)))).json();
    const response = await parse(row.id);
    assert.equal(response.status, 200, await response.clone().text());
    assert.match((await text(row.id)).text, new RegExp(expected));
  }
  passed('真实 PDFBox PDF 与 POI DOCX 提取文本');
  for (const [filename, data, errorCode] of [
    ['broken.pdf', 'this is not a PDF', 'DOCUMENT_PARSE_FAILED'],
    ['broken.docx', 'this is not a DOCX', 'DOCUMENT_PARSE_FAILED'],
    ['encrypted.pdf', readFileSync(join(fixtures, 'encrypted.pdf')), 'DOCUMENT_ENCRYPTED'],
    ['no-text.pdf', readFileSync(join(fixtures, 'no-text.pdf')), 'DOCUMENT_TEXT_EMPTY'],
    ['invalid-encoding.txt', Buffer.from([0xff, 0xfe, 0x61]), 'DOCUMENT_ENCODING_INVALID'],
    ['whitespace.md', '  \r\n\t', 'DOCUMENT_TEXT_EMPTY'],
    ['too-much.txt', 'x'.repeat(1_000_001), 'DOCUMENT_TEXT_TOO_LARGE']
  ]) {
    const row = await (await a.upload(kb.id, filename, data)).json();
    const response = await parse(row.id);
    assert.equal(response.status, 422, `${filename}: ${await response.clone().text()}`);
    assert.equal((await response.json()).code, errorCode, filename);
    const result = await text(row.id);
    assert.equal(result.status, 'FAILED', filename);
    assert.equal(result.text, null, filename);
    assert.ok(result.errorMessage, filename);
    assert.equal((await parse(row.id)).status, 422, '失败任务允许再次尝试');
  }
  passed('损坏/加密/无文字 PDF、损坏 DOCX、非法编码、空白与超限：失败原因持久化，不保存部分文本');
  sql(`UPDATE knowledge_document SET status = 'RUNNING', parse_token = 'occupied', parse_started_at = CURRENT_TIMESTAMP WHERE id = ${document.id}`);
  assert.equal((await parse(document.id)).status, 409);
  assert.equal(sql(`SELECT parse_token FROM knowledge_document WHERE id = ${document.id}`), 'occupied');
  sql(`UPDATE knowledge_document SET parse_started_at = CURRENT_TIMESTAMP - INTERVAL '6 minutes' WHERE id = ${document.id}`);
  assert.equal((await parse(document.id)).status, 200);
  assert.equal((await text(document.id)).status, 'SUCCESS');
  passed('RUNNING 占用拒绝重复解析，中断超时后可重新解析');
  sql(`ALTER TABLE knowledge_document ADD CONSTRAINT test_parse_save CHECK (id <> ${document.id} OR status <> 'SUCCESS') NOT VALID`);
  assert.equal((await parse(document.id)).status, 503);
  assert.equal((await text(document.id)).status, 'RUNNING');
  assert.equal((await text(document.id)).text, null);
  sql('ALTER TABLE knowledge_document DROP CONSTRAINT test_parse_save');
  sql(`UPDATE knowledge_document SET parse_started_at = CURRENT_TIMESTAMP - INTERVAL '6 minutes' WHERE id = ${document.id}`);
  assert.equal((await parse(document.id)).status, 200);
  assert.equal((await text(document.id)).errorMessage, null);
  passed('写入解析结果失败不误报成功，解除故障后重试恢复');
  const chunkResult = await verifyChunks({a, b, anonymous, kb, secondKb, sql, container, storage, until, passed});
  const ragResult = await verifyRag({a, b, anonymous, kb, secondKb, sql, modelCalls, until, passed});
  const rewriteResult = await verifyRewrite({a, b, kb, secondKb, sql, modelCalls, passed});
  await stopApplication();
  await startApplication('restart.log');
  assert.equal((await a.request('/api/auth/me')).status, 200);
  assert.equal((await (await a.request('/api/knowledge-bases')).json()).length, 2);
  assert.equal(await (await a.request(filePath)).text(), content);
  assert.equal((await text(document.id)).text, content);
  passed('Java 重启后 Redis 登录恢复，知识库、解析文本和原文件仍存在');
  const restoredChunks = await (await a.request(`/api/knowledge-bases/${kb.id}/documents/${chunkResult.documentId}/chunks`)).json();
  assert.equal(restoredChunks.chunkStatus, 'SUCCESS');
  assert.equal(restoredChunks.total, chunkResult.expectedTotal);
  passed('Java 重启后片段、来源版本、顺序与统计保留');
  assert.equal((await (await a.request(ragResult.path + '/index')).json()).status, 'SUCCESS');
  assert.deepEqual((await (await a.request('/api/conversations/' + ragResult.conversationId + '/messages')).json())[1].sources, ragResult.sources);
  passed('RAG：重启后向量索引、知识库绑定与历史回答的来源快照保留');
  const resumed = await a.json('/api/chat', {conversationId: rewriteResult.cid, knowledgeBaseId: rewriteResult.knowledgeBaseId,
    question: rewriteResult.original});
  assert.equal(resumed.status, 200);
  assert.equal((await resumed.json()).retrievalQuery.searchQuestion, '星河学习项目每周什么时候验收？');
  passed('改写：Java 重启后从数据库恢复近期用户问题，连续追问仍能补全对象');
  assert.equal((await a.post('/api/auth/logout', '', 'application/x-www-form-urlencoded')).status, 200);
  assert.equal((await a.request('/api/knowledge-bases')).status, 401);
  assert.equal((await a.request(filePath)).status, 401);
  assert.equal((await a.request(pathFor(document.id, 'text'))).status, 401);
  assert.equal((await a2.request('/api/auth/me')).status, 200);
  passed('退出只撤销当前 Token，原文件和解析文本拒绝访问，其他终端继续有效');
  docker('pause', redisContainer);
  try {
    const unavailable = await a2.request('/api/auth/me');
    assert.equal(unavailable.status, 503, await unavailable.clone().text());
    assert.equal((await unavailable.json()).code, 'AUTH_SESSION_UNAVAILABLE');
    const failedLogin = await anonymous.json('/api/auth/login', {username: 'study_a', password: 'local-test-123'});
    assert.equal(failedLogin.status, 503, await failedLogin.clone().text());
    assert.equal((await failedLogin.json()).code, 'AUTH_SESSION_UNAVAILABLE');
  } finally { docker('unpause', redisContainer); }
  assert.equal((await a2.request('/api/auth/me')).status, 200);
  passed('Redis 不可用时读取和登录明确 503，不回落内存，连接恢复后原 Token 有效');
  if (process.env.RUN_BROWSER_CHECK === 'true') await verifyBrowser({root, origin, work, passed});
  const html = readFileSync(join(root, 'bootstrap/src/main/resources/static/knowledge-test.html'), 'utf8');
  const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
  new Function(script);
  passed('新页面 JavaScript 语法检查');
  writeFileSync(join(work, 'result.json'), JSON.stringify({passed: checks.length, checks}, null, 2));
  console.log(`验证完成：${checks.length} 组通过。记录：${join(work, 'result.json')}`);
} finally {
  await stopApplication();
  if (containerCreated) {
    try { docker('rm', '--force', '--volumes', container); } catch { /* --rm 可能已清理 */ }
  }
  if (redisCreated) { try { docker('rm', '--force', '--volumes', redisContainer); } catch {} }
  if (mockModel) { mockModel.closeAllConnections(); await new Promise(resolve => mockModel.close(resolve)); }
}
