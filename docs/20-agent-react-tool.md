# AgentScope ReAct 与知识库工具

本页记录首次接入工具的单轮版本。当前 Agent 装配和继续会话的流程见新一阶段。 [Agent 会话与状态恢复](21-agent-conversations.md)

本轮把现有检索能力交给 Agent 作为可调用工具。AgentScope 把工具描述交给聊天模型。模型可以直接回答问候，也可以请求 Java 检索，在看到工具结果之后继续查或生成答案。

代码依据是原项目的 d9e5fc783ff7195d789d829fb35bdbfef928dbcc。原项目通过 ReActAgentProvider 组装模型和 Toolkit，KnowledgeSearchTool 将查到的资料作为 ToolResultBlock 回传。学习版复用这个分工，每次请求绑定一个所选知识库并创建独立的 Agent。 [原项目 Agent 组装](https://github.com/nageoffer/ragent/blob/d9e5fc783ff7195d789d829fb35bdbfef928dbcc/agent/src/main/java/com/nageoffer/ai/ragent/agent/config/ReActAgentProvider.java) · [原项目知识库工具](https://github.com/nageoffer/ragent/blob/d9e5fc783ff7195d789d829fb35bdbfef928dbcc/agent/src/main/java/com/nageoffer/ai/ragent/agent/tool/KnowledgeSearchTool.java)

## 运行一轮

1. 在 IDEA 重新加载根 POM 并重启 RagentLearningApplication，运行模块选 bootstrap。
2. 使用已有的 LLM_CHAT_URL、LLM_MODEL 和 LLM_API_KEY。该模型与接口需要支持 OpenAI Chat Completions 的工具调用。AgentScope 使用 2.0.2 的 core 和 OpenAI 扩展，版本与上游 POM 一致。 [原项目 POM](https://github.com/nageoffer/ragent/blob/d9e5fc783ff7195d789d829fb35bdbfef928dbcc/pom.xml) · [AgentScope 2.0.2](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.2)
3. 登录后打开知识库页。选择含有已向量化资料的知识库，点击页首「Agent 问答」，填入问题并提交。可以先上传并入库 D14 示例目录里的星河、青禾项目资料。 [示例资料](examples/rag-eval)
4. 提问「星河学习项目每周什么时候验收？」查看页面列出的检索词和引用。再问两个不同项目的验收安排，观察模型是否继续请求检索。

当前页面是单轮问答。工具调用和来源随响应展示，每次提问使用独立的工具记录。普通聊天的历史记录在原聊天入口查看。

## 请求与返回值

| 位置 | 接收 | 交给下一步 |
| --- | --- | --- |
| AgentChatController | Bearer 登录态、路径中的知识库 ID、请求体 question | 可信 userId、knowledgeBaseId、原问题 |
| AgentChatServiceImpl | 用户和知识库范围、原问题 | 校验归属，组装 ReActAgent 与 Toolkit 后提交用户消息 |
| AgentScope | 用户消息及 search_knowledge 的参数定义 | 验证模型给出的 tool_calls 并分派给 Java 工具，产生工具结果事件 |
| KnowledgeSearchTool | 本轮服务端绑定的范围、模型生成的 query | 调用 KnowledgeRetrievalService.retrieve，产出带编号的证据和对应的 tool_call_id |
| AgentScope 与 ChatModel | 包含工具结果的消息 | 模型按结果继续检索或生成终答 |
| AgentChatResponseVO | 完整回复、工具执行记录和本轮证据 | status、answer、toolCalls、sources |

模型请求的参数形如 `{"query":"星河学习项目每周什么时候验收"}`。工具将召回和重排选出的正文编为 `citationId`。同一片段版本在多次检索中复用该编号，回答中的 `[1]` 可以与来源展开项核对。模型下一次调用收到的是 `role=tool` 的结果消息，消息的 `tool_call_id` 对应本次工具请求。

工具在构造时绑定通过校验的用户和知识库。JSON Schema 与执行入口都限制模型参数为 query。每次查库沿用归属、当前索引和向量空间过滤，选取资料使用现有检索配置。文档内容作为工具数据进入模型上下文。

## 停止与排错

- `AGENT_MAX_ITERS` 默认 3，配置范围 1～8。框架限制推理循环，工具另外用同一数值限制本轮实际检索次数，覆盖一次模型回复请求多次检索的情况。
- `AGENT_TIMEOUT_MS` 默认 30000，配置范围 1000～90000。预算覆盖模型、检索与后续回复。超时取消 SDK 订阅并设置检索取消标记，接口返回 504 和 AGENT_TIMEOUT。
- 检索故障以工具错误回给模型，可以在预算内换词或结束。页面记录错误码。已调用工具但整轮没有取得资料时，返回 NO_EVIDENCE。
- AgentScope 会先校验工具参数。遇到模型附加 userId 或 knowledgeBaseId 时，SDK 在进入 Java 工具前拒绝调用，业务层通过工具结果事件补记 AGENT_TOOL_REJECTED。
- ITERATION_LIMIT 表示 SDK 已用完推理迭代。AGENT_MODEL_FAILED 表示模型调用或协议处理失败，先核对配置的地址、模型权限和工具调用支持。工具结果中的失败提示采用固定文本。

2026-11-07 通过 32 个单元测试与 93 组接口及真实 Edge 检查。服务端使用独立的 PostgreSQL、Redis 和本机 Chat Completions 模拟端点，AgentScope 执行实际的工具循环。固定题目验证两次检索的回填、归属、证据不足、故障恢复、迭代和超时。真实提供方的协议兼容与效果留待本机试问。 [完整回归记录](../target/knowledge-test-d5c19f56/result.json) · [HTTP 样例](http/knowledge-requests.http)

接下来用同一个 Toolkit 接一个查询工单状态的 MCP 工具，再进入 Agent 状态保存与恢复。
