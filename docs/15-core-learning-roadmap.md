# 核心学习路线：从基础 RAG 到教程中的 Agentic RAG 能力

校准日期：2026-11-02。依据为用户在当前会话粘贴的《Ragent - 企业级 RAG 智能体平台》简历教程正文、本机学习工程，以及 GitHub 原项目的实际源码。[教程链接](https://articles.zsxq.com/id_z3zoloiewrw7.html)需要登录，本次对照使用用户提供的正文。原项目远端核对基准为 main 的 [c3b6157730ac1f66a312bcd286c47d3ee6aaac42](https://github.com/nageoffer/ragent/commit/c3b6157730ac1f66a312bcd286c47d3ee6aaac42)，提交时间2026-10-25，不声称已运行上游项目或验证其全部功能。

## 目标与当前定位

最终目标调整为：借助 AI 逐步实现教程中的核心能力，理解方案选择、代码结构、并发与失败边界，能运行验证并解释实际效果。简历表述应在这些能力成立后整理。

学习工程为 `D:\jastudy\idea\ragent-learning`，参考工程为 `D:\jastudy\idea\ragent`。首次校准路线前学习工程Git工作区干净，校准保留既有实现、原始提示词、数据、认证和Maven模块，仅调整学习约定与文档。随后D15的代码增量及验证见下方进展，不接入新SDK或中间件。

此前原三十天计划以基础 RAG、简单 Agent/MCP 和架构理解为主要终点，并将长期记忆等列为后续。用户这次明确将教程亮点作为学习重点，因此这些能力进入后续主线；旧计划的已完成工作继续有效，不重置日期或把阶段编号当作完成比例，也不把扩大后的范围强塞进原时间承诺。

当前可用的是基础 RAG：原文件、解析正文、分块、向量化、检索、带来源的普通/流式回答，以及认证、归属和事务保障。教程的高级能力多数尚未实现。学习过程中分别记录“已实现”“已验证”“已由用户讲清”，不混为同一状态。

2026-11-02阶段1进展：已实现仅依据近期用户问题补全主体的改写、明确降级/澄清、普通/SSE接入与页面展示；14个单元测试和58组假模型/集成/Edge检查通过。用户随后完成一条真实模型连续追问样例，截图和只读数据库核对通过；系统性真实效果评测仍待完成，见 [D15说明](16-query-rewrite.md)。其余阶段仍待推进。

接入重排前已完成小范围架构对齐：rag.core下分离rewrite/workflow/prompt，模型统一经过LLMService，19个单元测试和58组回归通过，见 [架构说明](17-rag-architecture-alignment.md)。后续在该边界扩展重排，不推倒现有工程。

## 教程条目逐项对照

| 教程目标 | 学习版现状 | 还需要的实现与验收 |
| --- | --- | --- |
| AgentScope ReAct、多次检索、MCP、跨轮办理 | 未实现；当前由程序固定执行检索与生成 | 模型选择工具、程序校验执行、结果回填、继续决策或结束；工具调用关系与 Agent 状态恢复可观察 |
| Skills 渐进加载、工具双重校验、动态收回 | 未实现 | 加载技能前关联工具不可用；加载后开放对应工具；执行时再次校验；卸载后拒绝旧调用。仅写一个提示词文件不算完成 |
| 可恢复 HITL 人工确认 | 未实现 | 待确认状态与服务端参数快照持久化；校验用户、状态和版本后恢复；覆盖拒绝、篡改、过期、并发确认与重复执行。不能仅依赖页面确认按钮 |
| 工具结果裁剪、会话摘要、跨会话事实三层记忆 | 部分前置：最近六轮历史和聊天记录持久化 | 根据上下文预算控制工具结果与历史；摘要保留近期消息和完整调用关系；跨会话事实按用户隔离，支持更新与来源追溯 |
| 事务外模型判定、长期记忆一致性更新（模板称版本号双校验） | 未实现；向量任务已有短事务和版本/token 校验思路 | 当前上游采用用户锁、消息时序、后台游标及短事务条件提交；围绕实际实现验证冲突与去重，比较版本校验方案的取舍。文档版本控制不能冒充长期记忆功能 |
| 三路召回、加权 RRF、Rerank、分层预算 | 部分前置：单路向量、TopK=4、最低分0.35、6000字符资料预算 | 区分召回候选数、精排数与最终上下文预算；先验证重排，再组合多路检索。第三路必须有真实数据和调用证据，只有两路就如实记两路 |
| RAGAS、A/B Diff、人工校正、效果指标 | 部分前置：十题评估资料、手动失败记录和具体修正复测；完整独立十题基线未完成 | 固定基线、留出题、运行配置及人工判定；逐步接入实际需要的 RAGAS 指标，记录延迟/调用成本与前后对比。业务回归组数不等于效果指标数 |
| 分布式 SSE 任务控制、跨节点取消、资源回收 | 部分前置：本机取消、超时、终态协调和同会话锁 | 用独立任务标识、可信归属、Redis 运行状态及条件更新协调多节点；实例A执行、实例B取消的双实例实验通过。Redis 保存登录并不代表此功能存在 |
| TTL/TraceId 跨线程传递、按业务拆线程池 | 部分前置：工作线程显式传可信 userId，已有流式执行线程池 | 保证 Trace/身份关联并在线程复用后清理，按实际负载隔离；不能为凑“8+”而创建空线程池 |
| RocketMQ 事务消息与异步文档处理 | 未实现；目前解析、切分、向量化由接口分阶段触发 | 任务状态与消息投递协调、事务回查、消费者幂等、失败重试；覆盖提交后异常与重复消息。不能仅把调用放进异步线程就称可靠投递 |
| 数据库租约/续约、多实例刷新、ETag/内容哈希 | 部分前置：已有解析/索引租约、版本/token校验与索引分批续租 | 增加远程资料刷新及变更检测；验证未变更跳过、变更后处理、租约失效接管、旧任务禁止发布。当前没有完整远程刷新链 |

教程中的 Faithfulness +10.3%、Answer Relevancy +8.0% 是模板中的实验表述，不是本工程指标。20+指标、三路召回和8+线程池也不能直接照写；只报告本工程实际覆盖的数量和效果。

## 参考源码：GitHub 当前版本为主

本机参考工程停在 `ed05927`（2026-08-26），只查这个副本会遗漏后续能力。用户已明确可直接参考 GitHub，因此后续以远端源码为主要依据，本机副本用于已有代码对照；不因旧副本缺少实现而等待用户另找源码，也不自动拉取覆盖该目录。

已读取远端目录树（非截断结果）和以下关键源码，Skills、人工确认与长期记忆均有明确实现：

| 能力 | 远端源码与本次确认的行为 |
| --- | --- |
| Skills 按需加载 | [SkillLoadTool](https://github.com/nageoffer/ragent/blob/c3b6157730ac1f66a312bcd286c47d3ee6aaac42/agent/src/main/java/com/nageoffer/ai/ragent/agent/skill/SkillLoadTool.java)：先提供技能清单，调用时获取正文，成功结果携带已加载技能标记 |
| 工具动态遮蔽与执行再校验 | [AgentSkillMaskingMiddleware](https://github.com/nageoffer/ragent/blob/c3b6157730ac1f66a312bcd286c47d3ee6aaac42/agent/src/main/java/com/nageoffer/ai/ragent/agent/skill/AgentSkillMaskingMiddleware.java)根据当前上下文中的技能结果过滤工具；[McpToolProxy](https://github.com/nageoffer/ragent/blob/c3b6157730ac1f66a312bcd286c47d3ee6aaac42/agent/src/main/java/com/nageoffer/ai/ragent/agent/tool/McpToolProxy.java)在执行入口再次检查遮蔽集合，支持确认权限决策 |
| 人工确认与恢复 | [AgentChatServiceImpl](https://github.com/nageoffer/ragent/blob/c3b6157730ac1f66a312bcd286c47d3ee6aaac42/agent/src/main/java/com/nageoffer/ai/ragent/agent/service/impl/AgentChatServiceImpl.java)的 confirmPendingTool/startConfirmRun 从 Agent 状态获取待确认调用，前端只提供同意/拒绝，构造确认结果恢复执行；[AgentConversationServiceImpl](https://github.com/nageoffer/ragent/blob/c3b6157730ac1f66a312bcd286c47d3ee6aaac42/agent/src/main/java/com/nageoffer/ai/ragent/agent/service/impl/AgentConversationServiceImpl.java)按用户、会话、消息和待确认状态校验并结算 |
| 近期上下文裁剪与摘要 | [AgentContextCompactionMiddleware](https://github.com/nageoffer/ragent/blob/c3b6157730ac1f66a312bcd286c47d3ee6aaac42/agent/src/main/java/com/nageoffer/ai/ragent/agent/memory/AgentContextCompactionMiddleware.java)：按字符水位选择摘要压缩或工具结果裁剪，压缩失败有降级分支 |
| 长期记忆后台学习 | [AgentMemoryLearner](https://github.com/nageoffer/ragent/blob/c3b6157730ac1f66a312bcd286c47d3ee6aaac42/agent/src/main/java/com/nageoffer/ai/ragent/agent/memory/AgentMemoryLearner.java)读取游标后的用户发言、当前事实及背景，在用户锁内进行事务外模型判定 |
| 长期记忆主动整理与提交 | [AgentMemoryFlusher](https://github.com/nageoffer/ragent/blob/c3b6157730ac1f66a312bcd286c47d3ee6aaac42/agent/src/main/java/com/nageoffer/ai/ragent/agent/memory/AgentMemoryFlusher.java)处理本轮记住/修改/删除请求；[AgentMemoryRepository](https://github.com/nageoffer/ragent/blob/c3b6157730ac1f66a312bcd286c47d3ee6aaac42/agent/src/main/java/com/nageoffer/ai/ragent/agent/memory/AgentMemoryRepository.java)的 commitBackground 在短事务内条件推进后台游标并提交事实变更 |
| 追踪对接 | [Agent Trace 说明](https://github.com/nageoffer/ragent/blob/c3b6157730ac1f66a312bcd286c47d3ee6aaac42/agent/src/main/java/com/nageoffer/ai/ragent/agent/trace/README.md)解释 Langfuse/OTel 属性映射与回读验证；这里只确认文档及实现入口存在，不将上游实验结果当作学习版结果 |

教程是目标清单，具体算法仍需跟随实际源码。以长期记忆为例：当前后台流程是用户锁内读取、模型判定在数据库事务外、commitBackground(expectedCursor, nextCursor, edit) 在事务内条件推进游标并写事实，不宜直接描述为模板的“版本号双校验”。锁是否覆盖模型等待、业务锁和数据库事务的区别、游标怎样防重，均是要解释的取舍。

RAGAS 的20+指标及模板提升数字尚未据此完成实现/实验核验，不因发现上游评估入口就推定这些结果成立。后续进入相应阶段继续核对具体实现与 SDK 兼容性；参考提交变化时记录新基准，不把本次快照永久当作远端最新状态。

## 推进顺序与阶段出口

以下是功能阶段，不是新的日历天数；阶段1状态以上方进展为准，其他新增能力仍待实施。效果记录从开始就保留，RAGAS/Langfuse的正式接入在相应阶段核实依赖与配置。

| 阶段 | 做什么 | 可检查的阶段结果 |
| --- | --- | --- |
| 0 | 收尾已有拒答/引用修正，补齐独立基线 | 重启使用最新代码；旧失败记录保留；完成已有固定十题并记下实际结果，不反复扩成无止境调提示词 |
| 1 | 多轮问题改写 | 完整问题和“它什么时候验收”等追问均有可解释检索输入；原始问题仍原样保存；歧义/失败有明确行为 |
| 2 | Rerank 与检索预算 | 对同一组含干扰候选的问题比较排序、证据齐全情况、耗时与调用量；区分候选召回与最终资料数，不承诺一定提高 |
| 3 | AgentScope ReAct、知识库工具、独立 MCP 工具、Agent 状态 | 一次任务中真实发生工具选择、执行、回填、终答；模型可根据结果继续；受迭代/超时/取消限制；重启后按设计续办 |
| 4 | Skills 加载与工具准入 | 技能加载、工具开放、调用再校验、状态变化后收回都由服务端实际约束 |
| 5 | HITL 待确认与恢复执行 | 提交演示工单前暂停，确认后按快照恢复；拒绝、篡改、并发和重复确认按设计处理 |
| 6 | 三层记忆与长期事实更新 | 长对话触发压缩并保留工具关系；换会话能读取归属正确的事实；新旧事实竞争和抽取水位实验通过 |
| 7 | 多路召回、加权 RRF 与完整预算 | 先向量+关键词，再建设图谱第三路及相应资料；每路有结果与归因；比较编号精确匹配、语义问题、关系问题及额外成本 |
| 8 | RAGAS、A/B Diff、人工复核与追踪平台 | 能重放固定数据/配置，区分检索失败与生成失败，解释评估模型误判；记录实际指标、分母、延迟和费用；验证 Langfuse 接入 |
| 9 | 分布式 SSE、线程上下文、MQ 入库与远程刷新 | 双实例取消和归属检查、消息重复/失败与回查、续租接管和未变化跳过分别有测试证据 |

阶段2后就进入 Agent 主线，不等待把所有混合检索或提示词细节做完；阶段7将已有重排组合进更完整的检索链。评估和追踪所需的基础记录贯穿各阶段，避免最后才寻找优化前的数据。

业务场景先沿用已有知识库和合成工单资料；后续用“问规则—查工单—加载办理技能—确认提交—续办”串起核心能力。商品咨询只是教程给出的可选场景，不因此新建商城或推倒现有工程。

继续采用 Java、Spring Boot、PostgreSQL、MyBatis-Plus、Sa-Token/Redis 和现有五模块；Agent/MCP 阶段按需要新增对应模块。AgentScope、Redisson、RocketMQ、RAGAS、Langfuse 及检索组件分别核实稳定版本、运行方式与兼容性后再引入，不在本次路线校准中安装或升级。

## 当前项：问题改写应该学到什么

具体场景：第一轮“谁负责星河学习项目的验收？”，下一轮“它每周什么时候验收？”。当前检索只对本轮原句向量化，缺少明确对象可能影响命中。

实现前先把三种输入分清：

| 数据 | 含义 | 后续约定 |
| --- | --- | --- |
| originalQuestion | 用户实际输入的“它每周什么时候验收？” | 聊天记录保存它，不能替换成模型改写后的问题 |
| searchQuestion | 用必要上下文恢复指代后的独立检索问题 | 用于向量化和检索，记录改写是否发生及失败行为 |
| modelQuestion | 用户问题与本轮检索资料组成的模型输入 | 沿用当前来源编号、证据边界和来源快照；历史/改写不能自行添加事实 |

需要深入的代码问题包括：改写发生在何处，输入历史包含什么，结构化输出怎样校验，额外模型调用怎样超时，歧义时何时保留原句或澄清，普通聊天是否参与，以及如何保持用户/知识库边界。只把问题换个说法并看到一次正确答案不算验收完成。

当前知识库生成请求用占位消息隔离旧助手正文，以避免旧错误金额和引用编号污染新证据。引入改写时要单独设计其上下文，不为了支持追问就把旧答案直接恢复成可信事实；已有收费拒答和引用检查应继续通过。

## 每项的学习与交付方式

1. 先展示真实缺口：用可重复的问题或并发步骤说明现有实现哪里不足。
2. 对照参考关键方法和数据结构：解释传入/返回什么，也说明为什么需要这些状态、字段、接口与顺序。
3. 实现一项增量：阅读关键 diff，沿实际调用与失败分支调试，不只看层名。
4. 保留前后证据：输入、资料/模型版本、配置、候选/工具记录、结果、耗时、调用量；涉及评分时记录分母和人工判定。
5. 用户复述设计取舍：能解释一个成功场景、一个失败场景及一种替代方案的代价，再整理能被追问的简历表述。

既有功能回归、真实提供方具体样本复测等记录继续保留，不把原先测试重新记作新一轮执行。路线校准只涉及文档，随后D15增量的独立验证记录见D15说明；两步均未触发真实付费调用。手动提交和推送仍由用户在IDEA完成。
