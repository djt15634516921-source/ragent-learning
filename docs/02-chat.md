# 第 2 步：在现有工程上接通非流式聊天

## 1. 复制到当前工程

这个压缩包包含当前 `ragent-learning` 的新增文件。把包内 `src`、`docs`、`chat-requests.http` 复制到 `D:\jastudy\idea\ragent-learning`。合并已有的 `src` 目录，确认新的 `chat`、`infra`、`common` 包位于 `com.dujiteng.ragentlearning` 下。

没有提供新的 `pom.xml`、启动类、hello 代码、application.yaml 或 Git 目录；当前工程继续使用原来的这些文件。新的 Java 类在启动类包的子包中，会被组件扫描找到。

本次使用 Spring 自带的同步 HTTP 客户端 `RestClient`；当前 Web MVC starter 已提供所需基础依赖，POM 无需新增依赖。

## 2. 追加配置

打开 `src/main/resources/application.yaml`，在文件末尾追加以下内容，`llm` 与 `spring`、`server` 同级：

```yaml
llm:
  chat-url: ${LLM_CHAT_URL:}
  model: ${LLM_MODEL:}
  api-key: ${LLM_API_KEY:}
```

冒号后的环境变量默认值为空。未配置时应用仍能启动；调用聊天接口会得到明确的 `MODEL_CONFIG_MISSING`，不会发送模型请求。

## 3. 在 IDEA 运行配置里填写环境变量

打开 `RagentLearningApplication` 的 Run/Debug Configuration。找到 Environment variables（环境变量）；有些版本需要先在 Modify options 中显示这一项。填写：

| 变量 | 应填内容 |
|---|---|
| `LLM_CHAT_URL` | 原项目已经调用成功的完整 Chat Completions HTTP 地址 |
| `LLM_MODEL` | 同一平台上已经调用成功的模型 ID |
| `LLM_API_KEY` | 这个平台、账号/地域对应的 API Key |

地址要包含 `/chat/completions`，因为当前客户端直接请求这个地址。只填 SDK 的 base URL（例如只到 `/v1`）会请求错误的路径。

请保持这三项来自同一平台配置，Key 填在本机运行环境里。不要把真实 Key 放进可提交的 YAML、HTTP 请求文件或 README。IDEA 项目配置目录已由第一课 `.gitignore` 忽略；若主动开启“将运行配置存为项目文件”，保存和提交前要检查其中是否含有 Key。

可对照的地址示例（模型名称使用你账号实际可用的值）：

- 硅基流动国内 Chat Completions：`https://api.siliconflow.cn/v1/chat/completions`。
- 百炼北京旧域名：`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`；也可沿用原项目已验证的工作空间专属域名。其他地域请用原项目对应地域的地址。

这个初版支持 Bearer 鉴权、`model`/`messages` 请求以及 `choices[0].message.content` 文本响应。使用其他协议的模型服务，需要对应的适配实现。

配置完成后，停止旧进程，再启动 `RagentLearningApplication`。环境变量在进程启动时读取。

## 4. 用 PowerShell 发出 POST 请求

在 IDEA 下方 Terminal 执行：

```powershell
$chatRequestBody = @{ question = "请用三句话解释什么是 RAG。" } | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:18081/api/chat" -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($chatRequestBody))
```

预期返回一个带 `answer` 字段的对象，其中的回答来自真实模型。PowerShell 可能按表格显示字段；如需查看 JSON，在命令末尾加 `| ConvertTo-Json`。

本接口是 POST；PowerShell、IDEA HTTP Client 或其他接口工具可以发送这个请求。浏览器地址栏通常发送 GET 请求，所以直接在地址栏打开 `/api/chat` 不会触发这次聊天方法。

## 5. 再验证一个失败场景

空问题：

```powershell
$emptyQuestionBody = @{ question = "   " } | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:18081/api/chat" -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($emptyQuestionBody))
```

预期 HTTP 400，错误码 `QUESTION_REQUIRED`。PowerShell 在非 2xx 状态下会显示错误；查看错误详情中的 JSON。这次错误应在业务校验阶段产生，不会调用模型。

## 6. 需要仔细看的三段代码

| 入口 | 职责 | 你要观察的内容 |
|---|---|---|
| `ChatController.chat` | 接收 JSON，返回回答 | `question` 怎样进入 Java，结果怎样成为 JSON |
| `ChatService.chat` | 校验、整理、组织业务 | 空问题和超长问题在何处结束 |
| `ModelClient.chat` | 封装远程调用 | URL、Authorization、model、messages、stream，以及响应提取 |

`ChatRequest` 和 `ChatResponse` 是简单数据对象，使用 Java record 定义。`ChatExceptionHandler` 统一返回可预期的错误，`ModelResponseParser` 提取模型响应中的文本。

主调用链：接口工具 → `ChatController` → `ChatService` → `ModelClient` → 模型平台 → 按相反方向返回答案。

这一步接上了原项目 `infra-ai` 承担的模型接入职责。原项目使用 OkHttp 并进一步封装供应商、模型路由和流式解析；当前版本先用现有 Spring HTTP 能力跑通一条非流式请求。以后可以在模型适配层内更换 HTTP 实现，Controller 和业务层继续依赖该职责边界。

## 7. 排错时按错误码找阶段

| 错误码 | 检查方向 |
|---|---|
| `QUESTION_REQUIRED` / `QUESTION_TOO_LONG` | 请求参数 |
| `INVALID_JSON` | 请求体是否为合法 JSON |
| `MODEL_CONFIG_MISSING` | 运行配置中的三个环境变量，以及是否重启 |
| `MODEL_URL_INVALID` | 地址格式，是否包含完整 HTTP(S) URL |
| `MODEL_AUTH_FAILED` | Key、平台、地域和模型权限是否匹配 |
| `MODEL_RATE_LIMITED` | 平台配额、并发与限流提示 |
| `MODEL_HTTP_ERROR` | 提示中的上游 HTTP 状态；地址、模型 ID、平台状态 |
| `MODEL_CONNECTION_FAILED` | 网络、DNS、TLS 和连接/响应超时 |
| `MODEL_RESPONSE_INVALID` / `MODEL_RESPONSE_UNREADABLE` | 协议、非流式参数、响应是否为 JSON |
| `MODEL_ANSWER_EMPTY` | 模型是否返回了最终文本，推理与输出参数是否合适 |

响应不会原样回传平台错误体或密钥。当前只支持文本聊天，不包含图片、工具调用、RAG 或历史记忆。

## 8. 验收后再提交

- [ ] 原 `/api/hello` 仍可访问。
- [ ] 正常问题得到真实模型回答。
- [ ] 空问题得到明确的参数错误。
- [ ] 能指出请求和模型调用分别在哪个类发生。
- [ ] 已检查变更中没有真实 Key。

在终端执行，逐步查看结果：

```powershell
git status
git diff
git add src docs chat-requests.http
git diff --cached --stat
git commit -m "feat: 接入非流式模型聊天"
git push
```

## 9. 生成与验证范围

已核对 Spring RestClient 和模型平台 Chat Completions 文档；响应解析逻辑使用本机 Java 编译并检查成功和畸形响应场景。当前生成环境没有 Maven，尚未进行完整 Spring Boot 构建或真实模型调用；需要在你已经能运行的 IDEA 工程中完成上述验收。

参考资料：

- https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- https://docs.siliconflow.cn/docs/api/chat-completions-post
- https://help.aliyun.com/en/model-studio/qwen-api-via-openai-chat-completions
