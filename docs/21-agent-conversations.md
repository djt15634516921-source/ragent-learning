# Agent 会话与 PostgreSQL 状态恢复

Agent 会话可以连续提问。服务重启后，选择同一会话，AgentScope 会加载上一轮提交的上下文，包括模型发出的工具请求和对应的工具结果。页面另用可回放的消息展示问题、答案、工具记录与来源。

| 原版功能 | 学习版实现 |
| --- | --- |
| ReActAgentProvider 组装并复用 Agent | Provider 延迟构建 AgentScope 2.0.2 实例，统一注入模型、工具和状态存储 |
| AgentToolCatalog 注册工具 | 目录集中注册 search_knowledge，当前用户和知识库由每次请求的 RuntimeContext 提供 |
| PgAgentStateStore 按用户和会话保存框架状态 | AgentStateStore 使用 SDK 的状态编码，从 PostgreSQL 按 userId、sessionId、stateKey 加载 |
| AgentConversationService 管理会话和消息 | 创建、列表、游标历史和删除，问答与状态在一个短事务内提交 |
| AgentRunGate 管理会话并发 | 本机同一会话互斥，数据库提交检查会话版本 |

对应原项目提交 d9e5fc783ff7195d789d829fb35bdbfef928dbcc。 [ReActAgentProvider](https://github.com/nageoffer/ragent/blob/d9e5fc783ff7195d789d829fb35bdbfef928dbcc/agent/src/main/java/com/nageoffer/ai/ragent/agent/config/ReActAgentProvider.java) · [AgentToolCatalog](https://github.com/nageoffer/ragent/blob/d9e5fc783ff7195d789d829fb35bdbfef928dbcc/agent/src/main/java/com/nageoffer/ai/ragent/agent/tool/AgentToolCatalog.java) · [PgAgentStateStore](https://github.com/nageoffer/ragent/blob/d9e5fc783ff7195d789d829fb35bdbfef928dbcc/agent/src/main/java/com/nageoffer/ai/ragent/agent/state/PgAgentStateStore.java) · [原版建表](https://github.com/nageoffer/ragent/blob/d9e5fc783ff7195d789d829fb35bdbfef928dbcc/resources/database/upgrades/v2.0.0/260812_agent_engine.sql)

## 一轮执行和提交

1. `AgentChatController.chat` 取得登录用户、请求中的会话 ID、路径上的知识库 ID 和原问题。
2. `AgentChatServiceImpl.chat` 获取会话运行许可，校验归属和知识库绑定，通过 Provider 获取共享 Agent。每轮用独立 RuntimeContext 传递 userId、sessionId 和本轮工具范围。
3. `PgAgentStateStore.open` 在模型调用前检查并读取已提交的 AgentState。AgentScope 调用 store.get 时得到本轮状态对象，后续模型请求由框架携带历史工具调用与结果。
4. `KnowledgeSearchTool` 从 RuntimeContext 取本轮范围，复用现有检索。每轮来源编号从 1 开始，消息历史保留当时的来源快照。
5. SDK 保存回调标记本轮状态可提交。`AgentConversationRepository.appendTurn` 校验版本并在一个事务中写入会话更新、问答及工具快照、SDK 状态。

| 表 | 内容 |
| --- | --- |
| t_agent_conversation | 会话编号、所有者、绑定知识库、标题、时间和提交版本 |
| t_agent_message | 按消息编号排序的用户问题与最终回答，回答附工具记录和来源 |
| t_agent_state | AgentScope 原生 JSONB 状态，当前框架键为 agent_state |

采用 V21 增量 SQL。会话 ID 由服务端分配，消息和状态以会话及所有者为外键。删除会话时这两类记录随会话级联删除。

## 并发和失败

- 同一实例的同一会话只运行一轮。进行中重复提问或删除返回 409。不同会话各自传递工具范围，可并行执行。
- 数据库版本检查在写入前执行。若会话已被其他实例更新或知识库已删除，本轮提交被拒绝。应用互斥范围是单实例。
- 模型超时、执行失败或写库失败后，下次读取上一个已提交状态。坏状态或已提交会话缺少状态时返回 AGENT_STATE_UNAVAILABLE。
- SDK 启用了 pending tool recovery。当前持久化边界是完成的轮次，重启会从最近一次完整提交继续。执行中的轮次需要重新提问。

## 使用

在 IDEA 重启 bootstrap 后进入知识库页，选择已经入库的资料，点击「新建 Agent 会话」。同一会话先问「星河项目每周什么时候验收？」，再问「那青禾项目呢？」。重启后从下拉列表选择原会话，可以展开历史，再继续提问。

`POST /api/knowledge-bases/{id}/agent/chat` 接受可选的 `conversationId`，未提供时服务创建一条独立会话。保存后返回会话 ID。历史接口用 `beforeId` 加载更早消息。已有聊天模型配置用于工具调用。 [HTTP 样例](http/knowledge-requests.http)

## 验收

32 个单元测试和 100 组接口及 Edge 检查通过。隔离 PostgreSQL、Redis 和本机模拟模型验证了同会话追问、进程重启后的工具上下文、跨用户隔离、同会话互斥、写入回滚、级联删除和页面历史回放。真实模型的工具选择效果留待本机试问。 [完整回归记录](../target/knowledge-test-341c64c9/result.json)
