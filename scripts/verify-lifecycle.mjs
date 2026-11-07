// 仅由隔离回归主脚本调用；故障注入、文件移动和删除都限制在该次测试目录。
import assert from 'node:assert/strict';
import {mkdirSync, renameSync, writeFileSync, unlinkSync, rmdirSync, existsSync} from 'node:fs';
import {resolve, sep, join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {embeddingState} from './mock-embedding.mjs';
import {evaluateRetrieval} from './evaluate-retrieval.mjs';

export async function verifyLifecycle({client,sql,storage,work,until,passed,stopApplication,startApplication,modelCalls}) {
  const owner = client(), other = client();
  await owner.register('lifecycle_owner'); await other.register('lifecycle_other');
  const base = await (await owner.json('/api/knowledge-bases',{name:'闭环验收资料'})).json();
  const basePath = `/api/knowledge-bases/${base.id}`;
  const docPath = id => `${basePath}/documents/${id}`;
  const remove = (client,path) => client.request(path,{method:'DELETE'});
  const status = async id => {
    const response = await owner.request(`${basePath}/ingestion-tasks/${id}`);
    assert.equal(response.status,200,await response.clone().text()); return response.json();
  };
  const upload = async (filename,data,async = true,options = '') => {
    const form = new FormData(); form.append('file',new Blob([data]),filename);
    const response = await owner.post(`${basePath}/documents/ingest?async=${async}${options}`,form);
    assert.ok([200,202].includes(response.status),await response.clone().text()); return response.json();
  };
  const success = async id => {
    await until(async () => {
      const task = await status(id);
      if (task.status === 'FAILED') throw new Error(`任务${id}失败：${task.errorCode} ${task.errorMessage}`);
      return task.status === 'SUCCESS';
    }, '后台入库完成'); return status(id);
  };
  const safeFile = key => {
    const file = resolve(storage,key), root = resolve(storage)+sep;
    assert.ok(file.startsWith(root),'测试文件必须位于独立存储目录内'); return file;
  };
  assert.equal((await remove(client(),basePath)).status,401);
  assert.equal((await remove(other,basePath)).status,404);
  const invalid = new FormData(); invalid.append('file',new Blob(['bad options']),'bad-options.md');
  const beforeInvalid = sql(`SELECT count(*) FROM knowledge_document WHERE knowledge_base_id=${base.id}`);
  assert.equal((await owner.post(`${basePath}/documents/ingest?chunkSize=100`,invalid)).status,400);
  assert.equal(sql(`SELECT count(*) FROM knowledge_document WHERE knowledge_base_id=${base.id}`),beforeInvalid);
  passed('闭环：删除认证/归属、非法入库参数在文件保存前拒绝');

  const sync = await upload('lifecycle-sync.md','闭环资料：星河项目验收口令是海棠7429。',false);
  assert.equal(sync.status,'SUCCESS'); assert.equal(sync.stage,'COMPLETED');
  assert.equal(sync.runToken,undefined); assert.equal(sync.userId,undefined);
  assert.equal((await owner.request(docPath(sync.documentId)+'/file')).status,200);
  assert.equal((await (await owner.request(docPath(sync.documentId)+'/index')).json()).status,'SUCCESS');
  assert.ok(Number(sql(`SELECT count(*) FROM knowledge_chunk_vector v JOIN knowledge_chunk c ON c.id=v.chunk_id WHERE c.document_id=${sync.documentId}`))>0);
  passed('闭环：同步一键上传、解析、切分、真实pgvector入库，任务VO不暴露执行令牌');

  sql("ALTER TABLE document_ingestion_task ADD CONSTRAINT lifecycle_reject_submission CHECK(chunk_size<>777) NOT VALID");
  try {
    const form = new FormData(); form.append('file',new Blob(['任务创建失败时保留已上传原文件']),'submission-failure.md');
    const response=await owner.post(`${basePath}/documents/ingest?chunkSize=777`,form);
    assert.equal(response.status,503); assert.match((await response.json()).message,/文档编号/);
    assert.equal(sql(`SELECT count(*) FROM knowledge_document WHERE knowledge_base_id=${base.id} AND original_filename='submission-failure.md'`),'1');
  } finally { sql('ALTER TABLE document_ingestion_task DROP CONSTRAINT lifecycle_reject_submission'); }
  const submittedDoc=Number(sql(`SELECT id FROM knowledge_document WHERE knowledge_base_id=${base.id} AND original_filename='submission-failure.md'`));
  const submitted=await (await owner.json(docPath(submittedDoc)+'/ingest',{})).json();
  await success(submitted.taskId);
  passed('闭环：任务创建故障明确返回上传文档编号，原文件保留，解除故障后无需重复上传');

  const failedParse = await upload('lifecycle-invalid.txt',Buffer.from([0xff]),false);
  assert.equal(failedParse.status,'FAILED'); assert.equal(failedParse.stage,'PARSING');
  assert.equal(failedParse.errorCode,'DOCUMENT_ENCODING_INVALID');
  const parseKey = sql(`SELECT storage_key FROM knowledge_document WHERE id=${failedParse.documentId}`);
  writeFileSync(safeFile(parseKey),'修复后的有效资料。');
  assert.equal((await owner.post(`${basePath}/ingestion-tasks/${failedParse.taskId}/retry`,'')).status,202);
  assert.equal((await success(failedParse.taskId)).attempt,2);
  passed('闭环：解析失败保留文件和原因，修复后重试同一任务并记录次数');

  const retryDoc = await (await owner.upload(base.id,'lifecycle-retry.md','向量化失败时保留已解析和切分的资料。')).json();
  assert.equal((await owner.post(docPath(retryDoc.id)+'/parse','')).status,200);
  assert.equal((await owner.json(docPath(retryDoc.id)+'/chunks',{})).status,200);
  const versions = sql(`SELECT parse_version||':'||chunk_revision FROM knowledge_document WHERE id=${retryDoc.id}`);
  embeddingState.failNext = true;
  const failedIndex = await (await owner.json(docPath(retryDoc.id)+'/ingest',{})).json();
  await until(async () => (await status(failedIndex.taskId)).status === 'FAILED','向量化失败记录');
  const failure = await status(failedIndex.taskId);
  assert.equal(failure.stage,'INDEXING'); assert.match(failure.errorCode,/EMBEDDING/);
  assert.equal(sql(`SELECT parse_version||':'||chunk_revision FROM knowledge_document WHERE id=${retryDoc.id}`),versions);
  assert.equal((await other.request(`${basePath}/ingestion-tasks/${failure.taskId}`)).status,404);
  assert.equal((await other.post(`${basePath}/ingestion-tasks/${failure.taskId}/retry`,'')).status,404);
  assert.equal((await other.request(`${basePath}/ingestion-tasks`)).status,404);
  assert.equal((await owner.post(`${basePath}/ingestion-tasks/${failure.taskId}/retry`,'')).status,202);
  const retried = await success(failure.taskId);
  assert.equal(retried.attempt,2);
  assert.equal(sql(`SELECT parse_version||':'||chunk_revision FROM knowledge_document WHERE id=${retryDoc.id}`),versions);
  assert.equal((await owner.post(`${basePath}/ingestion-tasks/${failure.taskId}/retry`,'')).status,409);
  passed('闭环：向量化失败定位阶段、归属隔离、重试复用有效正文和片段，成功任务拒绝重复重试');

  const staleDoc=await (await owner.upload(base.id,'stale-parse.md','中断解析可恢复，旧令牌不能覆盖新结果。')).json();
  const staleId=randomUUID();
  sql(`UPDATE knowledge_document SET status='RUNNING',parse_token='old-parse-token',parse_started_at=CURRENT_TIMESTAMP WHERE id=${staleDoc.id}`);
  sql(`INSERT INTO document_ingestion_task(id,document_id,user_id,knowledge_base_id,status,stage,chunk_size,overlap,run_token,lease_until)
    SELECT '${staleId}',${staleDoc.id},user_id,id,'RUNNING','PARSING',800,120,'old-run-token',CURRENT_TIMESTAMP-INTERVAL '1 second'
    FROM knowledge_base WHERE id=${base.id}`);
  await until(async () => (await status(staleId)).status==='FAILED','解析中断任务失效');
  assert.equal(sql(`SELECT status||':'||COALESCE(parse_token,'NULL') FROM knowledge_document WHERE id=${staleDoc.id}`),'FAILED:NULL');
  assert.equal(sql(`UPDATE knowledge_document SET parsed_text='不得发布的旧结果' WHERE id=${staleDoc.id} AND parse_token='old-parse-token'`),'UPDATE 0');
  assert.equal((await owner.post(`${basePath}/ingestion-tasks/${staleId}/retry`,'')).status,202);
  await success(staleId);
  assert.equal((await (await owner.request(docPath(staleDoc.id)+'/text')).json()).text,'中断解析可恢复，旧令牌不能覆盖新结果。');
  passed('闭环：解析阶段租约过期废弃旧令牌，迟到写入被拒绝，手动重试重新完成');

  let held;
  embeddingState.hold = true;
  try {
    held = await upload('lifecycle-held.md','INDEX_SLOW 任务执行中校验重复提交及删除并发。');
    await until(async () => (await status(held.taskId)).stage === 'INDEXING' && !!embeddingState.release,'向量化已等待假服务');
    const duplicate = await owner.json(docPath(held.documentId)+'/ingest',{});
    assert.equal(duplicate.status,202); assert.equal((await duplicate.json()).taskId,held.taskId);
    assert.equal((await owner.json(docPath(held.documentId)+'/ingest',{chunkSize:600,overlap:100})).status,409);
    assert.equal((await owner.post(docPath(held.documentId)+'/parse','')).status,409);
    assert.equal((await owner.json(docPath(held.documentId)+'/chunks',{})).status,409);
    assert.equal((await owner.post(docPath(held.documentId)+'/index','')).status,409);
    await delay(3400);
    assert.equal((await status(held.taskId)).status,'RUNNING','长模型等待期间心跳继续续约');
    assert.equal((await remove(other,docPath(held.documentId))).status,404);
    const deleted = await remove(owner,docPath(held.documentId));
    assert.equal(deleted.status,200,await deleted.clone().text());
    assert.equal((await deleted.json()).pendingFileCleanups,0);
  } finally { embeddingState.hold = false; embeddingState.release?.(); embeddingState.release = null; }
  await delay(250);
  assert.equal(sql(`SELECT count(*) FROM knowledge_document WHERE id=${held.documentId}`),'0');
  assert.equal((await owner.request(`${basePath}/ingestion-tasks/${held.taskId}`)).status,404);
  assert.equal((await owner.request(docPath(held.documentId)+'/file')).status,404);
  passed('闭环：后台阶段可查询、心跳续约、重复任务复用、独立接口冲突保护，执行中删除不复活文档');

  const settings = await (await owner.request(`${basePath}/search/settings`)).json();
  assert.equal(settings.chunkSize,800); assert.equal(settings.overlap,120); assert.equal(settings.topK,4);
  assert.equal(settings.embeddingDimensions,1024); assert.equal(typeof settings.embeddingModel,'string'); assert.match(settings.embeddingProfile,/^[a-f0-9]{64}$/);
  const small = await owner.json(`${basePath}/search`,{question:'验收口令',topK:1,minScore:-1});
  assert.equal(small.status,200); assert.equal((await small.json()).length,1);
  assert.equal((await owner.json(`${basePath}/search`,{question:'验收口令',topK:21})).status,400);
  assert.equal((await owner.json(`${basePath}/search`,{question:'验收口令',minScore:1.1})).status,400);
  assert.equal((await other.request(`${basePath}/search/settings`)).status,404);
  const evaluation = await evaluateRetrieval({request:owner.request,knowledgeBaseId:base.id,topK:4,minScore:0.35,label:'isolated-mock-regression',
    cases:[{id:'local-evidence',question:'验收口令是什么',expectedFiles:['lifecycle-sync.md']},{id:'missing-phone',question:'林老师手机号',expectedFiles:[]}]});
  assert.equal(evaluation.metrics.scoredCases,1); assert.equal(evaluation.metrics.unscoredCases,1);
  assert.equal(evaluation.metrics.documentHitRateAtK,1);
  assert.equal(evaluation.configuration.backendSettings.embeddingProfile,settings.embeddingProfile);
  writeFileSync(join(work,'retrieval-evaluation.json'),JSON.stringify(evaluation,null,2));
  passed('闭环：Top-K/阈值调试、默认参数读取、文档级评测报告与未标注题人工复核标识');

  // 文件清理失败：把本次上传的原文件临时替换成非空目录，仅影响本次隔离目录。
  const cleanupDoc = await (await owner.upload(base.id,'cleanup-failure.md','用于原文件清理失败回归')).json();
  const cleanupKey = sql(`SELECT storage_key FROM knowledge_document WHERE id=${cleanupDoc.id}`);
  const cleanupFile = safeFile(cleanupKey), savedFile = safeFile(cleanupKey+'.test-backup');
  renameSync(cleanupFile,savedFile); mkdirSync(cleanupFile); writeFileSync(join(cleanupFile,'test-marker'),'test');
  try {
    const response = await remove(owner,docPath(cleanupDoc.id));
    assert.equal(response.status,200); assert.equal((await response.json()).pendingFileCleanups,1);
    assert.equal((await owner.request(docPath(cleanupDoc.id)+'/file')).status,404);
    assert.equal(sql(`SELECT count(*) FROM file_cleanup WHERE storage_key='${cleanupKey}'`),'1');
  } finally {
    unlinkSync(join(cleanupFile,'test-marker')); rmdirSync(cleanupFile); renameSync(savedFile,cleanupFile);
  }
  sql(`UPDATE file_cleanup SET next_attempt_at=CURRENT_TIMESTAMP WHERE storage_key='${cleanupKey}'`);
  await until(() => sql(`SELECT count(*) FROM file_cleanup WHERE storage_key='${cleanupKey}'`) === '0','原文件清理重试');
  assert.equal(existsSync(cleanupFile),false);
  passed('闭环：数据库删除与文件清理解耦，清理失败持久化、后台重试且不恢复资源可见性');

  const cid = 'lifecycle-history-'+randomUUID();
  for(let i=0;i<13;i++) assert.equal((await owner.json('/api/chat',{conversationId:cid,question:'历史分页-'+i})).status,200);
  const pagePath = '/api/conversations/'+cid+'/messages/page';
  const page1 = await (await owner.request(pagePath+'?size=5')).json();
  assert.equal(page1.messages.length,5); assert.equal(page1.hasMore,true);
  assert.deepEqual(page1.messages.map(m=>m.id),page1.messages.map(m=>m.id).sort((a,b)=>a-b));
  assert.equal((await owner.json('/api/chat',{conversationId:cid,question:'翻页期间新增'})).status,200);
  const page2 = await (await owner.request(pagePath+'?size=5&beforeId='+page1.nextBeforeId)).json();
  assert.ok(page2.messages.every(m=>m.id<page1.nextBeforeId));
  assert.equal(new Set([...page1.messages,...page2.messages].map(m=>m.id)).size,10);
  let cursor = page2.nextBeforeId, gathered = [...page2.messages,...page1.messages];
  while(cursor) { const page = await (await owner.request(pagePath+'?size=5&beforeId='+cursor)).json(); gathered = [...page.messages,...gathered]; cursor=page.nextBeforeId; }
  assert.equal(gathered.length,26,'翻页固定在首个游标之前，不重复新增的两条消息');
  assert.equal((await (await owner.request('/api/conversations/'+cid+'/messages')).json()).length,12);
  assert.ok(modelCalls.at(-1).messages.length<=14,'页面分页不扩大模型历史窗口');
  assert.equal((await other.request(pagePath)).status,404);
  assert.equal((await owner.request(pagePath+'?size=101')).status,400);
  assert.equal((await owner.request(pagePath+'?beforeId=0')).status,400);
  assert.equal((await remove(other,'/api/conversations/'+cid)).status,404);
  assert.equal((await remove(owner,'/api/conversations/'+cid)).status,200);
  assert.equal(sql(`SELECT count(*) FROM chat_message WHERE conversation_id='${cid}'`),'0');
  assert.equal((await owner.json('/api/chat',{conversationId:cid,question:'不得复活已删除会话'})).status,404);
  assert.equal((await owner.request(pagePath)).status,404);
  passed('闭环：历史游标分页无重复、模型仍保留六轮窗口，会话归属与删除后禁止迟到写入/复活');

  async function openStream(conversationId) {
    const response = await owner.json('/api/chat/stream',{conversationId,question:'cancel-stream'});
    assert.equal(response.status,200); const reader=response.body.getReader(); let text=''; const decoder=new TextDecoder();
    while(!text.includes('event:delta')) { const part=await reader.read(); if(part.done)break; text+=decoder.decode(part.value); }
    const start=text.match(/event:start\s+data:(\{[^\n]+\})/);
    assert.ok(start,text); return {reader,text,streamId:JSON.parse(start[1]).streamId};
  }
  const cancelCid='lifecycle-cancel-'+randomUUID();
  const opened=await openStream(cancelCid);
  const cancelPath=`/api/conversations/${cancelCid}/streams/${opened.streamId}/cancel`;
  assert.equal((await other.post(cancelPath,'')).status,404);
  const cancelled=await owner.post(cancelPath,''); assert.equal(cancelled.status,200); assert.equal((await cancelled.json()).cancelled,true);
  let tail=opened.text;
  while(true) { const part=await opened.reader.read(); if(part.done)break; tail+=new TextDecoder().decode(part.value); }
  assert.doesNotMatch(tail,/event:done/);
  assert.equal(sql(`SELECT count(*) FROM chat_message WHERE conversation_id='${cancelCid}'`),'0');
  const deleteCid='lifecycle-stream-delete-'+randomUUID(); const deleting=await openStream(deleteCid);
  assert.equal((await remove(owner,'/api/conversations/'+deleteCid)).status,200);
  while(!(await deleting.reader.read()).done) { /* 等待取消关闭连接 */ }
  assert.equal(sql(`SELECT count(*) FROM chat_message WHERE conversation_id='${deleteCid}'`),'0');
  passed('闭环：后端显式停止生成、取消归属、流式中删除会话，不保存半截回答');

  // 任务重启恢复：正在执行的任务只标失败，待处理任务可以继续派发。
  embeddingState.hold=true;
  let interrupted;
  try {
    interrupted=await upload('lifecycle-restart.md','INDEX_SLOW 重启中断的向量化需用户明确重试。');
    await until(async () => (await status(interrupted.taskId)).stage==='INDEXING' && !!embeddingState.release,'重启前等待模型');
    await stopApplication();
    const pendingDoc = sql(`INSERT INTO knowledge_document(knowledge_base_id,original_filename,file_type,file_size,storage_key)
      VALUES(${base.id},'restart-pending.md','md',10,'lifecycle/pending-${base.id}.md') RETURNING id`).split('\n')[0];
    const pendingKey=`lifecycle/pending-${base.id}.md`; mkdirSync(resolve(storage,'lifecycle'),{recursive:true}); writeFileSync(safeFile(pendingKey),'重启后继续处理的待排队资料。');
    const pendingId=randomUUID();
    sql(`INSERT INTO document_ingestion_task(id,document_id,user_id,knowledge_base_id,status,stage,chunk_size,overlap)
      SELECT '${pendingId}',${pendingDoc},user_id,id,'PENDING','PENDING',800,120 FROM knowledge_base WHERE id=${base.id}`);
    const oldCalls=embeddingState.calls.filter(c=>c.input.some(t=>t.includes('重启中断的向量化'))).length;
    await startApplication('lifecycle-restart.log');
    assert.equal((await owner.request('/api/auth/me')).status,200);
    await until(async () => (await status(interrupted.taskId)).status==='FAILED','中断任务过期并标记失败');
    assert.equal((await status(interrupted.taskId)).errorCode,'INGESTION_INTERRUPTED');
    assert.equal(embeddingState.calls.filter(c=>c.input.some(t=>t.includes('重启中断的向量化'))).length,oldCalls,'中断任务不自动重放模型调用');
    assert.equal(sql(`SELECT index_status||':'||COALESCE(index_token,'NULL') FROM knowledge_document WHERE id=${interrupted.documentId}`),'FAILED:NULL');
    await success(pendingId);
  } finally { embeddingState.hold=false; embeddingState.release?.(); embeddingState.release=null; }
  assert.equal((await owner.post(`${basePath}/ingestion-tasks/${interrupted.taskId}/retry`,'')).status,202);
  await success(interrupted.taskId);
  passed('闭环：重启后任务与登录持久化、排队任务继续，中断阶段令牌失效且不自动重跑付费调用，手动重试恢复');

  const ragCid='lifecycle-snapshot-'+randomUUID();
  const answer=await owner.json('/api/chat',{conversationId:ragCid,knowledgeBaseId:base.id,question:'星河项目验收口令是什么'});
  assert.equal(answer.status,200); const sources=(await answer.json()).sources;
  const trigger='lifecycle_reject_delete';
  sql(`CREATE FUNCTION ${trigger}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated deletion rollback'; END $$`);
  sql(`CREATE TRIGGER ${trigger} BEFORE DELETE ON knowledge_document FOR EACH ROW WHEN(OLD.id=${retryDoc.id}) EXECUTE FUNCTION ${trigger}()`);
  const beforeDelete=sql(`SELECT count(*) FROM knowledge_document WHERE knowledge_base_id=${base.id}`);
  const vectorsBefore=sql(`SELECT count(*) FROM knowledge_chunk_vector`);
  try {
    assert.equal((await remove(owner,basePath)).status,503);
    assert.equal(sql(`SELECT count(*) FROM knowledge_document WHERE knowledge_base_id=${base.id}`),beforeDelete);
    assert.equal(sql('SELECT count(*) FROM knowledge_chunk_vector'),vectorsBefore);
    assert.equal(sql(`SELECT count(*) FROM knowledge_base WHERE id=${base.id} AND deleted_at IS NULL`),'1');
    assert.equal(sql('SELECT count(*) FROM file_cleanup'),'0');
  } finally { sql(`DROP TRIGGER ${trigger} ON knowledge_document`); sql(`DROP FUNCTION ${trigger}()`); }
  const removed=await remove(owner,basePath); assert.equal(removed.status,200,await removed.clone().text());
  assert.equal((await removed.json()).pendingFileCleanups,0);
  assert.equal(sql(`SELECT count(*) FROM knowledge_document WHERE knowledge_base_id=${base.id}`),'0');
  assert.equal(sql(`SELECT count(*) FROM document_ingestion_task WHERE knowledge_base_id=${base.id}`),'0');
  assert.equal(sql(`SELECT count(*) FROM chat_conversation WHERE conversation_id='${ragCid}' AND knowledge_base_id=${base.id}`),'1');
  assert.deepEqual((await (await owner.request(`/api/conversations/${ragCid}/messages`)).json())[1].sources,sources);
  assert.equal((await owner.json('/api/chat',{conversationId:ragCid,knowledgeBaseId:base.id,question:'删除后不再检索'})).status,404);
  assert.equal((await owner.json('/api/chat',{conversationId:ragCid,question:'不得自动转换聊天模式'})).status,409);
  assert.equal((await owner.request(`${basePath}/documents`)).status,404);
  assert.equal((await owner.request(`${basePath}/ingestion-tasks`)).status,404);
  assert.equal((await (await owner.request('/api/knowledge-bases')).json()).some(k=>k.id===base.id),false);
  passed('闭环：真实删除约束故障整批回滚，恢复后级联清理；知识库删除保留会话绑定和来源快照，禁止继续检索/转换模式');
}
