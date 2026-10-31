# 第三步：内存版多轮会话

> 多模块更新：当前目录、构建与启动见[第 11 步](11-maven-modules.md)。以下为历史课程，旧增量包不能覆盖当前模块目录。

> 课程历史笔记。当前启动、认证和数据访问以 README 与第 10 步为准：所有业务 API 需 Bearer，Service 为接口＋Impl，数据库访问使用 Mapper。下方推导示例保留用于理解演进。

目标：在已经运行成功的非流式聊天接口中，按 conversationId 保存和携带历史对话。

## 应用修改

1. 在 IDEA 停止当前运行的应用。
2. 解压增量包，把里面的 `src`、`docs` 文件夹和 `memory-requests.http` 合并复制到现有项目 `D:\jastudy\idea\ragent-learning`，同名文件选择替换。确认合并后路径是 `ragent-learning\src\main\java\...`。
3. 这次包含六个 Java 文件：五个替换文件、一个新增文件。端口、POM、已有模型配置、API Key、ChatResponse、ModelResponseParser 和 hello 类继续使用当前工程中的版本。
4. 使用原来的 IDEA 运行配置启动，保留已经填好的三个环境变量。
5. 在 Apifox 仍然使用 `POST http://localhost:18081/api/chat`，Body 选择 JSON；每次都填写 `conversationId` 和 `question`。

## 改动和职责

| 文件（相对 src/main/java/com/dujiteng/ragentlearning） | 操作 | 职责 |
| --- | --- | --- |
| chat/dto/ChatRequest.java | 替换 | 接收会话编号和问题 |
| chat/ChatController.java | 替换 | 把两个输入传给 Service，包装 ChatResponse |
| chat/ChatService.java | 替换 | 校验输入，读历史，调模型，成功后保存 |
| chat/memory/ConversationMemoryStore.java | 新增 | 一个编号对应一份历史，保留最近六轮 |
| infra/llm/ModelClient.java | 替换 | 组装 system + 历史 + 本次 user 问题，发送 HTTP 请求 |
| common/ChatExceptionHandler.java | 替换 | 保留原有错误处理，仅更新非法 JSON 提示中的请求示例 |

ModelClient 的 chat 方法从两个参数变为三个：`chat(systemPrompt, history, question)`。ChatService 的 chat 方法则是 `chat(conversationId, question)`。它们是不同类中的不同方法。

## 先看懂 Service 的这几行

```java
Conversation conversation = memoryStore.getOrCreate(normalizedId);
synchronized (conversation) {
    String answer = modelClient.chat(SYSTEM_PROMPT, conversation.history(), normalized);
    conversation.appendTurn(normalized, answer);
    return answer;
}
```

- `getOrCreate`：根据编号找到会话；第一次使用这个编号时，新建一份空会话。
- `history()`：拿到已成功完成的历史快照，这里还没有本次问题。
- `modelClient.chat(...)`：发送系统提示词、历史和当前问题，等待模型回答。
- `appendTurn(...)`：收到有效回答后，保存这一问一答。调用模型抛出异常时，这一行不会执行。
- `return answer`：把回答返回 Controller；Controller 仍然用 `new ChatResponse(answer)` 包装它。
- `synchronized (conversation)`：同一会话一次只处理一条请求，让“读取 -> 模型调用 -> 保存”成为连续的流程；不同会话使用不同的会话对象。这里按获得锁的先后处理，不保证网络到达顺序。

构造方法中新增的 `ConversationMemoryStore memoryStore` 由 Spring 注入。我们编写构造方法声明需要什么对象，Spring 创建对象并把它们传进来。

## 请求中的历史如何变化

第一轮发给模型：system、当前 user。

第一轮成功后，Java 内存中保存：user、assistant。

第二轮发给模型：system、上一轮 user、上一轮 assistant、本次 user。

第二轮成功后，Java 内存中保存两轮 user/assistant。system 不保存在历史里，而是在每次模型请求开头添加一次。

每轮保存两条消息。超过六轮时，成对删除最早的问题和回答。发送当前问题时，最多携带六轮已完成的历史，另加 system 和当前 user；六轮限制不是 Token 数限制，也不是整个会话生命周期的总轮数限制。

## Apifox 验收

按顺序发送根目录 `memory-requests.http` 中第 1、2、3 个请求，也可以直接在 IDEA 点击每条请求旁边的绿色运行按钮。

1. `study-001`：告诉模型“我叫小杜，我的学习口令是青竹47”。等到返回 HTTP 200。
2. 同一个 `study-001`：问名字和口令。检查回答是否包含“小杜”和“青竹47”。
3. 换成从未用过的 `study-002`：只问名字和口令。这次请求不应携带 `study-001` 的历史。

模型回答有随机性。名字和口令测试用于观察功能；需要精确判断是否串会话时，应检查实际发给模型的 messages。

旧请求如果只有 question，会得到 `400 CONVERSATION_ID_REQUIRED`。这说明新接口要求填写会话编号；不要把它误判为千问调用失败。空问题测试也需要同时提供有效的 conversationId。

## 精确检查历史（需要时再调试）

在 `ModelClient.java` 的 `Map<String, Object> payload = Map.of(...)` 这一行打断点。用原来的运行配置进入 Debug，再发请求。

- 第一轮：局部变量 messages 应有两条：system、user。
- 第一轮成功后，同一会话的第二轮：应有四条：system、user、assistant、user。
- 一个全新编号的第一轮：应只有 system、user。

用 F9 放行，Apifox 才能继续收到回答。不要为检查历史打印 API Key。

## 这一版的边界

历史存在当前 Java 进程里，重启应用后清空；修改 Java 文件并重启同样会丢失历史。会话编号大小写有区别，首尾空白会被去掉。编号必填，最多 128 个字符；问题去掉首尾空白后最多 8000 个字符。

这是单机学习版本：按编号区分会话，没有登录用户权限校验，没有会话过期和会话总数限制。编号相同就会访问同一份历史。后面增加用户身份和持久化时，再一起完善这些规则。现在使用本机测试即可。

这一版仍然使用 `stream: false`。会话历史属于请求内容，流式输出属于响应传输方式，可以先把会话记忆验证清楚，再增加流式接口。

## 验证记录

提供者本地使用 Java 17 编译了实际的 ChatService、ConversationMemoryStore、ChatRequest 和已有 ChatApiException，通过七组行为检查：多轮传递、会话隔离和快照、调用失败不保存、按整轮裁剪、非法输入拦截、同会话串行、不同会话并行。模型客户端采用测试替身，Spring 注解采用测试占位，测试文件不包含在这个增量包内。

此记录不等于完整 Spring Boot 编译或真实千问联调；完整启动和真实回答仍需在你的 IDEA 与 Apifox 中验证。

## 验收通过后，用 IDEA 提交

按 Ctrl+K，检查并勾选本次变更的 Java 文件、文档及请求示例，在提交说明框填写：`feat: 添加按会话编号隔离的内存聊天记录`，选择 Commit and Push。

## 官方资料

- [阿里云百炼：多轮对话](https://help.aliyun.com/zh/model-studio/multi-round-conversation)
- [Java 17 ConcurrentHashMap](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html)
