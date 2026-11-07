// 只用于隔离协议/排序回归，不模拟真实模型准确率。
import {setTimeout as delay} from 'node:timers/promises';
export const rerankState={calls:[],httpStatus:null,invalid:false,slow:false,hold:false,release:null};
export async function mockRerank(input,response) {
  rerankState.calls.push(input);
  if(rerankState.httpStatus){const status=rerankState.httpStatus;rerankState.httpStatus=null;response.writeHead(status).end('synthetic upstream error');return;}
  const invalid=rerankState.invalid;rerankState.invalid=false;
  if(rerankState.hold)await new Promise(resolve=>{rerankState.release=resolve;});
  if(rerankState.slow){rerankState.slow=false;response.writeHead(200,{'Content-Type':'application/json'});response.write('{');await delay(3200);if(!response.destroyed)response.end();return;}
  if(response.destroyed)return;
  const {query,documents}=input.input;
  const results=documents.map((text,index)=>({index,relevance_score:query.includes('首次回复')&&text.includes('首次回复时限为两个工作日')?0.99:
    query.includes('验收时间')&&text.includes('每周四下午三点')?0.98:0.7-index*0.005,document:{text:'不得替换本地来源的上游正文'}}))
    .sort((a,b)=>b.relevance_score-a.relevance_score||a.index-b.index);
  if(invalid&&results.length>1)results[1].index=results[0].index;
  response.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({output:{results},usage:{total_tokens:100+documents.length},request_id:'synthetic-rerank'}));
}
