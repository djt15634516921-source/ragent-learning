// 由主验证脚本传入独立容器、账号和目录，不访问现有学习数据。
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {writeFileSync} from 'node:fs';
import {join} from 'node:path';

export async function verifyChunks({a, b, anonymous, kb, secondKb, sql, container, storage, until, passed}) {
  const source = Array.from({length: 3500}, (_, i) =>
    `第${i}段：Java 文档切分保留顺序和来源。中文🙂与单引号 ' 应当原样保存，后续再处理检索。`).join('\n\n');
  const uploaded = await a.upload(kb.id, 'chunk-source.md', source);
  assert.equal(uploaded.status, 201);
  const document = await uploaded.json();
  const path = `/api/knowledge-bases/${kb.id}/documents/${document.id}`;
  const chunk = (options = {}) => a.json(path + '/chunks', options);
  const page = async (number = 1, size = 100) => {
    const response = await a.request(path + `/chunks?page=${number}&size=${size}`);
    assert.equal(response.status, 200, await response.clone().text());
    return response.json();
  };
  assert.equal((await anonymous.request(path + '/chunks')).status, 401);
  assert.equal((await anonymous.json(path + '/chunks', {})).status, 401);
  assert.equal((await chunk()).status, 409);
  assert.equal((await page()).chunkStatus, 'PENDING');
  assert.equal((await b.request(path + '/chunks')).status, 404);
  assert.equal((await b.json(path + '/chunks', {})).status, 404);
  assert.equal((await a.request(`/api/knowledge-bases/${secondKb.id}/documents/${document.id}/chunks`)).status, 404);
  assert.equal((await a.post(path + '/parse', '')).status, 200);
  for (const options of [{chunkSize: 199}, {chunkSize: 4001}, {overlap: -1}, {chunkSize: 200, overlap: 101}])
    assert.equal((await chunk(options)).status, 400);
  assert.equal((await a.request(path + '/chunks?page=0')).status, 400);
  assert.equal((await a.request(path + '/chunks?size=101')).status, 400);
  passed('切分前置解析、登录/跨用户/跨库隔离、长度重叠与分页参数校验');

  const created = await chunk();
  assert.equal(created.status, 200, await created.clone().text());
  const first = await created.json();
  assert.equal(first.chunkStatus, 'SUCCESS');
  assert.equal(first.chunkSize, 800); assert.equal(first.overlap, 120);
  assert.equal(first.filename, 'chunk-source.md');
  assert.ok(first.total > 20);
  assert.equal(first.chunks.length, 20);
  assert.equal(first.chunks[0].chunkIndex, 0);
  assert.ok(first.chunkedAt);
  const points = [...source], covered = new Uint8Array(points.length);
  const all = [];
  for (let i = 1; i <= Math.ceil(first.total / 100); i++) all.push(...(await page(i)).chunks);
  assert.equal(all.length, first.total);
  for (let i = 0; i < all.length; i++) {
    const row = all[i];
    assert.equal(row.chunkIndex, i); assert.equal(row.documentId, document.id);
    assert.equal(row.characterCount, [...row.content].length);
    assert.equal(row.characterCount, row.endOffset - row.startOffset);
    assert.ok(row.characterCount <= 800);
    assert.equal(row.content, points.slice(row.startOffset, row.endOffset).join(''));
    if (i > 0) assert.equal(all[i - 1].endOffset - row.startOffset, 120);
    for (let at = row.startOffset; at < row.endOffset; at++) covered[at] = 1;
  }
  for (let at = 0; at < points.length; at++) if (!/\s/u.test(points[at])) assert.equal(covered[at], 1);
  assert.equal(sql(`SELECT count(*) FROM knowledge_chunk WHERE document_id=${document.id}`), String(first.total));
  assert.equal((await page(99999)).chunks.length, 0);
  assert.equal((await (await a.request(path + '/text')).json()).text, source);
  const listDocument = (await (await a.request(`/api/knowledge-bases/${kb.id}/documents`)).json()).find(r => r.id === document.id);
  assert.equal(listDocument.status, 'SUCCESS');
  assert.equal(listDocument.chunkCount, first.total);
  passed('默认切分、真实分页与落库：顺序、编号、Unicode/Emoji、重叠、原文位置及完整覆盖');

  const ids = () => sql(`SELECT string_agg(id::text, ',' ORDER BY chunk_index) FROM knowledge_chunk WHERE document_id=${document.id}`);
  const before = ids();
  assert.equal((await chunk()).status, 200);
  assert.equal(ids(), before);
  assert.equal((await b.request(path + '/chunks')).status, 404);
  assert.equal((await b.json(path + '/chunks', {})).status, 404);
  passed('同一原文与相同参数重复切分幂等，片段编号稳定，无重复记录或越权读取');

  // 第二个写入批次故障：证明第一批、DELETE 和文档统计也一起回滚。
  sql(`ALTER TABLE knowledge_chunk ADD CONSTRAINT test_chunk_reject CHECK (document_id <> ${document.id} OR chunk_index <> 250) NOT VALID`);
  try {
    const failed = await chunk({chunkSize: 200, overlap: 0});
    assert.equal(failed.status, 503, await failed.clone().text());
    assert.equal((await failed.json()).code, 'DOCUMENT_CHUNK_DATABASE_FAILED');
    assert.equal(ids(), before);
    assert.equal((await page()).chunkSize, 800);
    assert.equal((await page()).total, first.total);
  } finally { sql('ALTER TABLE knowledge_chunk DROP CONSTRAINT test_chunk_reject'); }
  assert.equal((await chunk({chunkSize: 200, overlap: 0})).status, 200);
  assert.ok((await page()).total > first.total);
  passed('切分跨批次写入失败整批回滚，旧片段/统计保留，解除故障后可替换');

  const concurrent = await Promise.all([chunk({chunkSize: 400, overlap: 80}), chunk({chunkSize: 600, overlap: 100})]);
  for (const response of concurrent) assert.equal(response.status, 200, await response.clone().text());
  const current = await page();
  assert.equal(sql(`SELECT count(*) FROM knowledge_chunk WHERE document_id=${document.id}`), String(current.total));
  assert.equal(sql(`SELECT count(DISTINCT chunk_index) FROM knowledge_chunk WHERE document_id=${document.id}`), String(current.total));
  passed('并发切分由文档行锁协调，最终片段与参数统计一致');

  assert.equal((await a.post(path + '/parse', '')).status, 200);
  assert.equal((await page()).chunkStatus, 'PENDING');
  assert.equal((await page()).total, 0);
  assert.ok((await page()).sourceVersion > current.sourceVersion);
  assert.equal((await chunk()).status, 200);
  const key = sql(`SELECT storage_key FROM knowledge_document WHERE id=${document.id}`);
  writeFileSync(join(storage, key), Buffer.from([0xff]));
  try {
    assert.equal((await a.post(path + '/parse', '')).status, 422);
    assert.equal((await page()).chunks.length, 0);
    assert.equal((await chunk()).status, 409);
    assert.ok((await (await a.request(path + '/text')).json()).errorMessage);
  } finally { writeFileSync(join(storage, key), source); }
  assert.equal((await a.post(path + '/parse', '')).status, 200);
  assert.equal((await chunk()).status, 200);
  passed('重新解析使旧片段失效，解析失败原因保留，恢复正文后可重新切分');

  // 让读取原文看到旧提交版本，保存行锁等待另一个事务提交新版本。
  const task = spawn('docker', ['exec', container, 'psql', '-U', 'postgres', '-d', 'knowledge_test', '-v', 'ON_ERROR_STOP=1', '-c',
    `SET application_name='chunk-version-fixture'; BEGIN; UPDATE knowledge_document SET parse_version=parse_version+1,
     chunk_version=NULL, chunk_count=0, chunk_size=NULL, chunk_overlap=NULL, chunked_at=NULL WHERE id=${document.id};
     SELECT pg_sleep(4); COMMIT;`], {windowsHide: true, stdio: ['ignore', 'ignore', 'pipe']});
  let taskError = ''; task.stderr.on('data', data => taskError += data.toString());
  const taskDone = new Promise((resolve, reject) => {
    task.once('error', reject); task.once('exit', code => code === 0 ? resolve() : reject(new Error(taskError)));
  });
  await until(() => sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='chunk-version-fixture' AND wait_event='PgSleep'") === '1', '版本变更事务持有文档行锁');
  const stale = await chunk({chunkSize: 500, overlap: 50});
  await taskDone;
  assert.equal(stale.status, 409, await stale.clone().text());
  assert.equal((await stale.json()).code, 'DOCUMENT_CHANGED');
  assert.equal((await page()).chunks.length, 0);
  assert.equal((await chunk()).status, 200);
  passed('切分期间原文版本变化时拒绝旧结果，不覆盖新版本');

  return {documentId: document.id, expectedTotal: (await page()).total};
}
