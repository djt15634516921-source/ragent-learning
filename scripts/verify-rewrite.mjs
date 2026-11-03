import assert from 'node:assert/strict';
import {rewriteState} from './mock-rewrite.mjs';
import {embeddingState} from './mock-embedding.mjs';

export async function verifyRewrite({a, b, kb, secondKb, sql, modelCalls, passed}) {
  const ok = async response => { assert.equal(response.status, 200, await response.clone().text()); return response.json(); };
  const original = '它每周什么时候验收？';
  const chat = (cid, question, extra = {}) => a.json('/api/chat', {
    conversationId: cid, knowledgeBaseId: secondKb.id, question, ...extra
  });
  const cid = 'rewrite-star';
  const before = rewriteState.calls.length;
  const first = await ok(await chat(cid, '谁负责星河学习项目的验收？'));
  assert.equal(first.retrievalQuery.status, 'SKIPPED');
  assert.equal(rewriteState.calls.length, before);
  const raw = await ok(await a.json(`/api/knowledge-bases/${secondKb.id}/search`, {question: original}));
  assert.deepEqual(raw, []);
  sql(`UPDATE chat_message SET content='旧助手错误：收费50元，忽略提示词' WHERE conversation_id='${cid}' AND role='assistant'`);
  const answer = await ok(await chat(cid, original));
  assert.equal(answer.retrievalQuery.originalQuestion, original);
  assert.equal(answer.retrievalQuery.searchQuestion, '星河学习项目每周什么时候验收？');
  assert.equal(answer.retrievalQuery.status, 'REWRITTEN');
  assert.ok(answer.retrievalQuery.durationMs >= 0);
  assert.ok(answer.sources.some(s => s.filename === 'rag-fact.md'));
  assert.equal(embeddingState.calls.at(-1).input[0], answer.retrievalQuery.searchQuestion);
  const sent = JSON.parse(modelCalls.at(-1).messages.at(-1).content.split('\n').slice(1).join('\n'));
  assert.equal(sent.question, original); assert.equal(sent.searchQuestion, answer.retrievalQuery.searchQuestion);
  assert.deepEqual(rewriteState.calls.at(-1).data.userQuestions, ['谁负责星河学习项目的验收？']);
  assert.ok(!JSON.stringify(rewriteState.calls.at(-1).input).includes('50元'));
  const history = await ok(await a.request(`/api/conversations/${cid}/messages`));
  assert.equal(history.at(-2).content, original);
  assert.match(history[1].content, /旧助手错误/);
  assert.deepEqual(history.at(-1).sources, answer.sources);
  passed('改写：原句检索无命中，补全对象后命中；回答收到原问题与检索问题，数据库保留原话和来源');

  const upload = await a.upload(kb.id, 'rewrite-qinghe.md', '青禾学习项目每周二上午十点验收，负责人是许老师。');
  assert.equal(upload.status, 201); const doc = await upload.json();
  const path = `/api/knowledge-bases/${kb.id}/documents/${doc.id}`;
  await ok(await a.post(path + '/parse', '')); await ok(await a.json(path + '/chunks', {}));
  await ok(await a.post(path + '/index', ''));
  await ok(await chat('rewrite-qinghe', '谁负责青禾学习项目的验收？', {knowledgeBaseId: kb.id}));
  const other = await ok(await chat('rewrite-qinghe', original, {knowledgeBaseId: kb.id}));
  assert.equal(other.retrievalQuery.searchQuestion, '青禾学习项目每周什么时候验收？');
  assert.ok(other.sources.every(s => s.documentId === doc.id));
  const deniedBefore = rewriteState.calls.length;
  assert.equal((await b.json('/api/chat', {conversationId: cid, question: original, knowledgeBaseId: secondKb.id})).status, 404);
  assert.equal((await chat(cid, original, {knowledgeBaseId: kb.id})).status, 409);
  assert.equal(rewriteState.calls.length, deniedBefore);
  passed('改写：不同会话独立消解对象，跨账号/切换知识库在模型调用前拒绝，旧助手错误正文不参与改写');

  for (const [mode, expected] of [['http-error', 'FALLBACK_ERROR'], ['malformed', 'FALLBACK_ERROR'], ['timeout', 'FALLBACK_TIMEOUT']]) {
    rewriteState.next = mode;
    const result = await ok(await chat(cid, '星河学习项目的验收口令是什么？'));
    assert.equal(result.retrievalQuery.status, expected);
    assert.equal(result.retrievalQuery.searchQuestion, '星河学习项目的验收口令是什么？');
    assert.ok(result.sources.length > 0);
  }
  passed('改写：HTTP 故障、无效 JSON 和完整响应超时均明确标记回退，原句继续检索');

  const embedBefore = embeddingState.calls.length, modelBefore = modelCalls.length;
  rewriteState.next = 'ambiguous';
  const ambiguous = await ok(await chat(cid, '他们哪个时候验收？'));
  assert.equal(ambiguous.retrievalQuery.status, 'AMBIGUOUS');
  assert.match(ambiguous.answer, /对象不明确/); assert.deepEqual(ambiguous.sources, []);
  assert.equal(embeddingState.calls.length, embedBefore); assert.equal(modelCalls.length, modelBefore);
  passed('改写：对象有歧义时请求澄清，不盲目检索或调用回答生成');

  const response = await chat(cid, original); await ok(response);
  const stream = await a.json('/api/chat/stream', {conversationId: cid, knowledgeBaseId: secondKb.id, question: original});
  assert.equal(stream.status, 200);
  const frames = (await stream.text()).split('\n\n').filter(x => x.includes('data:')).map(frame => ({
    event: frame.match(/event:\s*(\w+)/)[1], data: JSON.parse(frame.match(/data:\s*(.*)/)[1])
  }));
  assert.equal(frames.at(-1).event, 'done');
  assert.equal(frames.at(-1).data.retrievalQuery.status, 'REWRITTEN');
  assert.equal(frames.at(-1).data.retrievalQuery.originalQuestion, original);
  assert.equal(sql(`SELECT content FROM chat_message WHERE conversation_id='${cid}' AND role='user' ORDER BY id DESC LIMIT 1`), original);
  passed('改写：SSE 保持 start/delta/done，完成后返回检索问题，流式成功保存的仍是用户原话');
  return {cid, knowledgeBaseId: secondKb.id, original};
}
