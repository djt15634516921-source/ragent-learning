// 只用于独立容器回归：检查改写协议、失败降级和真正传给 Embedding 的问题。
import {setTimeout as delay} from 'node:timers/promises';
export const rewriteState = {calls: [], next: 'normal'};
export async function mockRewrite(input, response) {
  const last = input.messages?.at(-1)?.content;
  if (typeof last !== 'string' || !last.startsWith('检索问题改写输入')) return false;
  const data = JSON.parse(last.slice(last.indexOf('\n') + 1));
  rewriteState.calls.push({input, data});
  const mode = rewriteState.next;
  rewriteState.next = 'normal';
  if (mode === 'http-error') { response.writeHead(503).end(); return true; }
  if (mode === 'timeout') await delay(800);
  if (response.destroyed) return true;
  const context = data.userQuestions.join('\n');
  let status = 'UNCHANGED', question = data.question;
  if (mode === 'ambiguous') status = 'AMBIGUOUS';
  else if (question === '它每周什么时候验收？') {
    status = 'REWRITTEN';
    question = (context.lastIndexOf('青禾') > context.lastIndexOf('星河') ? '青禾' : '星河') + '学习项目每周什么时候验收？';
  } else if (question === '它的学习代号是什么？' && context.includes('浏览器资料')) {
    status = 'REWRITTEN'; question = '浏览器资料的学习代号是什么？';
  }
  const content = mode === 'malformed' ? '{"question":42}' : JSON.stringify({status, question});
  response.writeHead(200, {'Content-Type': 'application/json'});
  response.end(JSON.stringify({choices: [{message: {content}}]}));
  return true;
}
