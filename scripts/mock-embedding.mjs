// 仅用于隔离回归：确定性向量帮助检查真正的 PostgreSQL 余弦查询与归属过滤。
import {setTimeout as delay} from 'node:timers/promises';
export const embeddingState = {calls: [], badNext: false, failNext: false, hold: false, release: null};
export async function mockEmbedding(input, response) {
  embeddingState.calls.push(input);
  if (embeddingState.failNext) { embeddingState.failNext = false; response.writeHead(503).end(); return; }
  const bad = embeddingState.badNext; embeddingState.badNext = false;
  if (input.input.some(text => text.includes('INDEX_SLOW'))) {
    if (embeddingState.hold) await new Promise(resolve => { embeddingState.release = resolve; });
    else await delay(100);
  }
  const data = input.input.map((text, index) => {
    const vector = Array(bad ? 2 : 1024).fill(0);
    const subject = text.includes('火星') ? 1 : text.includes('青禾') ? 2
      : text.includes('它每周') && !text.includes('星河') ? 3 : 0;
    vector[subject] = 1;
    return {index, embedding: vector};
  }).reverse(); // 服务允许乱序，用 index 恢复原片段对应关系。
  response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify({data}));
}
