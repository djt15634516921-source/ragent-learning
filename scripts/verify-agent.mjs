import assert from 'node:assert/strict';
import {setTimeout as delay} from 'node:timers/promises';
import {agentState} from './mock-agent.mjs';
import {embeddingState} from './mock-embedding.mjs';
import {rerankState} from './mock-rerank.mjs';

export async function verifyAgent({client, until, passed}) {
  const owner = client(), other = client();
  await owner.register('agent_owner');
  await other.register('agent_other');
  const ok = async response => {
    assert.ok(response.ok, await response.clone().text());
    return response.json();
  };
  const base = await ok(await owner.json('/api/knowledge-bases', {name: 'Agent 工具回归'}));
  const path = '/api/knowledge-bases/' + base.id + '/agent/chat';
  const ask = (question, actor = owner) => actor.json(path, {question});
  for (const [filename, content] of [
    ['星河项目.md', '星河学习项目的验收时间为每周四下午三点。'],
    ['青禾项目.md', '青禾学习项目的验收时间为每周二上午十点。']
  ]) {
    const form = new FormData();
    form.append('file', new Blob([content]), filename);
    const task = await ok(await owner.post('/api/knowledge-bases/' + base.id + '/documents/ingest?async=false', form));
    assert.equal(task.status, 'SUCCESS');
  }

  let modelBefore = agentState.calls.length;
  let embeddingBefore = embeddingState.calls.length;
  assert.equal((await ask('Agent 比较两个项目的验收时间', client())).status, 401);
  assert.equal((await ask('Agent 比较两个项目的验收时间', other)).status, 404);
  assert.equal((await ask('   ')).status, 400);
  assert.equal(agentState.calls.length, modelBefore);
  assert.equal(embeddingState.calls.length, embeddingBefore);
  passed('Agent 入口在访问模型和 Embedding 之前校验登录、知识库归属与问题');

  const rerankBefore = rerankState.calls.length;
  const reply = await ok(await ask('Agent 比较两个项目的验收时间'));
  const requests = agentState.calls.slice(modelBefore);
  assert.equal(requests.length, 3);
  assert.equal(embeddingState.calls.length, embeddingBefore + 2);
  assert.equal(rerankState.calls.length, rerankBefore + 2);
  assert.deepEqual(requests[0].tools.map(tool => tool.function.name), ['search_knowledge']);
  const schema = requests[0].tools[0].function.parameters;
  assert.deepEqual(Object.keys(schema.properties), ['query']);
  assert.equal(schema.additionalProperties, false);
  const toolMessages = requests[2].messages.filter(message => message.role === 'tool');
  assert.deepEqual(toolMessages.map(message => message.tool_call_id), ['call-agent-1', 'call-agent-2']);
  assert.match(toolMessages[0].content, /每周四下午三点/);
  assert.match(toolMessages[1].content, /每周二上午十点/);
  assert.equal(reply.status, 'COMPLETED');
  assert.match(reply.answer, /每周四下午三点.*\[1\]/);
  assert.match(reply.answer, /每周二上午十点.*\[2\]/);
  assert.deepEqual(reply.toolCalls.map(call => call.status), ['SUCCESS', 'SUCCESS']);
  assert.deepEqual(reply.toolCalls.map(call => call.citationIds), [[1], [2]]);
  assert.deepEqual(reply.sources.map(source => source.fragment.filename), ['星河项目.md', '青禾项目.md']);
  assert.ok(reply.sources.every(source => Number.isFinite(source.fragment.rerankScore)));
  assert.equal((await ok(await owner.request('/api/conversations'))).length, 0);
  passed('AgentScope 实际执行两次 tool_call 并回填各自结果，复用 pgvector 与重排，回答引用和来源编号对应');

  embeddingBefore = embeddingState.calls.length;
  const direct = await ok(await ask('Agent 问候'));
  assert.equal(direct.status, 'COMPLETED');
  assert.match(direct.answer, /你好/);
  assert.deepEqual(direct.toolCalls, []);
  assert.deepEqual(direct.sources, []);
  assert.equal(embeddingState.calls.length, embeddingBefore);
  const forged = await ok(await ask('Agent 伪造工具归属'));
  assert.equal(forged.status, 'NO_EVIDENCE');
  assert.equal(forged.toolCalls[0].status, 'REJECTED');
  assert.equal(embeddingState.calls.length, embeddingBefore);
  passed('Agent 可直接回答问候，工具执行时拒绝模型伪造用户和知识库参数，且不发起检索');

  const empty = await ok(await ask('Agent 没有相关资料'));
  assert.equal(empty.status, 'NO_EVIDENCE');
  assert.equal(empty.toolCalls[0].status, 'NO_EVIDENCE');
  assert.deepEqual(empty.sources, []);
  assert.match(empty.answer, /未取得可用资料/);
  embeddingState.failNext = true;
  const recovered = await ok(await ask('Agent 第一次检索失败后再查'));
  assert.deepEqual(recovered.toolCalls.map(call => call.status), ['ERROR', 'SUCCESS']);
  assert.equal(recovered.toolCalls[0].errorCode, 'EMBEDDING_HTTP_FAILED');
  assert.equal(recovered.sources[0].citationId, 1);
  assert.match(recovered.answer, /每周四下午三点/);
  passed('Agent 无命中时明确说明资料不足，检索异常作为工具结果回传后可再次检索');

  embeddingBefore = embeddingState.calls.length;
  const capped = await ok(await ask('Agent 一次请求五次检索'));
  assert.equal(embeddingState.calls.length - embeddingBefore, 3);
  assert.ok(capped.toolCalls.some(call => call.status === 'LIMIT_REACHED'));
  assert.equal(capped.sources.length, 1, '同一片段在多次查询中复用本轮引用编号');
  modelBefore = agentState.calls.length;
  embeddingBefore = embeddingState.calls.length;
  const repeated = await ok(await ask('Agent 重复查询直到上限'));
  assert.equal(repeated.status, 'ITERATION_LIMIT');
  assert.ok(agentState.calls.length - modelBefore <= 4);
  assert.ok(embeddingState.calls.length - embeddingBefore <= 3);
  passed('Agent 限制 ReAct 迭代和同轮多工具的实际检索次数，重复证据沿用编号');

  modelBefore = agentState.calls.length;
  const modelFailure = await ask('Agent 模型请求失败');
  assert.equal(modelFailure.status, 502);
  const failure = await modelFailure.json();
  assert.equal(failure.code, 'AGENT_MODEL_FAILED');
  assert.doesNotMatch(failure.message, /private-provider-body/);
  assert.equal(agentState.calls.length, modelBefore + 1);
  const modelTimeout = await ask('Agent 等待模型超时');
  assert.equal(modelTimeout.status, 504);
  assert.equal((await modelTimeout.json()).code, 'AGENT_TIMEOUT');
  await until(() => agentState.timedOutConnections > 0, '超时后释放 Agent 模型连接');
  passed('Agent 上游错误有统一错误码，响应体等待超时会释放模型连接');

  modelBefore = agentState.calls.length;
  embeddingState.hold = true;
  try {
    const pending = ask('Agent 工具等待超时');
    await until(() => embeddingState.release !== null, 'Agent 工具已开始等待向量服务');
    const timedOut = await pending;
    assert.equal(timedOut.status, 504);
    assert.equal((await timedOut.json()).code, 'AGENT_TIMEOUT');
  } finally {
    embeddingState.hold = false;
    embeddingState.release?.();
    embeddingState.release = null;
  }
  await delay(150);
  assert.equal(agentState.calls.length, modelBefore + 1, '晚到的工具结果不能触发后续模型回答');
  passed('Agent 总超时覆盖工具执行，晚到检索不会继续发起下一轮模型调用');
}
