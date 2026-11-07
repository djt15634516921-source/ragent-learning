# 知识库闭环、后台入库、聊天管理与基础检索评测

日期：2026-11-06。按用户本次确定的优先级，在原五模块工程上增量实现；不引入新中间件，不更换依赖版本，不接入 BM25、RRF、Rerank 或 Agent。

## 本机使用

在 IDEA 重新构建并重启 `RagentLearningApplication`，继续使用 bootstrap 类路径、工程根目录和原有数据库、Redis、模型环境变量。无需新增凭证。应用启动依次执行原 `db/chat-schema.sql` 和新的 `db/migration/V18__knowledge_lifecycle.sql`，均可重复执行；没有使用 Flyway，也不删除或重建旧表。

知识库页提供“上传并自动入库”，使用页面里的切分参数；原“上传到当前知识库”、解析、切分、向量化接口与按钮保留。已有文档可点“一键入库”。阶段和错误显示在“入库进度”，完成后返回聊天页选择该知识库。

“删除文档”和“删除当前知识库”会要求页面确认，删除原文件、正文、片段、向量及对应入库任务。知识库行保留 `deleted_at` 标记供历史会话引用，不再出现在可用列表中。原问答和来源快照保留；旧会话仅可查看历史，不能继续检索，也不会自动转换成普通聊天。

聊天页增加“删除当前会话”“加载更早消息”和“停止生成”，独立 SSE 页也有停止按钮。删除会话会移除全部消息，保留编号的删除标记，避免在途回答或再次使用同一编号将它重新创建。停止操作可能与完整提交同时发生：返回 CANCELLED 表示停止且未保存该中断回答；COMPLETED 表示完整回答已经提交；NOT_RUNNING 表示任务已结束，应以历史为准。

## 职责与实际调用链

| 入口 | 业务服务与组件 | 持久化边界 |
| --- | --- | --- |
| KnowledgeDeletionController | KnowledgeDeletionServiceImpl、FileCleanupServiceImpl | KnowledgeDeletionRepository、KnowledgeLifecycleMapper |
| DocumentIngestionController | DocumentIngestionServiceImpl、原 Parsing/Chunk/Index Service | DocumentIngestionRepository、原阶段 Repository |
| ConversationController | ConversationServiceImpl、ChatStreamRegistry | ChatMemoryRepository、ConversationMapper/MessageMapper |
| KnowledgeRetrievalController | KnowledgeRetrievalServiceImpl、KnowledgeProcessingSettings | 原 KnowledgeVectorMapper |

业务 Service 均保留接口＋Impl、构造器注入；Controller 获取可信 userId 后交给业务层。任务 ID、文档 ID 都不能替代归属校验。工作线程使用任务记录中的可信 userId/知识库/文档编号，不读取请求线程的登录上下文。

删除链路：Controller → 删除 Service → 独立 Repository 代理开启短事务 → 锁定当前用户的有效知识库及文档 → 写入 file_cleanup → 删除文档，数据库外键级联清除片段、向量、任务 → 标记知识库删除（单文档删除则不标记库）→ 提交 → 清理文件。文件清理失败返回 pendingFileCleanups 并保留重试队列，后台继续处理；不会因为文件清理失败让已删除资料重新可见。

自动入库链路：Controller → DocumentIngestionService.upload/submit → 保存原文件及文档元数据 → Repository 校验归属并写任务 → 返回任务 ID → 定时派发到独立有界线程池 → 显式更新 PARSING/CHUNKING/INDEXING → 调用现有解析、切分、向量化接口 → 各自短事务发布 → SUCCESS/COMPLETED。网络等待和文本计算不在数据库事务中。

上传和任务创建分成两个提交边界。如果原文件已经保存、任务创建失败，接口会明确返回文档编号，保留上传成果；刷新列表对该文档重试一键入库即可。同步调用 `?async=false` 也使用任务记录，返回后须检查 status：只有 SUCCESS 表示全部阶段完成，FAILED 会携带阶段和原因。HTTP 200 表示获得终态任务记录，不代表失败任务被当成成功。

重试会检查实际数据：解析成功且正文存在则复用；正文/切分版本一致且参数相同则复用片段；向量版本与模型 profile 匹配则复用索引。检查正文必须使用 `textOwned`，不能从不包含正文的元数据查询误判“尚未解析”。改变参数会按原版本机制使旧索引失效。

## 并发、租约与重启

DocumentProcessingCoordinator 用本机读写锁协调同一文档：手动阶段可并行进入，仍由原数据库行锁、版本和令牌决定写入；自动入库独占阶段链，期间手动重解析/重切分/重向量化明确返回冲突。相同参数重复提交会返回已有活跃任务；不同参数返回冲突。数据库部分唯一索引确保一份文档最多一个待处理或执行中的入库任务。

删除不等待解析和远程模型完成。数据库删除使阶段行和任务消失，旧结果无法按原 token/version 条件发布；知识库列表、下载、正文、片段、向量检索及入库查询均过滤删除状态。

任务认领生成新的 run_token，心跳通过独立调度线程续租。租约过期后先废弃解析/向量化阶段令牌，再将任务标为 FAILED/INGESTION_INTERRUPTED；旧执行不能覆盖新状态。进程重启后，PENDING 任务继续派发，已中断的 RUNNING 任务等租约到期后明确失败，等待用户重试，不自动重放可能计费的向量化。

当前为单实例后台处理方案。数据库任务、阶段令牌和租约提供持久化及迟到结果保护；本机读写锁和流式注册表不等于完整多实例协调，也不等于 RocketMQ 可靠消息或分布式任务取消。后续相应阶段再升级。

SSE 的 start/delta/done/error 保留，start 额外提供 streamId。ChatStreamRegistry 管理当前实例的活跃流；取消时关闭上游输入流并中断工作线程。StreamCancellation.commit 仅围绕问答保存的短事务确定取消/提交先后：取消先发生则不进入保存，保存已开始则等待提交结果。删除与 appendTurn 共用会话行锁，防止删除后迟到插入。完整回答和来源仍同事务提交后才发送 done。

## 历史分页与参数

原 `GET /api/conversations/{id}/messages` 仍返回最近六轮，兼容旧调用。新增 `/messages/page?size=20&beforeId=...` 返回 messages、nextBeforeId、hasMore；使用消息主键游标，页内按时间正序展示。并发新增消息不会让旧游标页重复。页面加载全部历史不改变模型的六轮输入窗口，也不恢复旧助手正文作为 RAG 事实证据。

| 环境变量 | 默认值与含义 |
| --- | --- |
| KNOWLEDGE_CHUNK_SIZE / KNOWLEDGE_CHUNK_OVERLAP | 800 / 120；独立切分和一键入库均可按次覆盖；重叠最多为块长的一半 |
| KNOWLEDGE_INGESTION_WORKERS | 2；允许1～8，内存派发队列16，超出留在数据库等待 |
| KNOWLEDGE_INGESTION_POLL_MS / KNOWLEDGE_INGESTION_LEASE_MS | 1000 / 15000毫秒；租约至少为轮询间隔3倍，最多5分钟 |
| KNOWLEDGE_CLEANUP_POLL_MS | 30000毫秒；清理失败后一分钟可再次尝试，任务跨重启保留 |
| RAG_TOP_K / RAG_MIN_SCORE | 4 / 0.35；聊天默认检索设置，Top-K范围1～20 |
| RAG_MAX_CONTEXT_CHARS | 6000；按Unicode码点计算资料预算，不冒充Token数，范围200～20000 |

每份文档的最近任务列表最多返回100项；活跃入库任务有100项的准入检查，派发线程和队列都有上限。文档阶段数据和任务分别保存；这些数量是当前学习规模限制，不是吞吐能力指标。

检索调试页调用原 `/search`，可传本次 topK/minScore，不改变聊天默认参数。结果包含顺序、原文件、片段序号、余弦相似度、原文位置和正文。`/search/settings` 读取当前有效默认设置，不返回模型凭证。

## 基础评测与使用费用

`scripts/evaluate-retrieval.mjs` 提供可重复的文档级检索评测。固定样本在 `docs/examples/retrieval-eval.json`，原五份合成资料在 `docs/examples/rag-eval/`。先使用独立知识库上传这些文件并完成入库，文件名保持一致。

在本地环境设置 RAG_EVAL_URL、RAG_EVAL_TOKEN、RAG_EVAL_KNOWLEDGE_BASE_ID，可选 RAG_EVAL_TOP_K/RAG_EVAL_MIN_SCORE，然后执行：

```powershell
node scripts/evaluate-retrieval.mjs docs/examples/retrieval-eval.json target/retrieval-baseline.json --allow-model-calls
```

脚本不上传或修改资料，不调用回答模型；检索仍会调用 Embedding，运行前需要明确的 --allow-model-calls。Token 只通过环境变量传入，不进入命令示例、报告或日志。请求有超时且不跟随重定向。

报告记录题集哈希、参数、知识库编号、Embedding模型标识/维度/向量空间profile、逐题片段/来源版本/排序、耗时、可评分题数量、文件命中率、预期文件召回比例和首个相关结果的倒数排名。profile是已有的空间哈希，不包含密钥；模型提供方的实际权重版本仍需另行记录。缺失手机号/费用等题目未标注相关文件，单列人工复核，不自动计成正确或错误。

这些指标只按文件名判断，不验证片段是否包含完整证据，也不评价生成答案的忠实性与正确率。它们不是 RAGAS。正式效果对比还需要固定真实提供方/模型版本、资料版本和人工证据标注。隔离回归中的确定性假向量结果只用于验证评测工具工作，不作为实际检索提升证据。

## 验证与代码阅读

构建和隔离回归命令仍按 README，新增 verify-lifecycle.mjs、verify-lifecycle-browser.mjs。回归使用独立 PostgreSQL/Redis、端口和文件目录，只调用本机假模型；删除触发器、失败文件目录及其他故障只作用于本次合成数据。

验证覆盖：迁移重复执行与旧数据保留；归属与删除后不可访问；删除事务失败整批回滚；文件清理失败重试；同步/异步入库及进度；解析/向量化失败与阶段复用；重复提交和独立接口冲突；执行中删除；租约续期和重启；消息游标分页；显式取消和会话删除；旧知识库会话/来源保留；参数调试和评测报告。最终实际结果见 README 本次验收记录，不把历史验证或编译通过冒充全部效果通过。

本轮最终 `mvn verify dependency:build-classpath` 通过22个单元测试（原19个＋3个取消/保存竞争测试）；`RUN_BROWSER_CHECK=true` 完整回归通过76组，实际报告为 `target/knowledge-test-79450383/result.json`，持久摘要在[evaluation/2026-11-06-knowledge-lifecycle.json](evaluation/2026-11-06-knowledge-lifecycle.json)。Edge已验证一键上传、阶段轮询、刷新后进度恢复、失败重试、文档/知识库删除、历史分页、会话删除、两页停止生成和删除库后的历史只读状态；截图已人工查看。

最终检索工具检查报告为 `target/knowledge-test-79450383/retrieval-evaluation.json`，只用两道合成题验证报告生成和人工复核标识，不是固定十题的真实模型效果基线。未执行新的真实提供方调用，未宣称已完成Rerank、RAGAS或分布式任务控制。

阅读顺序建议：先看 Controller 的请求/响应，再看删除 Repository 的事务和清理队列；随后看入库 Service 的 process、stage 与重试判断，最后看租约维护、会话行锁和取消/提交竞争。重点是解释失败后留下什么、谁负责重试、旧执行为什么不能发布，而不只记调用顺序。

本轮开工前备份位于被忽略的 `data/feature-baseline-20261106-1793974134285`，包含158个源码/文档文件及未提交diff。用户原有 ApiResponseHeaders 修改和自定义提示词保留；未提交或推送Git。

下一步在本轮功能验收后回到检索重排与 Agent 主线。建议手动提交说明：`feat(knowledge): 完成删除与异步入库闭环并完善聊天管理和检索评测`。
