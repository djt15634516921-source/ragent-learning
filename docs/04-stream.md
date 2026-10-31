# 第四步：真实模型流式输出与 SSE

> 多模块更新：当前目录、构建与启动见[第 11 步](11-maven-modules.md)。以下为历史课程，旧增量包不能覆盖当前模块目录。

> 课程历史笔记。当前启动、认证和数据访问以 README 与第 10 步为准：所有业务 API 需 Bearer，Service 为接口＋Impl，数据库访问使用 Mapper。下方推导示例保留用于理解演进。

日期：2026-10-25。前置：第三步会话记忆已经在本机验收。

本次先完成流式输出并运行，再集中讲解基础聊天链路。后续仍按 30 天计划进入 PostgreSQL 持久化、文档入库、RAG 和 Agent。

## 1. 在 IDEA 应用增量包

1. 停止当前应用。
2. 解压 `ragent-learning-step04-stream-update.zip`，把里面的 `src`、`docs` 和 `stream-requests.http` 合并复制到现有工程 `D:\jastudy\idea\ragent-learning`，同名文件选择替换。
3. 确认代码位于 `ragent-learning\src\main\java\...`，测试页位于 `ragent-learning\src\main\resources\static\stream-test.html`。
4. 用原来的 IDEA 运行配置启动，继续使用已成功的千问环境变量与 18081 端口。
5. 浏览器打开 [流式测试页](http://localhost:18081/stream-test.html)，点击“发送并观察回答”。

这是当前 Git 工程的增量。现有 POM、application.yaml、ChatRequest、ChatResponse、ModelResponseParser、ConversationMemoryStore、hello 类继续沿用。

## 2. 最短验收路径

页面显示回答逐段增加，状态显示接收到的片段数量，最后显示“已完成并保存本轮”。这是真实模型返回的文字片段，代码没有先获取整段回答再人为延迟显示。

实际片段大小与速度由模型和网络决定，可能一段包含多个字，也可能很快结束。用稍长的问题更容易观察；“逐字”不是协议保证。

接着在页面使用同一编号发送：

```text
我叫小杜，我的学习口令是青竹47。请记住。
```

等出现完成状态，再问：

```text
我叫什么？我的学习口令是什么？
```

检查名字和口令正确；再换一个从未使用的编号，确认不携带旧历史。也可以在 Apifox 用普通 `/api/chat` 询问同一会话的口令，确认两个接口共用历史。

页面使用 POST + JSON，通过 fetch 消费响应流。浏览器地址栏直接打开 `/api/chat/stream` 会发 GET，不能代替 POST 调用；打开 `/stream-test.html` 即可操作。

## 3. Apifox 请求格式

新接口：

```text
POST http://localhost:18081/api/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

```json
{
  "conversationId": "stream-001",
  "question": "请分成八步解释一次 Java 后端调用大模型的流程，每步写两句话。"
}
```

本次响应是 SSE，不再是一个单独的 ChatResponse JSON。事件格式示意：

```text
event: start
data: {"conversationId":"stream-001"}

event: delta
data: {"content":"你好"}

event: delta
data: {"content":"，我是学习助手。"}

event: done
data: {"answer":"你好，我是学习助手。"}

```

`delta.content` 是新增文字，调用方逐段追加；`done.answer` 是完整答案。最终答案去掉首尾空白，与历史中保存的内容一致。

连接建立后发生的业务错误，例如模型鉴权失败，会通过 `error` 事件返回 code 和 message。HTTP 状态可能已经是 200，所以成功必须看正常完成事件或页面完成状态，不能只看状态码。

非法入参在建立 SSE 前拦截，例如有编号但 question 为空时，仍返回 HTTP 400 JSON。错误处理器显式指定 JSON Content-Type，避免 SSE 的媒体类型影响普通错误响应。

## 4. 文件与职责

| 文件 | 操作 | 作用 |
|---|---|---|
| chat/ChatController.java | 替换 | 保留普通接口，新增流式接口，发送四类 SSE 事件 |
| chat/ChatService.java | 替换 | 两种调用共用校验和历史；完整生成后再保存 |
| chat/ChatStreamConfig.java | 新增 | 提供有限后台线程和等待队列 |
| infra/llm/ModelClient.java | 替换 | 普通调用继续可用；流式调用发送 stream=true 并读取响应 |
| infra/llm/ModelStreamParser.java | 新增 | 按 SSE 事件解析 choices[0].delta.content，拼接最终答案 |
| infra/llm/StreamCancellation.java | 新增 | 将超时或断开信号传递到响应读取过程 |
| common/ChatExceptionHandler.java | 替换 | 在流式建立前也能返回明确的 JSON 参数错误 |
| resources/static/stream-test.html | 新增 | 浏览器测试页，逐段显示、计数并处理完成和错误 |

包中 Java 文件相对 `src/main/java/com/dujiteng/ragentlearning`。本次共七个 Java 文件和一个 HTML 测试页。

## 5. 跑通后集中讲的核心流程

Controller 先校验输入并建立 SseEmitter，把实际读取放进后台线程。Service 找到会话，拿到历史，再调用 ModelClient。

ModelClient 发送 system + 历史 + 本次 user，设置 stream=true。通过 RestClient.exchange 访问原始响应，在回调里持续读取；exchange 不走 retrieve 的自动状态处理，因此代码自行校验 HTTP 状态与 SSE Content-Type。

ModelStreamParser 读取 data 事件，解析 `choices[0].delta.content`。每段文字立刻交给 Controller 的回调；Controller 用 emitter.send 发出 delta，浏览器追加显示。

收到模型 `[DONE]` 且正文有效，模型方法返回完整答案；Service 保存一整轮，Controller 再发送 done。生成中断、数据不合法或异常结束时，不保存半条回答。

可以先看 Service 中这一段：

```java
String answer = modelClient.stream(
        SYSTEM_PROMPT, conversation.history(), input.question(), onDelta, cancellation);
cancellation.check();
conversation.appendTurn(input.question(), answer);
return answer;
```

普通调用返回一个完整字符串；流式调用在过程中多次调用 onDelta，同时在结束时返回完整字符串。这样既能立即展示，也能只保存一条完整回答。

StreamCancellation、线程池、原子变量和容器回调属于本次配套实现。集中讲解会先抓业务数据流，再解释这些对象解决的具体问题，不要求先闭卷写出来。

## 6. 对应原 Ragent 的位置

- 学习工程 ModelClient、ModelStreamParser 对应原项目 infra-ai 的模型适配与 OpenAIStyleSseParser 职责。原解析器使用 Gson，本学习实现使用当前 Boot 4 默认的 Jackson 3（tools.jackson），没有引入新的 JSON 依赖。
- ChatController 的 SseEmitter 与原项目 RAGChatController、AgentChatController 中 SSE 入口的思路对应。
- ChatService 仍属于业务编排：组织历史、模型调用与保存。后续检索和 Agent 会在这里进一步分出清晰边界。
- 当前取消信号仅用于基础流的收尾；原项目完整任务编号、主动停止接口、用户归属和 Redis 运行占用控制，按后续计划实现。

## 7. 实现边界

- 仍是内存历史，最近六轮，重启清空；编号本身还不是用户权限。本次不接数据库和登录。
- 同一会话的普通/流式请求共用一个会话锁，按获得锁的顺序处理。
- 背景线程最多四个，等待队列八项，队列满时返回 503 CHAT_BUSY。SSE 生命周期上限为 90 秒。
- 已取得的上游响应体会在超时/断开回调中关闭；尚在建立连接时同时依靠中断和原 HTTP 超时。没有新增面向用户的“停止生成”接口。
- 生成过程中失败或取消，不保存部分答案。完整答案已经保存后，传输 done 仍可能失败；消息保存与客户端收到确认不构成跨网络原子操作。
- 必须收到 `[DONE]` 才把上游流当作完整结束；普通 EOF、输出截断或过滤等异常结束会报错。正文上限 64000 个 Java 字符，单事件上限 262144。
- 页面显示增量文字，不展示 reasoning_content，不接工具调用。模型需使用当前可用的 OpenAI 兼容文本聊天接口。

## 8. 实际验证记录

本地 Java 17 编译并运行了真实流式解析与 Service 逻辑，JSON 解码使用固定解码夹具、模型客户端使用测试替身、Spring 注解使用测试占位。通过 21 项流式行为检查：事件分帧、BOM/CRLF、多行 data、独立空格/换行、结束标记、异常 EOF、无效事件、异常结束、长度、取消、关闭响应体、只保存完整轮次和两种模式共用历史。

原会话记忆七组行为回归通过。测试页实际使用的 SSE 分帧函数通过六项 Node 检查，包括把 UTF-8 中文按单字节网络块拆开。七个更新的 Java 源文件均通过 Java 17 语法解析。

当前环境没有完整 Maven/Spring 依赖缓存，因此没有执行完整 Spring Boot 构建、Servlet 容器联调或真实千问请求。上述检查不等于这些运行验证；本机 IDEA 启动和浏览器真实调用是本步骤接下来的验收。

## 9. 验收后用 IDEA 提交

Ctrl+K，检查七个 Java 文件、HTML 测试页、文档和请求文件，填写：

```text
feat: 添加真实模型流式输出和SSE测试页
```

选择 Commit and Push。随后集中讲解基础聊天链路，再进入 PostgreSQL 持久化。

## 官方参考

- [阿里云百炼：流式输出](https://help.aliyun.com/zh/model-studio/stream)
- [Spring MVC：异步请求与 SSE](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
- [Spring RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)
- [Spring Boot：JSON 支持](https://docs.spring.io/spring-boot/reference/features/json.html)
