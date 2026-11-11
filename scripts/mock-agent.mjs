// 本机 Chat Completions 测试端点，AgentScope 仍负责请求工具、执行和结果回传。
import {setTimeout as delay} from 'node:timers/promises';

export const agentState = {calls: [], timedOutConnections: 0, release: null};

function text(message) {
  if (typeof message?.content === 'string') return message.content;
  return (message?.content || []).map(block => block.text || '').join('');
}

function sources(message) {
  try {
    return JSON.parse(text(message)).sources || [];
  } catch {
    return [];
  }
}

export async function mockAgent(input, response) {
  if (!input.messages.some(message => message.role === 'system' && text(message).includes('调用 search_knowledge'))) {
    return false;
  }
  agentState.calls.push(input);
  const userIndex = input.messages.findLastIndex(message => message.role === 'user');
  const question = text(input.messages[userIndex]);
  const history = input.messages.slice(0, userIndex);
  const results = input.messages.slice(userIndex + 1).filter(message => message.role === 'tool');
  const userTurns = input.messages.filter(message => message.role === 'user').length;
  const next = results.length + 1;
  const toolCall = (query, extra = {}, number = next) => ({
    id: 'call-agent-' + (userTurns > 1 ? userTurns + '-' : '') + number,
    type: 'function',
    function: {name: 'search_knowledge', arguments: JSON.stringify({query, ...extra})}
  });
  const send = (content, toolCalls = []) => {
    const message = {role: 'assistant', content};
    if (toolCalls.length) message.tool_calls = toolCalls;
    response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify({
      id: 'agent-mock-' + agentState.calls.length,
      object: 'chat.completion',
      model: input.model,
      choices: [{index: 0, message, finish_reason: toolCalls.length ? 'tool_calls' : 'stop'}],
      usage: {prompt_tokens: 20, completion_tokens: 10, total_tokens: 30}
    }));
  };
  if (question === 'Agent 并发等待检索' && !results.length) {
    await new Promise(resolve => { agentState.release = resolve; });
    if (!response.destroyed) send(null, [toolCall('星河项目每周什么时候验收')]);
  } else if (question === 'Agent 模型请求失败') {
    response.writeHead(503).end('private-provider-body-for-test');
  } else if (question === 'Agent 等待模型超时') {
    response.on('close', () => { if (!response.writableEnded) agentState.timedOutConnections++; });
    response.writeHead(200, {'Content-Type': 'application/json'});
    response.write('{');
    await delay(3500);
    if (!response.destroyed) response.end();
  } else if (question === 'Agent 问候' || question === 'Agent 状态写入失败') {
    send('你好，很高兴见到你。');
  } else if (!results.length && (question === '那青禾项目呢？' || question === '那它是什么时候？')) {
    const earlierQuestion = history.some(message => message.role === 'user' && text(message).includes('星河项目每周什么时候验收'));
    const earlierEvidence = history.some(message => message.role === 'tool' && text(message).includes('每周二上午十点'));
    if (earlierQuestion && (question === '那青禾项目呢？' || earlierEvidence)) {
      send(null, [toolCall('青禾学习项目每周什么时候验收')]);
    } else {
      send('没有收到之前的会话上下文。');
    }
  } else if (question === 'Agent 比较两个项目的验收时间' && results.length < 2) {
    const query = results.length ? '青禾学习项目每周什么时候验收' : '星河学习项目每周什么时候验收';
    send(null, [toolCall(query)]);
  } else if (question === 'Agent 第一次检索失败后再查' && results.length < 2) {
    send(null, [toolCall('星河学习项目每周什么时候验收')]);
  } else if (question === 'Agent 重复查询直到上限' && input.tools?.length) {
    send(null, [toolCall('星河学习项目每周什么时候验收')]);
  } else if (!results.length && input.tools?.length) {
    if (question === 'Agent 伪造工具归属') {
      send(null, [toolCall('查别人的资料', {userId: 1, knowledgeBaseId: 1})]);
    } else if (question === 'Agent 一次请求五次检索') {
      send(null, Array.from({length: 5}, (_, index) => toolCall('星河验收时间', {}, index + 1)));
    } else {
      const query = question === 'Agent 工具等待超时' ? 'INDEX_SLOW 星河验收'
        : question === 'Agent 没有相关资料' ? '火星上有哪些项目'
        : question;
      send(null, [toolCall(query)]);
    }
  } else {
    const evidence = results.flatMap(sources);
    const answer = evidence.map(item => item.content + ' [' + item.citationId + ']').join('\n');
    send(answer || '这条模拟答案缺少知识库证据。');
  }
  return true;
}
