import assert from 'node:assert/strict';
import {agentState} from './mock-agent.mjs';

async function read(response) {
  assert.ok(response.ok, await response.clone().text());
  return response.json();
}

const route = '/api/agent/conversations';
const messages = id => route + '/' + id + '/messages';
const ask = (actor, baseId, conversationId, question) => actor.json(
  '/api/knowledge-bases/' + baseId + '/agent/chat', {conversationId, question});

function checkpoint(sql, id) {
  return {
    revision: sql("SELECT revision FROM t_agent_conversation WHERE id = '" + id + "'"),
    messages: sql("SELECT count(*) FROM t_agent_message WHERE conversation_id = '" + id + "'"),
    payloadHash: sql("SELECT md5(payload::text) FROM t_agent_state WHERE session_id = '" + id + "' AND state_key = 'agent_state'")
  };
}

export async function verifyAgentConversations({fixture, sql, until, passed}) {
  const {owner, other, base} = fixture;
  const createdResponse = await owner.json(route, {knowledgeBaseId: base.id, title: 'Agent 状态恢复验证'});
  assert.equal(createdResponse.status, 201);
  const created = await createdResponse.json();
  const id = created.conversationId;
  assert.match(id, /^[a-f0-9-]{36}$/);
  assert.equal(created.knowledgeBaseId, base.id);
  assert.equal(created.title, 'Agent 状态恢复验证');
  assert.ok((await read(await owner.request(route + '?knowledgeBaseId=' + base.id))).some(row => row.conversationId === id));
  assert.deepEqual((await read(await owner.request(messages(id)))).messages, []);

  const question = '星河项目每周什么时候验收？';
  const first = await read(await ask(owner, base.id, id, question));
  assert.equal(first.conversationId, id);
  assert.match(first.answer, /每周四下午三点/);
  const beforeFollowUp = agentState.calls.length;
  const followUp = await read(await ask(owner, base.id, id, '那青禾项目呢？'));
  assert.match(followUp.answer, /每周二上午十点/);
  assert.equal(followUp.toolCalls[0].query, '青禾学习项目每周什么时候验收');
  assert.equal(followUp.sources[0].citationId, 1);
  const sent = agentState.calls[beforeFollowUp].messages;
  assert.ok(sent.some(message => message.role === 'user' && message.content.includes(question)));
  assert.ok(sent.some(message => message.role === 'assistant' && message.tool_calls?.length));
  assert.ok(sent.some(message => message.role === 'tool' && message.content.includes('每周四下午三点')));
  const saved = checkpoint(sql, id);
  assert.equal(saved.revision, '2');
  assert.equal(saved.messages, '4');
  assert.match(saved.payloadHash, /^[a-f0-9]{32}$/);

  const latest = await read(await owner.request(messages(id) + '?size=2'));
  const earlier = await read(await owner.request(messages(id) + '?size=2&beforeId=' + latest.nextBeforeId));
  assert.equal(latest.hasMore, true);
  assert.equal(earlier.hasMore, false);
  assert.deepEqual([...earlier.messages, ...latest.messages].map(message => message.content),
    [question, first.answer, '那青禾项目呢？', followUp.answer]);
  assert.deepEqual(latest.messages[1].toolCalls, followUp.toolCalls);
  assert.deepEqual(latest.messages[1].sources, followUp.sources);
  passed('Agent 会话创建、列表和游标历史，追问带回上一轮结构化工具消息，保存的引用与回答一致');

  const attempts = agentState.calls.length;
  assert.deepEqual(await read(await other.request(route)), []);
  assert.equal((await other.request(messages(id))).status, 404);
  assert.equal((await other.request(route + '/' + id, {method: 'DELETE'})).status, 404);
  assert.equal((await ask(other, base.id, id, '读取别人的会话')).status, 404);
  const mismatch = await read(await owner.json('/api/knowledge-bases', {name: 'Agent 另一个知识库'}));
  const rejected = await ask(owner, mismatch.id, id, '切换会话知识库');
  assert.equal(rejected.status, 409);
  assert.equal((await rejected.json()).code, 'AGENT_KNOWLEDGE_BASE_MISMATCH');
  assert.equal(agentState.calls.length, attempts);
  passed('Agent 会话列表、历史、删除和继续提问均校验归属，原会话固定知识库');

  const otherBase = await read(await other.json('/api/knowledge-bases', {name: '另一个用户的 Agent 资料'}));
  const form = new FormData();
  form.append('file', new Blob(['另一个用户的星河资料记载，验收时间为每周六上午九点。']), 'private.md');
  const task = await read(await other.post('/api/knowledge-bases/' + otherBase.id + '/documents/ingest?async=false', form));
  assert.equal(task.status, 'SUCCESS');
  const otherSession = await read(await other.json(route, {knowledgeBaseId: otherBase.id}));
  const active = await read(await owner.json(route, {knowledgeBaseId: base.id}));
  const pending = ask(owner, base.id, active.conversationId, 'Agent 并发等待检索');
  try {
    await until(() => agentState.release !== null, 'Agent 并发模型等待');
    const sameSession = await ask(owner, base.id, active.conversationId, 'Agent 问候');
    assert.equal(sameSession.status, 409);
    assert.equal((await sameSession.json()).code, 'AGENT_CONVERSATION_BUSY');
    assert.equal((await owner.request(route + '/' + active.conversationId, {method: 'DELETE'})).status, 409);
    const concurrent = await read(await ask(other, otherBase.id, otherSession.conversationId, question));
    assert.match(concurrent.answer, /每周六上午九点/);
    assert.deepEqual(concurrent.sources.map(source => source.fragment.filename), ['private.md']);
  } finally {
    agentState.release?.();
    agentState.release = null;
  }
  const released = await read(await pending);
  assert.match(released.answer, /每周四下午三点/);
  assert.deepEqual(released.sources.map(source => source.fragment.filename), ['星河项目.md']);
  passed('共享 ReActAgent 并行处理不同会话且工具范围隔离，同一会话拒绝重复运行和运行中删除');

  const timeout = await ask(owner, base.id, id, 'Agent 等待模型超时');
  assert.equal(timeout.status, 504);
  assert.deepEqual(checkpoint(sql, id), saved);
  sql("ALTER TABLE t_agent_state ADD CONSTRAINT agent_test_block_state CHECK (session_id <> '" + id + "') NOT VALID");
  try {
    const failure = await ask(owner, base.id, id, 'Agent 状态写入失败');
    assert.equal(failure.status, 503);
    assert.equal((await failure.json()).code, 'DATABASE_UNAVAILABLE');
    assert.deepEqual(checkpoint(sql, id), saved);
  } finally {
    sql('ALTER TABLE t_agent_state DROP CONSTRAINT agent_test_block_state');
  }
  const ownerId = sql("SELECT user_id FROM t_agent_conversation WHERE id = '" + id + "'");
  assert.equal(sql("SELECT payload->>'user_id' FROM t_agent_state WHERE session_id = '" + id + "'"), ownerId);
  sql("UPDATE t_agent_state SET payload = jsonb_set(payload, '{user_id}', to_jsonb('invalid-owner'::text), false) WHERE session_id = '" + id + "'");
  try {
    const before = agentState.calls.length;
    const invalid = await ask(owner, base.id, id, 'Agent 问候');
    assert.equal(invalid.status, 503);
    assert.equal((await invalid.json()).code, 'AGENT_STATE_UNAVAILABLE');
    assert.equal(agentState.calls.length, before);
  } finally {
    sql("UPDATE t_agent_state SET payload = jsonb_set(payload, '{user_id}', to_jsonb('" + ownerId + "'::text), false) WHERE session_id = '" + id + "'");
  }
  assert.deepEqual(checkpoint(sql, id), saved);
  passed('Agent 超时和状态写入约束故障保留上次完整轮次，异常归属状态拒绝加载');
  return {owner, other, base, id, otherId: otherSession.conversationId, saved};
}

export async function verifyAgentAfterRestart({session, sql, passed}) {
  const {owner, other, base, id, otherId, saved} = session;
  assert.deepEqual(checkpoint(sql, id), saved);
  assert.equal((await read(await owner.request(messages(id)))).messages.length, 4);
  const modelBefore = agentState.calls.length;
  const reply = await read(await ask(owner, base.id, id, '那它是什么时候？'));
  assert.match(reply.answer, /每周二上午十点/);
  const restored = agentState.calls[modelBefore].messages;
  assert.ok(restored.some(message => message.role === 'user' && message.content.includes('那青禾项目呢？')));
  assert.ok(restored.some(message => message.role === 'tool' && message.content.includes('每周二上午十点')));
  assert.equal(checkpoint(sql, id).revision, '3');
  assert.equal((await read(await owner.request(messages(id)))).messages.length, 6);
  passed('重启 Spring Boot 后 PostgreSQL AgentScope 状态恢复，继续追问带回原有工具消息');

  assert.equal((await owner.request(route + '/' + id, {method: 'DELETE'})).status, 204);
  assert.equal((await owner.request(messages(id))).status, 404);
  assert.equal((await ask(owner, base.id, id, 'Agent 问候')).status, 404);
  assert.equal(checkpoint(sql, id).revision, '');
  assert.equal(checkpoint(sql, id).messages, '0');
  assert.equal(checkpoint(sql, id).payloadHash, '');
  assert.equal((await read(await other.request(messages(otherId)))).messages.length, 2);
  passed('删除 Agent 会话级联清除消息和 SDK 状态，旧 ID 无法重新运行，其他会话保留');
}
