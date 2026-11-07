import assert from 'node:assert/strict';
import {join} from 'node:path';

export async function verifyLifecycleBrowser({page,origin,work,passed}) {
  const originalRagCid=await page.locator('#conversation-id').inputValue();
  await page.locator('#chat-knowledge-base').selectOption('');
  await page.locator('#mode').selectOption('stream');
  await page.locator('#question').fill('cancel-stream');
  await page.locator('#send').click();
  await page.waitForFunction(() => !document.querySelector('#stop-generation').disabled && document.querySelector('#answer').textContent.length>0);
  await page.locator('#stop-generation').click();
  await page.waitForFunction(() => document.querySelector('#chat-status').textContent.includes('已停止生成'));
  await page.waitForFunction(() => !document.querySelector('#chat-fields').disabled);
  const cancelledCid=await page.locator('#conversation-id').inputValue();
  assert.equal(await page.evaluate(async cid => (await (await RagentAuth.fetch('/api/conversations/'+cid+'/messages')).json()).length,cancelledCid),0);
  passed('真实 Edge：聊天页停止按钮调用后端取消，界面确认停止且半截回答未保存');

  await page.evaluate(async cid => {
    for(let i=0;i<12;i++) {
      const response=await RagentAuth.fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({conversationId:cid,question:'浏览器分页-'+i})});
      if(!response.ok)throw new Error('分页样本建立失败');
    }
  },cancelledCid);
  await page.locator('#load-history').click();
  await page.waitForFunction(() => !document.querySelector('#older-history').disabled);
  assert.doesNotMatch(await page.locator('#history').textContent(),/浏览器分页-0\b/);
  await page.locator('#older-history').click();
  await page.waitForFunction(() => document.querySelector('#history').textContent.includes('浏览器分页-0'));
  assert.equal(await page.locator('#older-history').isDisabled(),true);
  page.once('dialog',dialog=>dialog.accept()); await page.locator('#delete-conversation').click();
  await page.waitForFunction(() => document.querySelector('#chat-status').textContent.includes('会话和消息已删除'));
  assert.notEqual(await page.locator('#conversation-id').inputValue(),cancelledCid);
  passed('真实 Edge：加载更早历史、游标到头停用、删除会话和页面状态清理');

  await page.goto(origin+'/stream-test.html');
  await page.locator('#conversation-id').fill('browser-lifecycle-stream');
  await page.locator('#question').fill('cancel-stream'); await page.locator('#send').click();
  await page.waitForFunction(() => !document.querySelector('#stop').disabled && document.querySelector('#answer').textContent.length>0);
  await page.locator('#stop').click();
  await page.waitForFunction(() => document.querySelector('#status').textContent.includes('已停止生成'));
  passed('真实 Edge：独立SSE页面停止生成与清理');

  await page.goto(origin+'/knowledge-test.html');
  await page.waitForFunction(() => !document.querySelector('#workspace').disabled);
  await page.locator('#file').setInputFiles({name:'browser-auto.md',mimeType:'text/markdown',buffer:Buffer.from('后台入库测试：知识库自动处理文档并保留来源。')});
  await page.locator('#upload-ingest').click();
  await page.waitForFunction(() => document.querySelector('#status').textContent.includes('后台入库任务'));
  await page.waitForFunction(() => document.querySelector('#ingestion-tasks').textContent.includes('完成'));
  assert.equal(await page.locator('#documents li').filter({has:page.getByRole('link',{name:'browser-auto.md',exact:true})}).count(),1);
  await page.reload();
  await page.waitForFunction(() => document.querySelector('#ingestion-tasks').textContent.includes('完成'));
  await page.locator('#search-question').fill('知识库自动处理文档');
  await page.locator('#search-top-k').fill('1'); await page.locator('#search-run').click();
  await page.waitForFunction(() => document.querySelector('#search-status').textContent.includes('返回 1 个片段'));
  assert.equal(await page.locator('#search-results details').count(),1);
  const row=page.locator('#documents li').filter({has:page.getByRole('link',{name:'browser-auto.md',exact:true})});
  page.once('dialog',dialog=>dialog.accept()); await row.getByRole('button',{name:'删除文档',exact:true}).click();
  await page.waitForFunction(() => document.querySelector('#status').textContent.includes('文档已删除'));
  assert.equal(await page.getByRole('link',{name:'browser-auto.md',exact:true}).count(),0);
  passed('真实 Edge：上传自动入库、阶段轮询、刷新后任务恢复、Top-K检索预览与删除文档');

  await page.locator('#file').setInputFiles({name:'browser-invalid.txt',mimeType:'text/plain',buffer:Buffer.from([0xff])});
  await page.locator('#upload-ingest').click();
  await page.waitForFunction(() => document.querySelector('#ingestion-tasks').textContent.includes('DOCUMENT_ENCODING_INVALID'));
  await page.getByRole('button',{name:'重试入库',exact:true}).click();
  await page.waitForFunction(() => document.querySelector('#ingestion-tasks').textContent.includes('第 2 次执行') && document.querySelector('#ingestion-tasks').textContent.includes('DOCUMENT_ENCODING_INVALID'));
  await page.screenshot({path:join(work,'lifecycle-knowledge-page.png'),fullPage:true});
  page.once('dialog',dialog=>dialog.accept()); await page.locator('#delete-base').click();
  await page.waitForFunction(() => document.querySelector('#status').textContent.includes('知识库已删除'));
  await page.goto(origin+'/login-test.html');
  await page.waitForFunction(() => !document.querySelector('#chat-fields').disabled);
  await page.locator('#saved-conversations').selectOption(originalRagCid);
  await page.waitForFunction(() => document.querySelector('#knowledge-mode-status').textContent.includes('该知识库已删除'));
  assert.equal(await page.locator('#send').isDisabled(),true);
  assert.match(await page.locator('#chat-status').textContent(),/仅可查看历史/);
  await page.waitForFunction(() => document.querySelector('#history').textContent.includes('browser-note.md'));
  await page.screenshot({path:join(work,'lifecycle-chat-page.png'),fullPage:true});
  passed('真实 Edge：失败阶段与重试次数、删除知识库后旧会话保留来源且禁止继续检索');
}
