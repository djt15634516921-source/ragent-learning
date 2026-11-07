import assert from 'node:assert/strict';
import {join} from 'node:path';
import {rerankState} from './mock-rerank.mjs';

export async function verifyRerankBrowser({page,origin,work,passed}){
  const kb=await page.evaluate(async()=>{
    const create=await RagentAuth.fetch('/api/knowledge-bases',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:'浏览器重排验证'})});
    if(!create.ok)throw new Error('创建重排资料失败');const kb=await create.json();
    for(let i=1;i<=8;i++){
      const form=new FormData();form.append('file',new Blob([i===7?'月泉系统工单首次回复时限为两个工作日。':`月泉演示背景资料${i}，仅说明办公安排。`]),i===7?'reply-policy.md':`distractor-${i}.md`);
      const response=await RagentAuth.fetch(`/api/knowledge-bases/${kb.id}/documents/ingest?async=false`,{method:'POST',body:form});
      if(!response.ok||(await response.json()).status!=='SUCCESS')throw new Error('浏览器重排入库失败');
    }return kb;
  });
  await page.goto(origin+'/knowledge-test.html');
  await page.waitForFunction(()=>!document.querySelector('#workspace').disabled);
  await page.locator('#knowledge-base').selectOption(String(kb.id));
  await page.waitForFunction(()=>document.querySelector('#search-settings').textContent.includes('召回 16'));
  assert.equal(await page.locator('#search-recall-budget').inputValue(),'16');
  assert.equal(await page.locator('#search-candidate-limit').inputValue(),'12');
  assert.equal(await page.locator('#search-rerank').isChecked(),true);
  await page.locator('#search-question').fill('月泉系统工单首次回复时限是多少？');
  await page.locator('#search-compare').click();
  await page.waitForFunction(()=>document.querySelector('#search-status').textContent.includes('同一候选集对比'));
  assert.equal(await page.locator('#search-comparison article').count(),2);
  const baseline=page.locator('#search-comparison article').nth(0),reranked=page.locator('#search-comparison article').nth(1);
  assert.doesNotMatch(await baseline.locator('details').first().textContent(),/两个工作日/);
  assert.match(await reranked.locator('details').first().textContent(),/两个工作日/);
  assert.match(await reranked.textContent(),/重排分/);assert.match(await reranked.textContent(),/向量/);
  await page.locator('#search-comparison').screenshot({path:join(work,'rerank-comparison-page.png')});
  rerankState.httpStatus=429;
  await page.locator('#search-run').click();
  await page.waitForFunction(()=>document.querySelector('#search-trace').textContent.includes('RERANK_RATE_LIMITED'));
  assert.match(await page.locator('#search-trace').textContent(),/回退/);
  passed('真实 Edge：三段预算、同候选排序对照、独立向量/重排分与限流回退提示');

  await page.goto(origin+'/login-test.html');
  await page.waitForFunction(()=>!document.querySelector('#chat-fields').disabled);
  await page.locator('#chat-knowledge-base').selectOption(String(kb.id));
  for(const mode of ['normal','stream']){
    await page.locator('#mode').selectOption(mode);await page.locator('#question').fill('月泉系统工单首次回复时限是多少？');
    await page.locator('#send').click();
    await page.waitForFunction(()=>document.querySelector('#chat-status').textContent.includes('回答已完成并保存'));
    await page.waitForFunction(()=>!document.querySelector('#chat-fields').disabled);
    assert.match(await page.locator('#answer').textContent(),/两个工作日/);
    assert.match(await page.locator('#retrieval-trace').textContent(),/重排已应用/);
    assert.match(await page.locator('#sources details').first().textContent(),/重排分/);
  }
  await page.screenshot({path:join(work,'rerank-chat-page.png'),fullPage:true});
  passed('真实 Edge：普通与流式聊天展示重排状态、正确首位证据和两个独立分数');
}
