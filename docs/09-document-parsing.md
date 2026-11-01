# 第 09 步：从原文件提取文本

> 当前进度：已接通知识库检索问答，见 [D11 说明](13-knowledge-rag.md)。下文保留对应阶段的实现边界和验收记录。

> D10 文档切分已接入，查看[切分说明与当前验收步骤](12-document-chunking.md)。

> 当前按[第 11 步](11-maven-modules.md)组织多模块：本章业务 Java 在 rag/src/main/java，配置/SQL/页面在 bootstrap/src/main/resources。历史验收日期与结果保留。

> 2026-10-30：数据库/认证迁移及真实 Edge 回归已完成，共 28 组通过，见第 10 步架构说明与验收记录。文中 2026-10-29 验证小节是历史记录。

> 当前认证与 Mapper/Service 结构已按第 10 步迁移；本章解析语义保持不变。

## 1. 本轮目标

已上传文件 → 校验当前用户与知识库/文档归属 → 标记 RUNNING → 从 FileStorage 读原文件 → 选择解析器 → 提取完整文本 → 保存正文和 SUCCESS；遇到可预期失败则保存 FAILED 和原因。

用户场景：点击文档旁的“解析文本”，能查看这份文件提取出的文字；刷新或重启后端后结果仍在。损坏、加密、无文字和编码错误不能显示为成功。

输入：当前登录身份、知识库编号、文档编号。输出：documentId、status、text、errorMessage、parsedAt、characterCount。

本步 SUCCESS 表示文本解析完成。分块、Embedding、向量索引和资料问答仍属于后续步骤。

## 2. 对应原项目的什么职责

完整项目的关键入口：

- rag/src/main/java/com/nageoffer/ai/ragent/core/parser/DocumentParser.java：统一解析器输入输出。
- 同目录 TikaDocumentParser.java、MarkdownDocumentParser.java：具体类型的解析实现。
- rag/src/main/java/com/nageoffer/ai/ragent/core/ingest/DefaultIngestionKernel.java：入库链中调用解析器。
- rag/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java：文档任务与状态。

原项目提供结构化 Block、专用 Markdown 解析和复杂版面解析等能力。当前学习版按计划先得到平文本：Markdown 保留源文本中的标题/代码标记；文本型 PDF/DOCX 使用 Tika。当前实现没有复制完整的 MinerU、结构化表格和版面恢复能力。

## 3. 代码与职责

以下 Java 路径相对 src/main/java/com/dujiteng/ragentlearning。

| 位置 | 本次职责 |
| --- | --- |
| knowledge/controller/KnowledgeController | 新增 parse、text 两个接口，传递认证结果中的 userId |
| knowledge/service/impl/DocumentParsingServiceImpl | 归属校验、占用任务、选择解析器、持久化结果与错误 |
| knowledge/parse/DocumentParser | supports/parse 统一接口，公共文本检查 |
| knowledge/parse/PlainTextDocumentParser | 严格 UTF-8 读取 TXT/Markdown，处理 BOM、换行 |
| knowledge/parse/TikaDocumentParser | PDF/DOCX 提取文字，关闭 PDF OCR 和嵌入附件递归解析 |
| knowledge/repository/KnowledgeRepository | 原子状态更新、任务编号校验、带归属条件读取正文 |
| db/chat-schema.sql | 使用 ALTER 给已有文档表增加解析字段 |
| static/knowledge-test.html | 解析/重新解析/重试、查看文本、失败原因与状态 |
| pom.xml | Tika core、PDF 和 Microsoft 解析模块，固定 3.3.3 |

没有改现有 ModelClient、聊天提示词或模型流解析代码。本步骤不调用聊天/Embedding 模型。

## 4. 顺着一次点击理解

1. 页面 POST 文档 parse 地址，auth.js 显式添加 Authorization: Bearer 请求头。
2. Controller 从 StpUtil 取可信 userId，交给 DocumentParsingService。
3. Repository 检查用户、知识库、文档三者匹配。越权在读取原文件前返回 404。
4. 一条 UPDATE 把文档设置为 RUNNING，并生成这次解析的 token；不同请求不能同时占用一个有效任务。
5. 服务按 fileType 选择 PlainTextDocumentParser 或 TikaDocumentParser。解析器只读原文件、产文本，不写数据库。
6. 成功时，用同一条 UPDATE 保存完整正文、SUCCESS 和时间。失败时保存 FAILED 与可展示的原因。
7. 页面用 textContent 显示结果，文本中的 HTML 标记作为文字展示。

新增字段：parsed_text（正文）、error_message（失败原因）、parsed_at（成功时间）、parse_started_at（本次开始时间）、parse_token（本次任务标识）。已有原文件和文档记录保留。

列表只读取元数据，正文通过单独 text 接口加载，避免每次刷新列表都携带全部文档正文。

## 5. 状态、重试与边界

| 状态 | 含义 |
| --- | --- |
| PENDING | 已上传，尚未开始解析 |
| RUNNING | 正在解析或上一次解析尚未完成收尾 |
| SUCCESS | 完整文本已保存，可查看 |
| FAILED | 本次解析失败，原因已保存，可以再次尝试 |

重新解析开始时清空旧正文/错误/成功时间，再以本次完整结果替换。不保存被截断的部分正文。

正常重复请求遇到有效 RUNNING 返回 409 DOCUMENT_PROCESSING。若 Java 进程在解析中被中断，五分钟后可以重新点击。新任务会更换 token，旧任务即使稍后完成也不能覆盖新任务。五分钟是占用有效期，不是强制终止解析的硬超时。

当前是同步的小文件解析，没有后台任务队列、独立解析进程或硬超时。解析过程中不持有数据库事务或连接；状态和结果各用短 SQL 完成。结果写入失败会返回 503，不冒充 SUCCESS；修复数据库后刷新确认，必要时等待占用过期后重试。

支持范围：

- TXT/Markdown：严格 UTF-8，包括 UTF-8 BOM；统一 CRLF/CR 到 LF。GBK 等编码请先另存为 UTF-8。
- PDF：已有文字层的 PDF；明确关闭 OCR，扫描图片型文件可能提取不到文字。
- DOCX：提取正文中的文字；不承诺恢复复杂版面、图片内文字和全部表格结构。
- 原上传限制仍为 10 MB；解析输出上限为 100 万 Java 字符。超过上限会失败，不默默截断。

## 6. 本机验收

1. IDEA 刷新 Maven，首次会下载新依赖，然后重新启动 RagentLearningApplication。保留原数据库、模型环境变量和存储目录。
2. 打开 http://localhost:18081/login-test.html 登录，再打开 http://localhost:18081/knowledge-test.html。
3. 选择原来的知识库，对 docs/examples/knowledge-demo.md 点击“解析文本”。应变为“解析完成”，下面能看到包含“朱之文01”的正文。
4. 刷新页面，点击“查看文本”，结果仍在。重启后端并重新登录，也应能查看。
5. 上传自己的文本型 PDF 或 DOCX 并解析，核对其中一个明确的句子确实出现在结果里。
6. 失败场景：把普通文字另存为 broken.pdf，上传后解析。应提示文件无法解析，文档列表显示“解析失败”及原因，原文件仍可下载。

API：

| 方法 | 地址 | 行为 |
| --- | --- | --- |
| POST | /api/knowledge-bases/{kbId}/documents/{docId}/parse | 要求 Bearer 登录，写请求检查同源 Origin；同步解析并返回结果 |
| GET | /api/knowledge-bases/{kbId}/documents/{docId}/text | 要求登录和归属；返回当前状态、文本及失败原因 |

成功解析返回 200；未登录 401；跨源 Origin 403；越权/文档不存在 404；有效任务占用 409；解析输入不可处理 422；数据库访问失败 503。

数据库核对：

```sql
SELECT id, original_filename, status, error_message, parsed_at,
       char_length(parsed_text) AS text_length
FROM knowledge_document ORDER BY id;
```

通过本机验收后，在 IDEA 检查 diff 并提交，可用说明：feat: 提取文档文本并持久化解析状态。本轮仍未替用户提交或推送。

## 7. 实际验证（2026-10-29）

本机 Java 21、Maven 构建打包成功；使用 Tika 3.3.3、PDFBox 3.0.8、POI 5.5.1。版本以本次实际解析的依赖为准。

独立的原生 PostgreSQL 16 容器、独立 Java 进程与本地文件目录，运行 scripts/verify-knowledge.mjs，上传和解析共 18 组通过。其中新增覆盖：

- 在 D8 旧表与旧文档记录上运行增量迁移。
- 解析/读取正文的登录、CSRF、跨用户与跨知识库校验。
- UTF-8 中文、BOM、换行、Markdown 原文及 Unicode 字符数。
- 由 PDFBox 和 POI 实际生成的 PDF、中文 DOCX 成功提取。
- 损坏 PDF/DOCX、加密 PDF、无文字 PDF、非法 UTF-8、空白文本、文本超限均保存 FAILED 和原因，正文为 NULL；失败任务可再次尝试。
- 数据库中模拟有效 RUNNING，重复请求被拒绝；占用过期后可重新解析。
- 实际添加数据库 CHECK 约束拒绝成功结果写入，确认返回 503、没有半条正文；解除约束并让占用过期后重试成功。
- 真实 Java 进程重启、重新登录后正文保留；退出后不能读取。
- 新页面脚本通过语法检查。

本轮未连接用户现有学习数据库、未调用真实模型、未做真实浏览器点击或截图。原来 D8 的 HTTP 上传与隔离检查也已随本轮重新通过。

复跑：

```powershell
mvn clean verify dependency:build-classpath "-Dmdep.outputFile=target/parser-classpath.txt"
node scripts/verify-knowledge.mjs
```

验证程序会生成独立 PDF/DOCX 样本，结束后清理自己创建的应用和数据库容器；日志、样本与结果留在 target/knowledge-test-随机编号/。

## 8. 接下来学什么

这一步需要能指出：原文件在哪里读取、解析器怎样选、状态在哪更新、失败原因到哪里查。然后进入 D10：把提取的长文本切成带顺序与来源编号的片段。Embedding 和向量检索在后续步骤接入。

官方参考：[Tika 下载与 3.x 维护线](https://tika.apache.org/download.html)、[PDFParserConfig](https://tika.apache.org/3.3.3/api/org/apache/tika/parser/pdf/PDFParserConfig.html)、[文本长度限制异常](https://tika.apache.org/3.3.3/api/org/apache/tika/exception/WriteLimitReachedException.html)。
