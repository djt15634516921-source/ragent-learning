// 仅在主脚本的隔离 PostgreSQL/Redis/文件和确定性模型上执行。
import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {rerankState} from './mock-rerank.mjs';
import {embeddingState} from './mock-embedding.mjs';
import {compareRetrieval} from './compare-retrieval.mjs';

export async function verifyRerank({client,sql,work,until,passed,modelCalls}){
  const owner=client(),other=client();await owner.register('rerank_owner');await other.register('rerank_other');
  const ok=async response=>{assert.ok(response.ok,await response.clone().text());return response.json();};
  const base=await ok(await owner.json('/api/knowledge-bases',{name:'重排同候选验证'}));
  const basePath=`/api/knowledge-bases/${base.id}`;
  const ingest=async(kb,filename,text,chunkSize=800)=>{
    const form=new FormData();form.append('file',new Blob([text]),filename);
    const task=await ok(await owner.post(`/api/knowledge-bases/${kb}/documents/ingest?async=false&chunkSize=${chunkSize}&overlap=0`,form));
    assert.equal(task.status,'SUCCESS',JSON.stringify(task));return task.documentId;
  };
  for(let i=1;i<=6;i++)await ingest(base.id,`distractor-${i}.md`,`月泉演示资料${i}，说明办公环境与项目背景，不含时限。`);
  const relevant=await ingest(base.id,'reply-policy.md','月泉系统工单首次回复时限为两个工作日，受理人会记录处理进度。');
  await ingest(base.id,'acceptance.md','星河学习项目的验收时间是每周四下午三点。');
  const question='月泉系统工单首次回复时限是多少？';
  const counts=()=>[embeddingState.calls.length,rerankState.calls.length];
  const before=counts();
  const comparison=await ok(await owner.json(basePath+'/search/compare',{question}));
  assert.deepEqual(counts(),before.map(n=>n+1));assert.equal(comparison.sameCandidates,true);
  const {baseline,reranked}=comparison;
  assert.deepEqual(baseline.retrieval.beforeRerank,reranked.retrieval.beforeRerank);
  assert.equal(baseline.sources.some(source=>source.documentId===relevant),false);
  assert.equal(reranked.sources[0].documentId,relevant);
  assert.equal(reranked.sources[0].score,1);assert.equal(reranked.sources[0].rerankScore,0.99);
  assert.match(reranked.sources[0].content,/两个工作日/);assert.doesNotMatch(reranked.sources[0].content,/上游正文/);
  assert.equal(reranked.retrieval.rerankStatus,'APPLIED');assert.equal(baseline.retrieval.rerankStatus,'DISABLED');
  assert.equal(reranked.retrieval.recalledCount,8);assert.equal(reranked.retrieval.candidateCount,8);assert.equal(reranked.retrieval.returnedCount,4);
  assert.equal(reranked.retrieval.rerankTokens,108);assert.equal(reranked.retrieval.recallBudget,16);assert.equal(reranked.retrieval.candidateLimit,12);
  assert.equal(rerankState.calls.at(-1).parameters.top_n,8);
  passed('重排：一次召回共享候选，原第7名证据进入首位，原始正文/向量分保留，三段预算与使用量可见');

  const small=await ok(await owner.json(basePath+'/search/compare',{question,recallBudget:4,candidateLimit:4}));
  assert.equal(small.reranked.sources.some(source=>source.documentId===relevant),false);
  const calls=counts();
  for(const options of [{recallBudget:101},{candidateLimit:3},{topK:21},{recallBudget:4,candidateLimit:8},{minScore:2}])
    assert.equal((await owner.json(basePath+'/search/detail',{question,...options})).status,400);
  assert.equal((await other.json(basePath+'/search/compare',{question})).status,404);
  assert.equal((await client().json(basePath+'/search/compare',{question})).status,401);
  const empty=await ok(await owner.json('/api/knowledge-bases',{name:'未入库重排验证'}));
  assert.equal((await owner.json(`/api/knowledge-bases/${empty.id}/search/compare`,{question})).status,409);
  assert.deepEqual(counts(),calls);
  passed('重排：未召回证据不能被恢复，非法预算和他人/无索引资源在模型调用前拒绝');

  let count=rerankState.calls.length;
  const disabled=await ok(await owner.json(basePath+'/search/detail',{question,rerank:false}));
  assert.equal(disabled.retrieval.rerankStatus,'DISABLED');assert.equal(rerankState.calls.length,count);
  const noHit=await ok(await owner.json(basePath+'/search/detail',{question:'火星温度'}));
  assert.equal(noHit.retrieval.rerankStatus,'SKIPPED_EMPTY');assert.equal(rerankState.calls.length,count);
  const budget=await ok(await owner.json(basePath+'/search/detail',{question:'问题'.repeat(1800)}));
  assert.equal(budget.retrieval.rerankStatus,'SKIPPED_BUDGET');assert.equal(rerankState.calls.length,count);
  for(const [mode,status,code] of [['httpStatus','FALLBACK_ERROR','RERANK_RATE_LIMITED'],['invalid','FALLBACK_ERROR','RERANK_RESPONSE_INVALID'],['slow','FALLBACK_TIMEOUT','RERANK_TIMEOUT']]){
    rerankState[mode]=mode==='httpStatus'?429:true;
    const result=await ok(await owner.json(basePath+'/search/detail',{question}));
    assert.equal(result.retrieval.rerankStatus,status);assert.equal(result.retrieval.errorCode,code);
    assert.deepEqual(result.sources,disabled.sources);assert.equal(result.retrieval.rerankAttempts,1);
    assert.equal(rerankState.calls.length,++count,'失败不自动重试');
  }
  passed('重排：关闭/无命中/超字符预算不调用，429/坏索引/响应体超时显式回退且不重复请求');

  const chat=await ok(await owner.json('/api/chat',{conversationId:'rerank-normal',question,knowledgeBaseId:base.id}));
  assert.equal(chat.sources[0].documentId,relevant);assert.match(chat.answer,/两个工作日/);assert.equal(chat.retrieval.rerankStatus,'APPLIED');
  const history=await ok(await owner.request('/api/conversations/rerank-normal/messages'));
  assert.equal(history[0].content,question);assert.deepEqual(history[1].sources,chat.sources);
  const stream=await owner.json('/api/chat/stream',{conversationId:'rerank-sse',question,knowledgeBaseId:base.id});
  const frames=parseFrames(await stream.text());const done=frames.find(frame=>frame.event==='done');
  assert.ok(done);assert.equal(done.data.sources[0].documentId,relevant);assert.equal(done.data.retrieval.rerankStatus,'APPLIED');
  assert.equal(sql("SELECT count(*) FROM chat_message WHERE conversation_id='rerank-sse'"),'2');
  count=rerankState.calls.length;
  await ok(await owner.json('/api/chat',{conversationId:'rerank-plain',question:'普通聊天'}));assert.equal(rerankState.calls.length,count);
  sql("UPDATE chat_message SET sources_json=(SELECT jsonb_agg(element-'rerankScore') FROM jsonb_array_elements(sources_json) element) WHERE conversation_id='rerank-normal' AND role='assistant'");
  const old=await ok(await owner.request('/api/conversations/rerank-normal/messages'));
  assert.equal(old[1].sources[0].rerankScore,null);assert.equal(old[1].sources[0].score,1);
  passed('重排：普通/SSE使用相同重排证据，完整回答后保存来源分数，旧JSON快照兼容，普通聊天不调用');

  for(const cancel of [true,false]){
    rerankState.hold=true;const cid=cancel?'rerank-cancel':'rerank-timeout',modelsBefore=modelCalls.length;
    try{
      const response=await owner.json('/api/chat/stream',{conversationId:cid,question,knowledgeBaseId:base.id});
      const reader=response.body.getReader(),decoder=new TextDecoder();let text='';
      while(!text.includes('\n\n')){const part=await reader.read();if(part.done)throw new Error('未收到start');text+=decoder.decode(part.value,{stream:true});}
      const start=parseFrames(text).find(frame=>frame.event==='start');assert.ok(start);
      await until(()=>!!rerankState.release,'重排远程等待');
      if(cancel){
        assert.equal((await other.post(`/api/conversations/${cid}/streams/${start.data.streamId}/cancel`,'')).status,404);
        const stopped=await ok(await owner.post(`/api/conversations/${cid}/streams/${start.data.streamId}/cancel`,''));assert.equal(stopped.status,'CANCELLED');
      }
      try{while(true){const part=await reader.read();if(part.done)break;text+=decoder.decode(part.value,{stream:true});}}
      catch(error){if(!String(error).includes('terminated'))throw error;}
      assert.equal(parseFrames(text).some(frame=>frame.event==='done'),false);
    }finally{rerankState.hold=false;rerankState.release?.();rerankState.release=null;}
    await delay(150);assert.equal(modelCalls.length,modelsBefore);assert.equal(sql(`SELECT count(*) FROM chat_message WHERE conversation_id='${cid}'`),'0');
  }
  passed('重排：远程等待时显式取消/流式超时均停止后续回答，不降级继续生成，不保存半轮或发送done');

  const context=await ok(await owner.json('/api/knowledge-bases',{name:'重排上下文预算'}));
  const ids=[];for(const [i,size]of [4000,4000,800,600,500].entries())ids.push(await ingest(context.id,`budget-${i}.md`,'文'.repeat(size),4000));
  const selected=await ok(await owner.json(`/api/knowledge-bases/${context.id}/search/detail`,{question:'资料内容',rerank:true}));
  assert.deepEqual(selected.sources.map(source=>source.documentId),[ids[0],ids[2],ids[3],ids[4]]);
  assert.equal(selected.retrieval.contextCharacters,5900);assert.equal(selected.retrieval.afterRerank.length,5);
  passed('重排：先排完整候选再限制上下文，跳过超预算整块并继续选取，最终片段数和字符预算同时生效');

  const report=await compareRetrieval({request:owner.request,knowledgeBaseId:base.id,includeAnswers:true,label:'isolated-deterministic-rerank',
    cases:[{id:'late-evidence',question,expectedFiles:['reply-policy.md'],expectedEvidence:['两个工作日']},
      {id:'unannotated',question:'火星没有提供的事实',expectedFiles:[]}]});
  assert.equal(report.metrics.baseline.documentHitRateAtK,0);assert.equal(report.metrics.reranked.documentHitRateAtK,1);
  assert.equal(report.metrics.reranked.reportedRerankTokens,108);assert.equal(report.metrics.baseline.reportedRerankTokens,0);
  assert.equal(report.results[0].answerComparisonStatus,'READY_FOR_HUMAN_REVIEW');
  assert.notEqual(report.results[0].answers.baseline.conversationId,report.results[0].answers.reranked.conversationId);
  writeFileSync(join(work,'rerank-comparison.json'),JSON.stringify(report,null,2));
  passed('重排：同候选检索A/B、独立新会话答案对照与证据字面覆盖报告；模拟结果不冒充真实模型质量');
}
function parseFrames(text){return text.split('\n\n').filter(frame=>frame.includes('data:')).map(frame=>({event:frame.match(/event:\s*(\w+)/)[1],data:JSON.parse(frame.match(/data:\s*(.*)/)[1])}));}
