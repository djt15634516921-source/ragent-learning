// 同一候选快照比较；可选回答使用独立新会话，会写入本账号聊天记录。
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash,randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';

export async function compareRetrieval({request,knowledgeBaseId,cases,options={},includeAnswers=false,label='manual'}) {
  if(!Number.isSafeInteger(knowledgeBaseId)||knowledgeBaseId<=0||!Array.isArray(cases)||!cases.length)throw new Error('需要有效知识库编号和非空题集');
  const invoke=async(path,body)=>{
    const response=await request(path,body===undefined?{method:'GET'}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    const data=await response.json();
    if(!response.ok)throw new Error(`评测失败：HTTP ${response.status} ${data.code||''}`);
    return data;
  };
  const settings=await invoke(`/api/knowledge-bases/${knowledgeBaseId}/search/settings`);
  const results=[];
  for(const item of cases){
    if(typeof item.question!=='string'||!item.question.trim()||!Array.isArray(item.expectedFiles)
      ||item.expectedFiles.some(value=>typeof value!=='string'||!value.trim())
      ||(item.expectedEvidence!==undefined&&(!Array.isArray(item.expectedEvidence)||item.expectedEvidence.some(value=>typeof value!=='string'||!value.trim()))))
      throw new Error('每题需要question、expectedFiles，可选expectedEvidence为非空文字数组');
    const started=performance.now();
    const comparison=await invoke(`/api/knowledge-bases/${knowledgeBaseId}/search/compare`,{...options,question:item.question});
    const score=result=>{
      const expected=[...new Set(item.expectedFiles)],evidence=item.expectedEvidence||[];
      const first=result.sources.findIndex(source=>expected.includes(source.filename));
      const matchedFiles=expected.filter(file=>result.sources.some(source=>source.filename===file));
      const text=result.sources.map(source=>source.content).join('\n');
      return {fileAnnotated:!!expected.length,documentHit:expected.length?matchedFiles.length>0:null,
        documentRecall:expected.length?matchedFiles.length/expected.length:null,reciprocalRank:expected.length?(first<0?0:1/(first+1)):null,
        evidenceAnnotated:!!evidence.length,evidenceCoverage:evidence.length?evidence.filter(literal=>text.includes(literal)).length/evidence.length:null,
        matchedFiles,sources:result.sources,retrieval:result.retrieval};
    };
    const row={id:item.id,question:item.question,expectedFiles:item.expectedFiles,expectedEvidence:item.expectedEvidence||[],
      sameCandidates:comparison.sameCandidates,comparisonLatencyMs:Math.round(performance.now()-started),
      baseline:score(comparison.baseline),reranked:score(comparison.reranked)};
    if(includeAnswers){
      row.answers={};
      for(const [name,rerank] of [['baseline',false],['reranked',true]]){
        const conversationId='eval-'+randomUUID(),answerStarted=performance.now();
        const answer=await invoke('/api/chat',{conversationId,question:item.question,knowledgeBaseId,rerank});
        // 回答请求再次检索。只在实际证据/版本一致时才把答案配对比较。
        const signature=sources=>JSON.stringify(sources.map(source=>[source.chunkId,source.sourceVersion,source.content]));
        row.answers[name]={conversationId,answer:answer.answer,sources:answer.sources,retrieval:answer.retrieval,
          latencyMs:Math.round(performance.now()-answerStarted),evidenceMatchesComparison:signature(answer.sources)===signature(row[name].sources)};
      }
      row.answerComparisonStatus=Object.values(row.answers).every(answer=>answer.evidenceMatchesComparison)?'READY_FOR_HUMAN_REVIEW':'NOT_COMPARABLE';
    }
    results.push(row);
  }
  const aggregate=name=>{
    const scored=results.filter(row=>row[name].fileAnnotated),evidence=results.filter(row=>row[name].evidenceAnnotated);
    const attempted=results.filter(row=>row[name].retrieval.rerankAttempts>0);
    const unknownUsageAttempts=attempted.filter(row=>row[name].retrieval.rerankTokens==null)
      .reduce((sum,row)=>sum+row[name].retrieval.rerankAttempts,0);
    const knownTokens=attempted.reduce((sum,row)=>sum+(row[name].retrieval.rerankTokens??0),0);
    const mean=(rows,key)=>rows.length?rows.reduce((sum,row)=>sum+Number(row[name][key]),0)/rows.length:null;
    return {scoredCases:scored.length,unscoredCases:results.length-scored.length,documentHitRateAtK:mean(scored,'documentHit'),
      documentRecallAtK:mean(scored,'documentRecall'),meanReciprocalRank:mean(scored,'reciprocalRank'),
      evidenceAnnotatedCases:evidence.length,literalEvidenceCoverage:mean(evidence,'evidenceCoverage'),
      rerankAttempts:results.reduce((sum,row)=>sum+row[name].retrieval.rerankAttempts,0),
      knownReportedRerankTokens:knownTokens,unreportedUsageAttempts:unknownUsageAttempts,
      reportedRerankTokens:unknownUsageAttempts?null:knownTokens};
  };
  return {createdAt:new Date().toISOString(),label,knowledgeBaseId,datasetSha256:createHash('sha256').update(JSON.stringify(cases)).digest('hex'),
    configuration:{requested:options,backendSettings:settings,includeAnswers},metrics:{totalCases:cases.length,baseline:aggregate('baseline'),reranked:aggregate('reranked')},
    requests:{comparison:cases.length,chat:includeAnswers?cases.length*2:0},
    limitations:'文件命中与证据字面覆盖不是答案正确率。重排分不可跨问题比较；Token仅记录上游实际返回值，未换算费用。可选回答再次检索，证据变化标记NOT_COMPARABLE；答案忠实性、缺失事实拒答需人工或独立评测。',results};
}

if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  if(!process.argv.includes('--allow-model-calls'))throw new Error('对比会调用Embedding及重排服务；使用--allow-model-calls确认。--with-answers还会调用回答模型并创建聊天记录。');
  const positional=process.argv.slice(2).filter(arg=>!arg.startsWith('--'));
  const origin=new URL(process.env.RAG_EVAL_URL||'http://localhost:18081'),token=process.env.RAG_EVAL_TOKEN;
  if(!['http:','https:'].includes(origin.protocol)||!token||!positional[0]||!positional[1])throw new Error('需要题集/输出路径和RAG_EVAL_TOKEN、RAG_EVAL_KNOWLEDGE_BASE_ID');
  const input=JSON.parse(readFileSync(positional[0],'utf8'));
  const options={};
  for(const [key,env] of Object.entries({topK:'RAG_EVAL_TOP_K',minScore:'RAG_EVAL_MIN_SCORE',recallBudget:'RAG_EVAL_RECALL_BUDGET',candidateLimit:'RAG_EVAL_CANDIDATE_LIMIT'}))
    if(process.env[env]!==undefined)options[key]=Number(process.env[env]);
  const request=(path,options)=>fetch(new URL(path,origin),{...options,redirect:'error',signal:AbortSignal.timeout(90000),headers:{...options.headers,Authorization:'Bearer '+token}});
  const report=await compareRetrieval({request,knowledgeBaseId:Number(process.env.RAG_EVAL_KNOWLEDGE_BASE_ID),cases:input.cases,options,
    includeAnswers:process.argv.includes('--with-answers'),label:input.label||'manual'});
  writeFileSync(positional[1],JSON.stringify(report,null,2));
  console.log(JSON.stringify({report:resolve(positional[1]),metrics:report.metrics}));
}
