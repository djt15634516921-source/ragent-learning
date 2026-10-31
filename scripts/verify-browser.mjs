// 可选的真实 Edge 回归，使用主验证脚本提供的隔离服务与本机假模型。
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';

export async function verifyBrowser({root, origin, work, passed}) {
  const require = createRequire(import.meta.url);
  const {chromium} = require(join(root, 'target/browser-check/node_modules/playwright-core'));
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const context = await browser.newContext({acceptDownloads: true});
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(origin + '/login-test.html');
    await page.locator('#username').fill('page_user');
    await page.locator('#password').fill('local-test-123');
    await page.locator('#register').click();
    await page.waitForFunction(() => document.querySelector('#auth-status').textContent.includes('已创建'));
    await page.locator('#login').click();
    await page.waitForFunction(() => document.querySelector('#auth-status').textContent.includes('已登录'));
    assert.ok(await page.evaluate(() => !!sessionStorage.getItem('ragent-learning-token')));
    assert.equal((await context.cookies()).length, 0);
    await page.locator('#mode').selectOption('normal');
    await page.locator('#question').fill('浏览器普通聊天');
    await page.locator('#send').click();
    await page.waitForFunction(() => document.querySelector('#chat-status').textContent.includes('回答已完成并保存'));
    await page.waitForFunction(() => document.querySelector('#history').textContent.includes('浏览器普通聊天'));
    assert.match(await page.locator('#history').textContent(), /浏览器普通聊天/);
    await page.locator('#mode').selectOption('stream');
    await page.locator('#question').fill('浏览器流式聊天');
    await page.locator('#send').click();
    await page.waitForFunction(() => document.querySelector('#chat-status').textContent.includes('回答已完成并保存'));
    await page.waitForFunction(() => document.querySelector('#history').textContent.includes('浏览器流式聊天'));
    await page.waitForFunction(() => !document.querySelector('#chat-fields').disabled);
    await page.goto(origin + '/stream-test.html');
    await page.locator('#conversation-id').fill('browser-stream');
    await page.locator('#question').fill('独立流式页面');
    await page.locator('#send').click();
    await page.waitForFunction(() => document.querySelector('#status').textContent.includes('已完成并保存本轮'));
    passed('真实 Edge：注册、登录、sessionStorage、普通聊天和两个 SSE 页面');

    await page.goto(origin + '/knowledge-test.html');
    await page.waitForFunction(() => document.querySelector('#identity').dataset.authenticated === 'true');
    await page.locator('#name').fill('浏览器知识库');
    await page.locator('#description').fill('验证新的请求认证协议');
    await page.locator('#create-form button').click();
    await page.waitForFunction(() => document.querySelector('#status').textContent.includes('已创建知识库'));
    await page.locator('#file').setInputFiles({name: 'browser-note.md', mimeType: 'text/markdown',
      buffer: Buffer.from('# 浏览器验证\n朱之文01，原文解析与下载。')});
    await page.locator('#upload-form button').click();
    await page.waitForFunction(() => document.querySelector('#status').textContent.includes('已上传'));
    await page.getByRole('button', {name: '解析文本', exact: true}).click();
    await page.waitForFunction(() => document.querySelector('#status').textContent.includes('解析完成'));
    assert.match(await page.locator('#parsed-text').textContent(), /朱之文01/);
    const downloaded = page.waitForEvent('download');
    await page.getByRole('link', {name: 'browser-note.md', exact: true}).click();
    const file = await downloaded;
    assert.equal(file.suggestedFilename(), 'browser-note.md');
    assert.match(readFileSync(await file.path(), 'utf8'), /朱之文01/);
    assert.ok(!file.url().includes('Bearer'));
    await page.reload();
    await page.getByRole('button', {name: '查看文本', exact: true}).click();
    await page.waitForFunction(() => document.querySelector('#parsed-text').textContent.includes('朱之文01'));
    await page.screenshot({path: join(work, 'knowledge-page.png'), fullPage: true});
    await page.goto(origin + '/login-test.html');
    await page.waitForFunction(() => document.querySelector('#auth-status').textContent.includes('已登录'));
    await page.locator('#logout').click();
    await page.waitForFunction(() => document.querySelector('#chat-status').textContent.includes('已退出'));
    assert.ok(await page.evaluate(() => !sessionStorage.getItem('ragent-learning-token')));
    await page.goto(origin + '/knowledge-test.html');
    await page.waitForFunction(() => document.querySelector('#identity').dataset.authenticated === 'false');
    assert.deepEqual(errors, []);
    passed('真实 Edge：上传、解析、读取文本、携带请求头下载、刷新和退出清理');
  } finally { await browser.close(); }
}
