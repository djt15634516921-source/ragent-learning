// 可选真实服务验收：仅使用合成资料和独立容器，调用一次文档 Embedding、一次问题 Embedding、一次聊天。
// 会产生提供方费用；先执行 verify-knowledge.mjs，通过后按需手动运行。
// --evidence-check 使用五份 D14 合成资料，5 次文档 Embedding、4 次问题 Embedding、4 次聊天（其中一次 SSE）。
import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {mkdirSync, openSync, closeSync, writeFileSync, readFileSync, readdirSync} from 'node:fs';
import {join, resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';

const root = resolve(import.meta.dirname, '..');
assert.ok(process.env.LLM_API_KEY && process.env.LLM_CHAT_URL && process.env.LLM_MODEL, '请提供现有模型环境变量');
const id = randomUUID().slice(0, 8), password = randomUUID();
const pg = 'ragent-real-pg-' + id, redis = 'ragent-real-redis-' + id;
const work = join(root, 'target', 'rag-provider-' + id);
mkdirSync(work, {recursive: true});
const docker = (...args) => execFileSync('docker', args, {encoding: 'utf8', windowsHide: true}).trim();
let application, pgCreated = false, redisCreated = false;
async function until(check) {
  for (let i = 0; i < 120; i++) { if (await check()) return; await delay(500); }
  throw new Error('服务启动超时，查看 ' + work);
}
try {
  docker('run', '-d', '--rm', '--name', pg, '-e', 'POSTGRES_PASSWORD=' + password,
    '-e', 'POSTGRES_DB=rag_provider', '-p', '127.0.0.1::5432', 'pgvector/pgvector:pg16'); pgCreated = true;
  docker('run', '-d', '--rm', '--name', redis, '-p', '127.0.0.1::6379', 'redis:latest',
    'redis-server', '--requirepass', password); redisCreated = true;
  await until(() => { try { return docker('exec', pg, 'pg_isready', '-U', 'postgres').includes('accepting'); } catch { return false; } });
  const server = createServer(); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port; await new Promise(resolve => server.close(resolve));
  const origin = 'http://127.0.0.1:' + port;
  const log = openSync(join(work, 'application.log'), 'w');
  application = spawn('java', ['-jar', join(root, 'bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar')], {
    cwd: root, windowsHide: true, stdio: ['ignore', log, log], env: {...process.env,
      SERVER_PORT: String(port), SPRING_DATASOURCE_URL: 'jdbc:postgresql://127.0.0.1:' + docker('port', pg, '5432/tcp').split(':').at(-1) + '/rag_provider',
      SPRING_DATASOURCE_USERNAME: 'postgres', SPRING_DATASOURCE_PASSWORD: password,
      REDIS_HOST: '127.0.0.1', REDIS_PORT: docker('port', redis, '6379/tcp').split(':').at(-1), REDIS_PASSWORD: password,
      REDIS_USERNAME: '', REDIS_DATABASE: '0', AUTH_REDIS_PREFIX: 'ragent-learning:provider:' + id + ':',
      KNOWLEDGE_STORAGE_ROOT: join(work, 'files'), CHAT_STREAM_TIMEOUT_MS: '90000'}});
  closeSync(log);
  await until(async () => {
    if (application.exitCode !== null) throw new Error('应用启动失败，查看 ' + work);
    try { return (await fetch(origin + '/login-test.html')).ok; } catch { return false; }
  });
  let token;
  const request = (path, options = {}) => fetch(origin + path, {...options, signal: AbortSignal.timeout(90000),
    headers: {...(token ? {Authorization: 'Bearer ' + token} : {}), ...options.headers}});
  const json = (path, data) => request(path, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)});
  const ok = async (response, status = 200) => { assert.equal(response.status, status, await response.clone().text()); return response.json(); };
  const credentials = {username: 'provider_fixture', password: 'isolated-fixture-123'};
  await ok(await json('/api/auth/register', credentials), 201);
  token = (await ok(await json('/api/auth/login', credentials))).token;
  const kb = await ok(await json('/api/knowledge-bases', {name: '合成资料连通性验证'}), 201);
  if (process.argv.includes('--evidence-check')) {
    const directory = join(root, 'docs/examples/rag-eval');
    const indexes = [];
    for (const filename of readdirSync(directory).filter(f => f.endsWith('.md')).sort()) {
      const form = new FormData(); form.append('file', new Blob([readFileSync(join(directory, filename))]), filename);
      const doc = await ok(await request('/api/knowledge-bases/' + kb.id + '/documents', {method: 'POST', body: form}), 201);
      const path = '/api/knowledge-bases/' + kb.id + '/documents/' + doc.id;
      await ok(await request(path + '/parse', {method: 'POST'}));
      await ok(await json(path + '/chunks', {}));
      const indexed = await ok(await request(path + '/index', {method: 'POST'}));
      assert.equal(indexed.status, 'SUCCESS'); assert.equal(indexed.count, 1);
      indexes.push({filename, count: indexed.count, model: indexed.model});
    }
    const cases = [];
    const saveEvidence = (status, errorType) => writeFileSync(join(work, 'grounding-result.json'),
      JSON.stringify({passed: status === 'PASSED', status, errorType, dimensions: 1024, indexes,
        contaminatedHistory: '仅独立测试库人工插入旧错误金额', checks: cases.map(c => c.name), cases}, null, 2));
    const capture = (name, question, result) => { cases.push({name, question, ...result}); saveEvidence('RUNNING'); };
    try {
      const question = '月泉系统每提交一张工单要收多少钱？';
      const input = text => ({conversationId: 'provider-evidence', knowledgeBaseId: kb.id, question: text});
      const known = await ok(await json('/api/chat', input('星河学习项目的验收口令是什么？')));
      capture('已知事实仍能回答并引用实际证据', '星河学习项目的验收口令是什么？', known);
      assert.match(known.answer, /海棠7429/);
      const knownCitations = [...known.answer.matchAll(/\[(\d+)\]/g)].map(m => Number(m[1]));
      assert.ok(knownCitations.some(i => known.sources[i - 1]?.content.includes('海棠7429')));
      // 仅污染本脚本独立容器的历史，检查旧错误答案不能替代当前证据。
      docker('exec', pg, 'psql', '-U', 'postgres', '-d', 'rag_provider', '-v', 'ON_ERROR_STOP=1', '-c',
        "INSERT INTO chat_message (conversation_id,role,content) VALUES ('provider-evidence','user','之前问过月泉工单收费情况'),('provider-evidence','assistant','月泉系统每提交一张工单需支付50元[1]。')");
      const checkMissingFee = result => {
        assert.match(result.answer, /未提供|没有提供|未说明|未明确|未提及|无法确定|未涉及|没有.*收费/);
        assert.doesNotMatch(result.answer, /\d+(?:\.\d+)?\s*元|[一二三四五六七八九十百千万]+元/);
        assert.doesNotMatch(result.answer, /无需(?:支付|缴纳)|不收取(?:任何)?费用/);
      };
      const normal = await ok(await json('/api/chat', input(question)));
      capture('有错误金额历史时普通请求仍明确证据不足', question, normal);
      checkMissingFee(normal);
      const response = await json('/api/chat/stream', input(question));
      assert.equal(response.status, 200);
      const frames = (await response.text()).split('\n\n').filter(x => x.includes('data:')).map(frame => ({
        event: frame.match(/event:\s*(\w+)/)[1], data: JSON.parse(frame.match(/data:\s*(.*)/)[1])
      }));
      assert.equal(frames.at(-1).event, 'done', JSON.stringify(frames.at(-1)));
      const streaming = frames.at(-1).data;
      capture('SSE 同样明确证据不足并完成保存', question, streaming); checkMissingFee(streaming);
      const phoneQuestion = '林老师手机号是多少';
      const phone = await ok(await json('/api/chat', input(phoneQuestion)));
      capture('短手机号问题拒答，出现的引用均有本轮依据', phoneQuestion, phone);
      assert.match(phone.answer, /未提供|没有提供|未说明|未明确|未提及|无法确定|未涉及/);
      assert.doesNotMatch(phone.answer, /\b1[3-9]\d{9}\b/);
      for (const match of phone.answer.matchAll(/\[(\d+)\]/g)) {
        assert.ok(phone.sources[Number(match[1]) - 1]?.content.includes('林老师'), '手机号回答中的引用须对应林老师相关证据');
      }
      const history = await ok(await request('/api/conversations/provider-evidence/messages'));
      assert.ok(history.some(m => m.role === 'assistant' && m.content === '月泉系统每提交一张工单需支付50元[1]。'));
      assert.deepEqual(history.at(-1).sources, phone.sources);
      saveEvidence('PASSED');
      console.log('真实服务证据不足与历史干扰检查通过，记录：' + join(work, 'grounding-result.json'));
    } catch (error) { saveEvidence('FAILED', error.name); throw error; }
  } else {
    const text = '星河学习项目的验收口令是海棠7429。负责人是林老师，每周四下午三点验收。以上为测试构造的数据。';
    const form = new FormData(); form.append('file', new Blob([text]), 'provider-fixture.md');
    const doc = await ok(await request('/api/knowledge-bases/' + kb.id + '/documents', {method: 'POST', body: form}), 201);
    const path = '/api/knowledge-bases/' + kb.id + '/documents/' + doc.id;
    await ok(await request(path + '/parse', {method: 'POST'}));
    await ok(await json(path + '/chunks', {}));
    const index = await ok(await request(path + '/index', {method: 'POST'}));
    assert.equal(index.count, 1); assert.equal(index.status, 'SUCCESS');
    const result = await ok(await json('/api/chat', {conversationId: 'provider-grounded', knowledgeBaseId: kb.id,
      question: '根据资料，星河学习项目的验收口令是什么？'}));
    assert.match(result.answer, /海棠7429/); assert.match(result.answer, /\[1\]/);
    assert.equal(result.sources[0].content, text);
    const history = await ok(await request('/api/conversations/provider-grounded/messages'));
    assert.deepEqual(history[1].sources, result.sources);
    writeFileSync(join(work, 'result.json'), JSON.stringify({passed: true, embeddingModel: index.model,
      dimensions: 1024, answer: result.answer, sources: result.sources, checks: ['真实文档向量化', '真实问题向量化和 pgvector 检索',
        '真实模型依据合成事实回答并引用[1]', '完整问答与来源快照落库']}, null, 2));
    console.log('真实服务 RAG 闭环通过，记录：' + join(work, 'result.json'));
  }
} finally {
  if (application && application.exitCode === null && application.signalCode === null) {
    const stopped = new Promise(resolve => application.once('exit', resolve)); application.kill(); await stopped;
  }
  if (pgCreated) docker('rm', '-f', '-v', pg);
  if (redisCreated) docker('rm', '-f', '-v', redis);
}
