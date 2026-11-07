// 文档级检索评测；不调用回答模型，也不把文件命中率称作答案正确率。
import {readFileSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';

export async function evaluateRetrieval({request, knowledgeBaseId, cases, topK = 4, minScore = 0.35, recallBudget, candidateLimit, rerank, label = 'manual'}) {
  if (!Number.isSafeInteger(knowledgeBaseId) || knowledgeBaseId <= 0 || !Array.isArray(cases) || !cases.length) throw new Error('请提供有效知识库编号和非空题集');
  if (!Number.isSafeInteger(topK) || topK<1 || topK>20 || !Number.isFinite(minScore) || minScore < -1 || minScore > 1) throw new Error('Top-K须为1～20的整数，最低分须为-1～1的有限数值');
  const settingsResponse=await request(`/api/knowledge-bases/${knowledgeBaseId}/search/settings`,{method:'GET'});
  if(!settingsResponse.ok) throw new Error('无法读取知识库检索设置：HTTP '+settingsResponse.status);
  const backendSettings=await settingsResponse.json();
  let scored = 0, hit = 0, recall = 0, reciprocal = 0;
  const results = [];
  for (const item of cases) {
    if (typeof item.question !== 'string' || !item.question.trim() || !Array.isArray(item.expectedFiles)
        || item.expectedFiles.some(file => typeof file !== 'string' || !file.trim())) throw new Error('题目须包含question和有效文件名数组expectedFiles');
    const started = performance.now();
    const response = await request(`/api/knowledge-bases/${knowledgeBaseId}/search/detail`, {
      method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({question:item.question,topK,minScore,recallBudget,candidateLimit,rerank})
    });
    const payload = await response.json();
    if (!response.ok) throw new Error(`题目${item.id}检索失败：HTTP ${response.status} ${payload.code}`);
    const data = payload.sources;
    const expected = [...new Set(item.expectedFiles)];
    const found = [...new Set(data.map(row => row.filename))];
    const matched = expected.filter(file => found.includes(file));
    const first = data.findIndex(row => expected.includes(row.filename));
    if (expected.length) {
      scored++; hit += matched.length ? 1 : 0; recall += matched.length / expected.length;
      reciprocal += first < 0 ? 0 : 1 / (first + 1);
    }
    results.push({id:item.id, question:item.question, expectedFiles:expected, matchedFiles:matched,
      latencyMs:Math.round(performance.now()-started), requiresHumanReview:!expected.length,retrieval:payload.retrieval,
      retrieved:data.map(row => ({chunkId:row.chunkId,documentId:row.documentId,filename:row.filename,chunkIndex:row.chunkIndex,score:row.score,rerankScore:row.rerankScore,sourceVersion:row.sourceVersion}))});
  }
  return {createdAt:new Date().toISOString(),label,knowledgeBaseId,configuration:{topK,minScore,recallBudget,candidateLimit,rerank,backendSettings},
    datasetSha256:createHash('sha256').update(JSON.stringify(cases)).digest('hex'),
    metrics:{totalCases:cases.length,scoredCases:scored,unscoredCases:cases.length-scored,
      documentHitRateAtK:scored ? hit/scored : null,documentRecallAtK:scored ? recall/scored : null,
      meanReciprocalRank:scored ? reciprocal/scored : null,
      averageLatencyMs:results.reduce((sum,row) => sum+row.latencyMs,0)/results.length},
    limitations:'按预期文件名评分，不验证命中片段是否含完整证据，不评价答案忠实性或正确率。未标注相关文件的题目须人工检查。',results};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (!process.argv.includes('--allow-model-calls')) throw new Error('检索会调用Embedding服务。确认后使用 --allow-model-calls；凭证只通过环境变量提供。');
  const positional = process.argv.slice(2).filter(arg => arg !== '--allow-model-calls');
  const origin = new URL(process.env.RAG_EVAL_URL || 'http://localhost:18081');
  if (origin.protocol !== 'http:' && origin.protocol !== 'https:') throw new Error('无效服务地址');
  const token = process.env.RAG_EVAL_TOKEN;
  if (!token || !positional[0] || !positional[1]) throw new Error('需要题集和输出文件，以及RAG_EVAL_TOKEN/RAG_EVAL_KNOWLEDGE_BASE_ID环境变量');
  const input = JSON.parse(readFileSync(positional[0],'utf8'));
  const request = (path,options) => fetch(new URL(path,origin), {...options,redirect:'error',signal:AbortSignal.timeout(90000),
    headers:{...options.headers,Authorization:'Bearer '+token}});
  const report = await evaluateRetrieval({request,knowledgeBaseId:Number(process.env.RAG_EVAL_KNOWLEDGE_BASE_ID),
    cases:input.cases,topK:Number(process.env.RAG_EVAL_TOP_K || 4),minScore:Number(process.env.RAG_EVAL_MIN_SCORE || 0.35),label:input.label || 'manual'});
  writeFileSync(positional[1],JSON.stringify(report,null,2));
  console.log(JSON.stringify({report:resolve(positional[1]),metrics:report.metrics}));
}
