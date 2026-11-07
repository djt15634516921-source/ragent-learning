# Ragent 学习工程开发约定

## 必须遵守

- 修改前确认目录、读取最新源码并检查 Git 状态。学习工程 `D:\jastudy\idea\ragent-learning`，参考工程 `D:\jastudy\idea\ragent`。保留用户未提交修改、自定义提示词、解析功能和源码压缩包。不自动提交、推送或硬重置。
- Maven 聚合 framework、infra-ai、system、rag、agent、bootstrap。根 POM 统一版本，bootstrap 负责启动和打包可运行 JAR。编译目标为 Java 17，使用 Spring Boot 4.1.0 和 PostgreSQL。
- bootstrap 依赖 system、rag、agent、framework。agent 依赖 rag、infra-ai、framework。rag 依赖 infra-ai、framework，system 和 infra-ai 分别依赖 framework。Controller 通过 Sa-Token API 取得可信 userId 并显式传给业务层。
- 模块 POM 只声明实际需要的依赖；版本统一放根 dependencyManagement，不能把所有依赖放父 dependencies 让每个模块继承。业务类库不应用 Spring Boot repackage。
- 数据访问统一 MyBatis-Plus 3.5.18（spring-boot4-starter）；认证统一 Sa-Token 1.46.0（Boot 4 starter、Redis Template、Jackson 3 插件）。新版本必须核对官方兼容说明并实际启动。禁止混用 Boot 3 starter。
- Spring Security 仅保留独立 crypto 用于 BCrypt。不恢复 SecurityFilterChain、UserDetails、AuthenticationPrincipal 或第二套认证状态。
- system 模块放用户认证；rag 模块内部按 chat/knowledge 业务组织；infra-ai 放模型调用；framework 仅放跨模块基础约定；bootstrap 只放启动、全局 Web 装配和运行资源。Java 包名尽量稳定，内部目录按需创建。业务 Service 为接口和带 @Service 的 Impl；Controller 构造器注入接口，不直接访问 Mapper。不机械创建空目录、MapperImpl 或通用 IService 继承。
- 实体统一 DO 后缀。DTO 接收参数，VO 限定响应；不返回密码哈希或内部存储键。Mapper 继承 BaseMapper，关联查询、归属原子写入及 PostgreSQL 特性保留自定义 SQL。Repository 仅在承担归属、转换或事务等实质职责时保留。
- 当前用户通过已验证的 StpUtil 获取，业务显式传 userId，SSE worker 不读取请求线程上下文。所有资源操作保留归属过滤，旧无归属会话不能被自动认领。他人资源按现有约定返回 404。
- 只接受 Authorization: Bearer 请求头，不从 Cookie、请求体或 URL 读取 Token。浏览器在当前标签页 sessionStorage 保存，401 清理，不自动重放写请求。下载用带请求头的 fetch+Blob，禁止 Token URL。
- 注册/登录接收 JSON，保留 BCrypt 12 轮及已有哈希，密码正确后才能建立会话。Redis DAO 必须是实际持久层；故障明确返回 503，禁止内存回退。
- Redis 默认 127.0.0.1:6379、DB 0，密码经 REDIS_PASSWORD 注入；键前缀必须以 ragent-learning: 开头，默认 ragent-learning:auth:。不清库或删除其他工程的键。
- 登录默认绝对有效期 8 小时、不自动续期；多端各自获得新 Token，退出仅撤销当前 Token。行为变更必须同步文档和测试。
- 当前页面同源部署，无任意来源 CORS。写请求的 Origin 若存在必须匹配当前服务。认证不依赖自动 Cookie，因此移除旧 CSRF 交换；/api/auth/csrf 明确返回 410。未来若引入 Cookie，须重新设计 CSRF。
- 完整问答由 ChatMemoryRepository.appendTurn 经 Spring 代理在一个短事务中保存 user/assistant 两条消息。禁止同类内部调用绕过代理，禁止把模型等待、SSE 发送或长文件解析放进数据库事务。
- 保留同会话本机并发锁、最近 6 轮历史、消息编号顺序和 start/delta/done/error。完整回答保存后才发 done。取消、超时、半截流或写库失败不保存成功轮次。
- 上传与解析分开；上传写库失败清理新文件。解析保留状态、失败原因、完整正文、5 分钟租约及 parse_token 条件更新；旧任务不能覆盖新任务。Parser 不访问 Mapper。
- 不删表重建、不清记录、不重新分配归属。结构变化与现有 db/chat-schema.sql 幂等迁移协调。每次结构变化须提供可重复执行的增量 SQL，保留旧记录与归属。
- 保留统一异常处理和错误码，区分参数、认证、权限、资源、数据库、文件和模型错误。不能吞异常返回空列表或成功，不记录密码、Token、Cookie、密钥或完整敏感正文。
- 凭证仅用环境变量或被忽略的私有配置，示例不含真实值。注释解释原因和边界，避免逐行复述。
- 验证使用独立 PostgreSQL/Redis、端口、文件目录和本机假模型；不得对用户业务数据做故障注入。编译、集成、浏览器和真实模型验证分别报告，不能虚构结果。
- 基础 RAG 已完成 Embedding、pgvector 入库、知识库检索与带来源回答；普通聊天继续兼容。后续按 docs/15-core-learning-roadmap.md 推进 AgentScope 等核心能力；阶段计划不代表功能已经实现。引入新依赖时核对当前 Java/Spring Boot 兼容性，不为接入 SDK 随意升级或降级。

## 可选建议

- 新抽象、分页接口、缓存和分布式锁须有明确场景；当前单实例锁不代表多实例并发保证。
- 保持显式 import、四空格缩进与构造器注入，优先保证学习代码可读。
- 按影响范围验证，完整命令见 README。可选浏览器回归使用 RUN_BROWSER_CHECK=true。
- 讲解沿实际输入输出、Controller、ServiceImpl、Mapper/组件展开，重点理解身份、归属、事务及排错证据。

## 多模块开发和验证

- 应用配置、SQL 初始化、静态测试页统一放 bootstrap/src/main/resources；HTTP 样例在 docs/http。原始源码压缩包归档 docs/archive/main.zip，保留字节。
- IDEA 运行 RagentLearningApplication 时选择 bootstrap 模块类路径，工作目录固定项目根目录，环境变量沿用原配置；不要让相对路径 data/knowledge 指向 bootstrap/data。
- 根目录构建使用 mvn clean verify。命令启动先构建，再从根目录 java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar。spring-boot:run 仅对 bootstrap 执行，先 install 本地模块；不要把 run 目标用 -am 应用到所有模块。
- 完整回归构建：mvn clean verify dependency:build-classpath "-Dmdep.outputFile=target/parser-classpath.txt"，然后 node scripts/verify-knowledge.mjs。脚本使用 bootstrap 的可运行包及 rag 的解析依赖类路径。

## D10 文档切分

- 切分在 rag/knowledge 内，业务 Service 继续接口＋Impl；TextSplitter 为明确的纯计算组件，不访问数据库或模型。
- 长度/位置按 Unicode 码点计算，默认 800/120，序号从 0 开始，位置为解析正文的左闭右开区间。不得将字符数冒充 Token 数或 PDF 页码。
- 文档正文版本与切分版本必须匹配才能读取片段；重新解析使旧片段失效，保存旧版本结果须返回冲突。
- 切分计算在事务外，整批替换和统计在独立 Repository Bean 的短事务内；批次失败必须恢复旧片段。相同版本和参数复用结果。
- 使用 PostgreSQL MyBatis-Plus 分页，保留用户/知识库/文档归属 JOIN；jsqlparser 版本与现有 MyBatis-Plus 统一为 3.5.18。
- 完整验证包含 TextSplitterTest、verify-chunks.mjs 和已有回归；不使用用户业务库做故障注入，不自动提交或推送。

- 本机私有连接覆盖可放工程根目录 config/application.properties，该文件必须被 Git 忽略，真实凭证不能写入示例。bootstrap 中的配置仍为共享基线；外部覆盖依赖正确的工程根工作目录，修改后须重启。

## D11 知识库问答必须遵守

- Embedding与聊天分别负责向量和文本。当前默认百炼text-embedding-v4、固定1024维、每批最多10条，复用现有密钥但不写入源码。ModelClient保留为统一LLMService协议实现，保留原chat/stream便捷签名；用户原有基础提示词保持不变。
- 文档向量与问题向量必须匹配 endpoint/model/dimensions profile；换空间需重新向量化，禁止静默混用、补零、截断或生成假向量。
- 向量化显式触发，有状态、失败原因、租约与 token。远程计算在事务外，全部向量与成功统计在独立 Repository 的短事务内提交；部分失败不能发布索引。
- chunk_revision 区分相同正文的不同切分；重解析/实际重切分失效旧索引，旧任务不能发布。检索在 SQL 同时过滤归属、正文/切分/索引版本与向量空间。
- 普通会话和选定知识库会话固定模式，切换时新建会话。SSE 维持原事件协议，done 增加来源且必须在完整问答与 sources_json 同事务保存后发送。
- 检索资料是当前 user 消息中的引用数据，不提升为 system 指令。无可用索引明确报错，无足够相关资料明确拒答；来源为当次快照，不随文档变化改写历史。
- 知识库模型输入按当前 sources 顺序显式提供 citationId、文件名与正文。旧助手正文不能直接充当本轮事实证据；当前用占位消息隔离发送副本，保留近期用户问题，数据库/页面历史与普通聊天不改写。未来调整须复验错误金额历史、缺失事实拒答及引用对应，不能硬编码评估题答案或宣称提示词等于事实核验。
- 完整回归包括 verify-rag.mjs；真实提供方验收脚本 verify-rag-provider.mjs 会产生调用费用，只在需要且使用合成资料、独立容器时执行。不得自动向量化用户已有资料或用业务库注入故障。

## 学习重点与简历能力（2026-11-02）

- 用户明确要求以其提供的 Ragent 简历教程中的核心能力作为后续学习目标。以 docs/15-core-learning-roadmap.md 为当前范围与进度对照；原三十天计划中把高级记忆等排除在目标外的约定不再作为最终范围限制，不重置已经完成的进度。
- 主线包含 AgentScope ReAct/MCP、Skills 渐进加载与工具校验、可恢复人工确认、三层记忆和长期事实更新、混合检索与预算、效果评测及分布式流式任务控制。逐项实现与验收，不一次性添加全部中间件或机械复制原项目类目录。
- 每项讲解必须覆盖业务问题、关键代码和数据结构、方案取舍、失败/并发边界、实际验证；仅解释 Controller/Service 调用顺序或演示一次成功，不能视为已经掌握该亮点。登录和普通管理能力保持必要维护，学习时间优先用于 AI 主线。
- 明确区分近期历史与三层记忆、聊天记录持久化与 Agent 状态恢复、Redis 登录与分布式任务控制、向量版本校验与长期记忆更新、功能回归与 RAG 效果评测。已有基础只能记作部分前置能力。
- 简历只描述实际实现、能解释且有验证证据的能力；教程中的 Faithfulness +10.3%、Answer Relevancy +8.0%、20+ 指标、8+ 线程池及三路召回不能当成本工程结果。指标须记录题集、版本、配置、分母、成本和前后结果。
- 原项目参考以 GitHub nageoffer/ragent 的当前源码为主，记录核对提交以便复查；2026-11-02 已确认 main 为 c3b6157730ac1f66a312bcd286c47d3ee6aaac42，包含 Skills、人工确认恢复和长期记忆实现。本机参考副本 ed05927（2026-08-26）较旧，不能以它缺少某类为由停下远端核对，也不自动拉取覆盖本机目录。教程和源码细节不一致时，解释当前实现及取舍，不机械照抄简历模板；例如该远端版本长期记忆采用用户锁、处理游标与短事务提交，不能直接描述成模板中的版本号双校验。

## D15 多轮检索问题改写

- QueryRewriteService/Impl只处理知识库连续提问；先验证用户/知识库/会话归属，在原同会话锁内读取一次历史。普通聊天和知识库首轮不增加改写调用。
- 改写上下文仅含近期用户问题，最多6条/6000个Unicode码点；旧助手正文继续隔离。改写只恢复对象和约束，不作为资料事实，也不扩大知识库范围。
- 保留originalQuestion（原始提问）、searchQuestion（检索输入）、modelQuestion（原问题/检索问题/本轮资料包）的区别。数据库仍存原提问和来源快照；retrievalQuery元数据仅随普通响应/SSE done返回，不伪装成已持久化Trace。
- 改写使用现有模型配置与独立总超时，默认3000毫秒；结构校验失败/上游故障/超时显式回退原句，取消必须传播而不是降级后继续生成。AMBIGUOUS返回澄清并跳过检索和回答模型。
- D15阶段未做多问拆分、术语映射或基于助手回答的指代消解；重排现状见下方D19约定。后续扩展应保留金额幻觉与来源隔离边界。不得在生产实现中硬编码评估题的主体或答案。
- 回归包含QueryRewriteServiceTest、verify-rewrite.mjs与Edge页面检查。真实提供方验收另行报告；新增调用费用与配置见docs/16-query-rewrite.md。

## RAG核心与模型调用边界（2026-11-02架构对齐）

- rag模块内的chat负责会话、用户归属、同会话锁与保存协调；rag.core.rewrite负责检索问题改写，rag.core.workflow负责固定RAG前处理，rag.core.prompt负责模型消息组装。核心能力采用接口＋Impl，核心层不反向引用chat的DTO/VO。
- RewriteResult、RagContext是内部结果；QueryRewriteVO由聊天边界转换后响应。HTTP字段与SSE事件不因内部整理改变，不把内部结果直接扩散成前端协议。
- 固定问答和改写共用 infra-ai 的 LLMService 与 ModelClient。Agent 的工具消息交给 AgentScope OpenAI 适配器，连接使用同一套 llm 配置。
- 请求消息、总超时、temperature、max_tokens、完整响应长度预算通过不可变LLMRequest传递；不得修改共享客户端全局配置实现单次短超时。改写仍默认3秒、512输出Token、65536响应字符预算；回答默认60秒，流式仍使用原解析器与取消资源关闭逻辑。
- 自定义SYSTEM_PROMPT继续保留在ChatServiceImpl；RAG规则、来源编号、历史助手正文隔离迁移到DefaultRAGPromptService时保持内容一致。原始问答与来源仍由独立Repository短事务保存，不将远程调用放进数据库事务。
- 当前RagWorkflow实现改写、检索（含可选重排）与直接澄清/拒答，不声称已有完整Workflow框架、多模型路由或Agent。后续在该边界内扩展，避免再堆进聊天服务。

## 知识库闭环与聊天管理（2026-11-06）

- 用户本次将知识库删除、一键/异步入库、进度重试、聊天管理和基础检索评测置于重排之前；范围与接口见docs/18-knowledge-lifecycle.md。完成这轮后再回到Rerank/Agent主线，不在本轮接入BM25、RRF或新中间件。
- 增量结构在db/migration/V18__knowledge_lifecycle.sql，由Spring SQL初始化按原schema之后执行；保持幂等，不删除旧表或重置业务数据。业务删除仅作用于用户明确选择且归属正确的资源。
- 知识库删除保留deleted_at行供历史会话绑定，文档/正文/片段/向量/入库任务清除；历史问答和来源快照保留，已删除知识库不能继续检索或隐式变成普通聊天。所有文档、检索和任务入口过滤删除状态。
- 删除元数据和file_cleanup记录在独立Repository短事务中提交，磁盘清理在提交后执行；失败保留持久队列并明确返回pendingFileCleanups。不能声称@Transactional会回滚磁盘文件，不输出内部存储键。
- DocumentIngestionService接口＋Impl编排现有解析/切分/向量化服务，独立接口继续保留。上传成功但任务创建失败须明确返回已上传文档编号，不悄悄删除原文件或引导重复上传；任务status=FAILED不能描述成入库成功。
- 入库任务以数据库为准，显式传可信userId。只复用版本/参数/profile有效的阶段；查询正文须用包含正文的方法，不能从元数据查询的null字段推断未解析。模型调用不进入长事务。
- 单实例读写协调器允许原手动阶段并发并保留行锁/token/version校验；自动入库独占同文档阶段链。数据库唯一索引约束一文档一个活跃任务。相同参数重复提交返回已有任务，不同参数明确冲突。
- 心跳与远程任务使用独立线程；租约过期先废弃阶段令牌，再标记任务失败。PENDING跨重启继续派发，RUNNING中断不自动重放付费调用，等待手动重试。禁止把当前本机锁和租约描述成完整分布式调度或MQ可靠投递。
- 会话删除与appendTurn在短事务中共用会话行锁；清除消息并保留编号删除标记，禁止迟到写入或用同编号复活。历史分页使用消息主键游标，与模型最近六轮窗口分离。
- SSE保持原事件，start可附加streamId；显式取消校验用户/会话/任务归属。取消与完整问答提交通过StreamCancellation.commit确定先后，页面根据CANCELLED/COMPLETED/NOT_RUNNING说明结果；不把“停止接收”当作后端取消确认。本机注册表不等于跨节点取消。
- 切分默认值来自KnowledgeProcessingSettings，按次覆盖仍须校验。检索预览可覆盖Top-K/最低分，聊天使用默认配置；资料预算按Unicode码点，不能当作Token。评测记录题集哈希、参数、分母、来源与耗时；文件命中指标不能冒充答案正确率或RAGAS。
- 新增功能回归在verify-lifecycle.mjs及verify-lifecycle-browser.mjs，继续使用独立容器/端口/目录和假模型。真实提供方效果与付费调用单列报告，不在用户业务库做故障注入，不自动提交或推送。

## D19 重排与检索预算（2026-11-06）

- 本轮依据上游GitHub提交200dca86ac04d3a27f7cfffd7d3dd4d2b1c41129的RetrievalBudget、RerankPostProcessor和BaiLianRerankClient。仅完成单路向量召回后的重排、分层预算与对比观察，不宣称已实现BM25/RRF/图谱、多模型路由、Agent或RAGAS。
- RetrievalBudget区分recallBudget/candidateLimit/contextTopK，默认16/12/4；当前单路约束1≤contextTopK≤candidateLimit≤recallBudget≤100，最终片段数≤20。先召回并截候选池，再对整个候选池打分，最后按条数与资料字符预算选择完整片段。
- RerankPostProcessor属于rag.core.retrieval，远程协议属于infra-ai.rerank.RerankService/BaiLianRerankClient。聊天只消费RagContext，不添加第二套模型认证、Mapper或提示词实现。
- 默认关闭重排，不增加隐式收费调用。RERANK_URL必须明确配置百炼原生text-rerank端点，默认qwen3-rerank，密钥可复用LLM_API_KEY。普通聊天/首轮规则不变；启用重排不要求重新向量化已有资料。
- 重排响应index只映射回本次可信候选，忽略上游回传正文。索引必须完整、唯一且合法，分数须有限且处于0～1；无效/部分结果整批回退。score保留余弦分，rerankScore独立且可空，禁止混用阈值或改写历史快照。
- 总超时覆盖完整响应体，读取时限制256KiB；不自动重试。重排失败/超时显式回退原向量排序，取消必须传播，不能在取消后降级继续回答。模型等待继续在数据库事务之外。
- 资料字符与重排输入字符预算按Unicode码点，后者按候选逐次计入问题；不等于Token、费用或提供方单条限制。超字符预算明确跳过重排，不静默截断或换模型。未召回的事实无法由重排恢复；高分也不保证包含所问事实。
- 原/search列表协议保留；/search/detail和/search/compare返回边界VO。对比共享同一次问题向量和候选快照；普通响应与SSE done追加retrieval观察字段，历史只保存最终来源及独立分数，不能把响应观察称作持久化全链路追踪。
- 对比脚本区分文件命中、字面证据覆盖和人工答案质量；可选答案对照使用两个新会话并核对证据版本，证据变化标记NOT_COMPARABLE。rerankAttempts是适配器尝试数，usage只记上游实际值，不估算收费或编造效果提升。
- 保留原基础提示词、RAG规则、改写提示词、身份/归属与问答原子提交边界。回归新增BaiLianRerankClientTest、RerankPostProcessorTest、verify-rerank.mjs与真实Edge检查，真实模型效果另列。

## D20 Agent 知识库工具

- AgentScope core 和 OpenAI 模型适配器使用原项目选定的 2.0.2。Agent 模块组装 ReAct 与 Toolkit，检索继续走 KnowledgeRetrievalService，bootstrap 装配 HTTP 入口。
- 每次单轮问答使用独立的 ReActAgent 和工具状态。授权校验先于模型调用，工具构造时绑定可信用户和目标知识库。模型参数仅允许 query，工具执行时再次校验，服务按归属过滤资料。
- 工具结果带本轮 citationId 并通过对应 tool_call_id 进入模型上下文。来源编号取自实际检索片段，重复的片段版本复用编号。使用 textContent 渲染模型输出和资料。
- 服务限制 ReAct 迭代、实际检索次数和整轮等待时间。超时取消订阅并通知检索组件，工具错误用简短说明回传，页面展示稳定错误码。保留 HTTP 接口与工具回归的实际证据。
