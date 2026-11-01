import assert from 'node:assert/strict';
import {embeddingState} from './mock-embedding.mjs';

export async function verifyRag({a, b, anonymous, kb, secondKb, sql, modelCalls, until, passed}) {
  const base = '/api/knowledge-bases/' + secondKb.id;
  const ok = async response => { assert.equal(response.status, 200, await response.clone().text()); return response.json(); };
  const create = async (client, k, filename, text, options = {}) => {
    const upload = await client.upload(k, filename, text); assert.equal(upload.status, 201);
    const doc = await upload.json(), path = '/api/knowledge-bases/' + k + '/documents/' + doc.id;
    await ok(await client.post(path + '/parse', ''));
    await ok(await client.json(path + '/chunks', options));
    return {doc, path, index: () => client.post(path + '/index', '')};
  };
  const before = embeddingState.calls.length;
  assert.equal((await a.json(base + '/search', {question: '星河项目'})).status, 409);
  assert.equal(embeddingState.calls.length, before);
  const source = '星河项目的验收口令是海棠7429，负责人是林老师，每周四下午进行验收。';
  const fixture = await create(a, secondKb.id, 'rag-fact.md', source);
  assert.equal((await anonymous.post(fixture.path + '/index', '')).status, 401);
  assert.equal((await b.post(fixture.path + '/index', '')).status, 404);
  assert.equal((await b.json(base + '/search', {question: '星河项目'})).status, 404);
  assert.equal((await b.json('/api/chat', {conversationId: 'rag-denied', knowledgeBaseId: secondKb.id, question: '星河项目'})).status, 404);
  assert.equal(embeddingState.calls.length, before);
  passed('RAG：未向量化明确拒绝；未登录/跨用户操作不调用 Embedding 或聊天模型');

  const indexed = await ok(await fixture.index());
  assert.equal(indexed.status, 'SUCCESS'); assert.equal(indexed.count, 1);
  assert.equal(sql(`SELECT vector_dims(v.embedding) FROM knowledge_chunk_vector v JOIN knowledge_chunk c ON c.id=v.chunk_id WHERE c.document_id=${fixture.doc.id}`), '1024');
  assert.equal(embeddingState.calls.at(-1).dimensions, 1024);
  const count = embeddingState.calls.length;
  await ok(await fixture.index()); assert.equal(embeddingState.calls.length, count);
  passed('RAG：真实 pgvector 1024 维入库、成功状态、相同版本重复向量化不产生外部调用');

  const otherBase = await (await b.json('/api/knowledge-bases', {name: '其他账号 RAG'})).json();
  const other = await create(b, otherBase.id, 'private.md', '星河项目的口令是其他用户秘密，不允许泄漏。');
  await ok(await other.index());
  const unrelated = await create(a, kb.id, 'other-base.md', '星河项目的口令是另一知识库秘密，不应混入本次答案。');
  await ok(await unrelated.index());
  const results = await ok(await a.json(base + '/search', {question: '星河项目验收口令是什么？'}));
  assert.equal(results.length, 1); assert.equal(results[0].content, source);
  assert.equal(results[0].documentId, fixture.doc.id); assert.ok(results[0].score > 0.99);
  assert.equal(results[0].embedding, undefined);
  passed('RAG：SQL 余弦相似度、来源信息、同用户跨库与跨账号检索隔离');

  const chat = (id, question, extra = {}) => a.json('/api/chat', {conversationId: id, knowledgeBaseId: secondKb.id, question, ...extra});
  const conversationId = 'rag-grounded';
  const answer = await ok(await chat(conversationId, '星河项目验收口令是什么？'));
  assert.match(answer.answer, /海棠7429/); assert.match(answer.answer, /\[1\]/);
  assert.equal(answer.sources[0].content, source);
  const sent = modelCalls.at(-1).messages;
  assert.match(sent[0].content, /未经信任/); assert.ok(!sent[0].content.includes('海棠7429'));
  assert.match(sent.at(-1).content, /海棠7429/);
  assert.equal(sql(`SELECT content FROM chat_message WHERE conversation_id='${conversationId}' AND role='user'`), '星河项目验收口令是什么？');
  const history = await ok(await a.request('/api/conversations/' + conversationId + '/messages'));
  assert.deepEqual(history[1].sources, answer.sources);
  const currentInput = JSON.parse(sent.at(-1).content.split('\n').slice(1).join('\n'));
  assert.deepEqual(currentInput.sources.map(s => s.citationId), [1]);
  assert.equal(currentInput.sources[0].content, source);
  assert.deepEqual(Object.keys(currentInput.sources[0]).sort(), ['citationId', 'content', 'filename']);
  assert.match(sent[0].content, /主题相关.*不代表正文包含所问事实/);
  assert.match(sent[0].content, /当前资料未提供，无法确定/);
  await ok(await chat(conversationId, '请再说明星河项目的口令'));
  const forwardedHistory = modelCalls.at(-1).messages.filter(m => m.role === 'assistant');
  assert.ok(forwardedHistory.length > 0);
  assert.ok(forwardedHistory.every(m => !m.content.includes(answer.answer)));
  assert.ok(modelCalls.at(-1).messages.some(m => m.role === 'user' && m.content === '星河项目验收口令是什么？'));
  assert.equal((await ok(await a.request('/api/conversations/' + conversationId + '/messages')))[1].content, answer.answer);
  passed('RAG：本轮来源显式编号、历史助手正文仅在发送副本中隔离、用户问题与持久化历史保留');
  const conversations = await ok(await a.request('/api/conversations'));
  assert.equal(conversations.find(x => x.conversationId === conversationId).knowledgeBaseId, secondKb.id);
  assert.equal((await chat(conversationId, '切换', {knowledgeBaseId: kb.id})).status, 409);
  assert.equal((await chat(conversationId, '切换', {knowledgeBaseId: null})).status, 409);
  passed('RAG：检索内容实际传给模型，原问题与来源快照保存，会话固定知识库防止历史混用');

  const stream = await a.json('/api/chat/stream', {conversationId: 'rag-stream', knowledgeBaseId: secondKb.id, question: '星河项目验收口令是什么？'});
  assert.equal(stream.status, 200);
  const frames = (await stream.text()).split('\n\n').filter(x => x.includes('data:')).map(frame => ({
    event: frame.match(/event:\s*(\w+)/)[1], data: JSON.parse(frame.match(/data:\s*(.*)/)[1])
  }));
  assert.equal(frames.at(-1).event, 'done'); assert.equal(frames.at(-1).data.sources[0].content, source);
  assert.match(frames.at(-1).data.answer, /海棠7429/);
  const modelBefore = modelCalls.length;
  const refusal = await ok(await chat('rag-no-evidence', '火星现在几度？'));
  assert.match(refusal.answer, /没有检索到/); assert.deepEqual(refusal.sources, []);
  assert.equal(modelCalls.length, modelBefore);
  sql("ALTER TABLE chat_message ADD CONSTRAINT test_rag_save CHECK (conversation_id <> 'rag-rollback' OR role <> 'assistant') NOT VALID");
  try {
    assert.equal((await chat('rag-rollback', '星河项目')).status, 503);
    assert.equal(sql("SELECT count(*) FROM chat_message WHERE conversation_id='rag-rollback'"), '0');
  } finally { sql('ALTER TABLE chat_message DROP CONSTRAINT test_rag_save'); }
  passed('RAG：SSE done 携带来源、无命中明确拒答且不调用聊天模型、问答与来源写库失败整轮回滚');

  embeddingState.hold = true;
  try {
    const interrupted = await a.json('/api/chat/stream', {conversationId: 'rag-retrieval-timeout',
      knowledgeBaseId: secondKb.id, question: 'INDEX_SLOW 星河项目'});
    await until(() => embeddingState.release !== null, 'SSE 等待问题向量化');
    const body = await interrupted.text();
    assert.ok(!body.includes('event:done'));
  } finally { embeddingState.hold = false; embeddingState.release?.(); embeddingState.release = null; }
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.equal(sql("SELECT count(*) FROM chat_message WHERE conversation_id='rag-retrieval-timeout'"), '0');
  passed('RAG：问题向量化等待期间 SSE 超时，不写入问答或发送 done');

  const profile = sql(`SELECT index_profile FROM knowledge_document WHERE id=${fixture.doc.id}`);
  sql(`UPDATE knowledge_document SET index_profile='different-space' WHERE id=${fixture.doc.id}`);
  const callBefore = embeddingState.calls.length;
  assert.equal((await a.json(base + '/search', {question: '星河项目'})).status, 409);
  assert.equal(embeddingState.calls.length, callBefore);
  sql(`UPDATE knowledge_document SET index_profile='${profile}' WHERE id=${fixture.doc.id}`);
  await ok(await a.json(fixture.path + '/chunks', {chunkSize: 600, overlap: 80}));
  assert.equal((await ok(await a.request(fixture.path + '/index'))).status, 'PENDING');
  assert.equal((await a.json(base + '/search', {question: '星河项目'})).status, 409);
  assert.equal(sql(`SELECT count(*) FROM knowledge_chunk_vector v JOIN knowledge_chunk c ON c.id=v.chunk_id WHERE c.document_id=${fixture.doc.id}`), '0');
  await ok(await fixture.index());
  await ok(await a.post(fixture.path + '/parse', ''));
  assert.equal((await a.json(base + '/search', {question: '星河项目'})).status, 409);
  await ok(await a.json(fixture.path + '/chunks', {})); await ok(await fixture.index());
  assert.deepEqual((await ok(await a.request('/api/conversations/' + conversationId + '/messages')))[1].sources, answer.sources);
  passed('RAG：向量空间不匹配拒绝混用，重切分/重解析使旧向量失效，历史来源快照仍可读取');

  const failed = await create(a, secondKb.id, 'index-failure.md', '星河项目的失败重试测试资料。');
  sql(`UPDATE knowledge_document SET chunk_count=501 WHERE id=${failed.doc.id}`);
  const callsBeforeLimit = embeddingState.calls.length;
  assert.equal((await failed.index()).status, 400);
  assert.equal(embeddingState.calls.length, callsBeforeLimit);
  sql(`UPDATE knowledge_document SET chunk_count=1 WHERE id=${failed.doc.id}`);
  embeddingState.badNext = true;
  const bad = await failed.index(); assert.equal(bad.status, 502); assert.equal((await bad.json()).code, 'EMBEDDING_INVALID_RESPONSE');
  let state = await ok(await a.request(failed.path + '/index')); assert.equal(state.status, 'FAILED'); assert.match(state.errorMessage, /1024/);
  embeddingState.failNext = true; assert.equal((await failed.index()).status, 502);
  assert.equal((await ok(await a.request(failed.path + '/index'))).status, 'FAILED');
  assert.equal((await ok(await failed.index())).status, 'SUCCESS');
  passed('RAG：错误维度与上游故障拒绝保存，失败原因持久化，重试可恢复');

  const slow = await create(a, secondKb.id, 'slow.md', 'INDEX_SLOW 星河项目文档的并发与失效验证。');
  embeddingState.hold = true;
  const running = slow.index();
  try {
    await until(() => embeddingState.release !== null, '向量化进入远程等待');
    assert.equal((await slow.index()).status, 409);
    await ok(await a.json(slow.path + '/chunks', {chunkSize: 700, overlap: 100}));
  } finally { embeddingState.hold = false; embeddingState.release?.(); embeddingState.release = null; }
  assert.equal((await running).status, 409);
  assert.equal((await ok(await a.request(slow.path + '/index'))).status, 'PENDING');
  sql(`UPDATE knowledge_document SET index_status='RUNNING', index_started_at=CURRENT_TIMESTAMP-INTERVAL '6 minutes', index_token='expired' WHERE id=${slow.doc.id}`);
  assert.equal((await ok(await slow.index())).status, 'SUCCESS');
  passed('RAG：并发认领拒绝重复调用，切分变更后旧任务不能发布，过期租约可恢复');

  const big = await create(a, secondKb.id, 'batch.md', '星河项目批量向量化事务验证。'.repeat(4300), {chunkSize: 200, overlap: 0});
  const chunkCount = Number(sql(`SELECT chunk_count FROM knowledge_document WHERE id=${big.doc.id}`));
  assert.ok(chunkCount > 200 && chunkCount <= 500);
  const cutoff = sql(`SELECT id FROM knowledge_chunk WHERE document_id=${big.doc.id} AND chunk_index=200`);
  sql(`ALTER TABLE knowledge_chunk_vector ADD CONSTRAINT test_vector_batch CHECK (chunk_id < ${cutoff}) NOT VALID`);
  try {
    assert.equal((await big.index()).status, 503);
    assert.equal(sql(`SELECT count(*) FROM knowledge_chunk_vector v JOIN knowledge_chunk c ON c.id=v.chunk_id WHERE c.document_id=${big.doc.id}`), '0');
    assert.equal((await ok(await a.request(big.path + '/index'))).status, 'FAILED');
  } finally { sql('ALTER TABLE knowledge_chunk_vector DROP CONSTRAINT test_vector_batch'); }
  assert.equal((await ok(await big.index())).count, chunkCount);
  assert.ok(embeddingState.calls.every(call => call.input.length <= 10));
  passed('RAG：Embedding 每批最多 10 条，向量跨 SQL 批次失败全部回滚，解除约束后完整恢复');
  return {conversationId, sources: answer.sources, path: fixture.path};
}
