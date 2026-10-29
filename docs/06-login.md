# 第 6 步笔记：登录身份与会话归属

这一步建立两个能力：确认请求来自哪个用户；确认这个用户有权使用哪个会话。普通聊天、流式输出和数据库记忆继续共用原来的核心流程。

## 1. 这次学到什么程度

能描述下面四件事即可开始下一步：

1. 密码登录成功后，浏览器用 Cookie 带上会话标识，后端恢复登录身份。
2. 当前用户编号来自后端认证结果，前端不负责决定自己是谁。
3. 读取历史、继续对话、保存消息都需要会话归属校验。
4. 流式线程池任务明确接收 userId，数据库事务只覆盖短时间的数据库操作。

Spring Security 的配置 API 可以查文档或由 AI 协助实现。你需要判断身份从哪里来、在哪里校验、失败应该在哪里结束请求。

## 2. 目录与职责

| 位置 | 职责 |
| --- | --- |
| `system/user/UserRepository` | app_user 的查询和创建 |
| `system/auth/AuthService` | 注册校验、密码哈希、提供登录时查询的用户资料 |
| `system/auth/LoginUser` | 通过认证的用户身份，包含数据库 userId |
| `system/auth/SecurityConfig` | 配置框架的认证、Session、CSRF、登录/退出和访问规则 |
| `system/auth/AuthController` | 注册、当前用户、获取 CSRF 凭证 |
| `chat/ChatController` | 从认证结果取得 userId，接收聊天/会话/历史请求 |
| `chat/ChatService` | 校验输入，组织归属检查、历史读取、模型调用、保存 |
| `chat/memory/ConversationMemoryStore` | 按用户和会话复用本机锁，转换数据库错误 |
| `chat/memory/ChatMemoryRepository` | 带用户条件执行会话和消息 SQL |
| `infra/llm` | 原来的模型请求、结果解析和流式解析；本步骤不修改 |

目前仍是一个 Maven 工程。`system` 开始承接用户和认证职责，为后续对照原项目的模块边界做准备。原项目使用 Sa-Token 等组件；学习版这一步使用 Spring Security，掌握的认证与归属职责相同，配置 API 不要求一模一样。

## 3. 注册过程

页面发送用户名和密码 → AuthController → AuthService 检查输入 → PasswordEncoder 生成 BCrypt 哈希 → UserRepository 写 app_user。

数据库保存 `password_hash`。相同密码多次生成的哈希通常不同，因为包含随机盐。登录时调用编码器的匹配逻辑，不是重新生成一个哈希后直接用字符串相等比较。

注册响应仅包含 `userId`、`username`；LoginUser 和 UserAccount 不直接作为 HTTP 响应。注册成功后需要再登录。

## 4. 为什么没在 AuthController 里看到 login 方法

`POST /api/auth/login` 被 Spring Security 的登录过滤器处理：

1. 读取表单字段 username、password。
2. 通过 AuthService.loadUserByUsername 查询用户。
3. DaoAuthenticationProvider 用 PasswordEncoder 比对密码。
4. 成功后保存认证状态、更换 Session ID，再由 successHandler 返回身份信息。

因此，这个接口用 `application/x-www-form-urlencoded`，聊天接口继续用 JSON。页面已经分别处理这两种请求格式。

LoginUser 继承框架 User，认证完成后框架可以清除身份对象中的密码哈希；数据库中的密码哈希正常保留。

## 5. Cookie、Session、数据库连接与 CSRF

| 名称 | 解决的问题 |
| --- | --- |
| 用户表 app_user | 保存账号与密码哈希 |
| Session | 后端记住某次登录对应哪个用户 |
| JSESSIONID Cookie | 浏览器后续请求带上 Session 标识；不是 userId，也不是密码 |
| CSRF 凭证 | 后端检查修改请求是否持有当前会话的请求凭证 |
| PostgreSQL 连接 | Java 程序通过 JDBC 读写用户和聊天数据 |

浏览器使用同源 fetch，自动携带 Cookie；页面没有把密码或 Session ID 放入 localStorage。Cookie 由后端设置，Session Cookie 为 HttpOnly。

CSRF 凭证从 `GET /api/auth/csrf` 获取，POST 请求在返回的 headerName 对应请求头中携带 token。登录成功或退出后旧凭证失效，之后要重新获取。新页面每次 POST 前都获取当前凭证，不会自动重放失败的写请求。

没有 CSRF 凭证的 POST 可能先得到 **403 CSRF_INVALID**。带有效凭证但未登录访问聊天，得到 **401 AUTH_REQUIRED**。两项检查的目的不同。

本步骤用后端内存 Session，没有引入 Redis。Java 后端重启后需要重新登录；app_user 和聊天记录都在 PostgreSQL 中，所以仍然存在。后续需要让登录状态跨实例或进程保存时，再接入集中会话存储。

## 6. 聊天时身份如何传下去

Controller 的关键变化：

```java
public ChatResponse chat(@AuthenticationPrincipal LoginUser user,
                         @RequestBody ChatRequest request) {
    // 省略输入提取。
    return new ChatResponse(chatService.chat(user.userId(), id, question));
}
```

`@AuthenticationPrincipal` 表示取出框架已经认证的身份。ChatRequest 仍只定义 conversationId 和 question，没有让客户端填写 userId。

即使客户端另外塞入一个 userId 字段，服务端使用的仍然是认证结果中的 userId。模型请求里不需要放密码、Session Cookie 或数据库账号。

## 7. 流式任务为什么要明确传 userId

```java
long userId = user.userId();
chatService.prepareConversation(userId, input.conversationId());

streamExecutor.submit(() -> {
    // 省略事件发送与错误处理。
    chatService.streamChat(userId, input.conversationId(), input.question(), onDelta, cancellation);
});
```

请求处理线程先确认身份，然后把 userId 当普通参数传入线程池任务。默认的线程上下文不会因为写了一个 lambda 就自动跟随所有自建线程池传播；这里避免在 worker 中重新依赖请求线程的身份上下文。

`long userId` 是这一次请求捕获的值，账号 B 的另一次请求会捕获 B 自己的值。数据库查询还会继续检查 userId 与会话归属。

异步分派的放行只用于已经进入应用的容器 SSE/错误分派；客户端首次发送的 `/api/**` 请求仍要求认证。

## 8. 归属校验放在哪里

`ensureOwned(userId, conversationId)` 使用一个短事务：

1. 尝试创建带 user_id 的会话；同名会话已存在则不覆盖。
2. 查询这个编号的拥有者。
3. 只有拥有者等于当前 userId 才继续；否则抛出 404。

不同用户同时使用一个全新编号时，数据库主键冲突规则决定只有一个拥有者。另一个用户被拦住，不能覆盖 user_id，也不会调用模型。

归属校验同时出现在以下边界：

- 读取历史前检查；查询 SQL 仍带 `c.user_id = ?`。
- 流式接口在创建 SseEmitter 前先确定归属，通常能直接返回 404 JSON。
- worker 开始处理时再次检查。
- 保存时再检查；消息 INSERT 自身通过拥有者条件选择目标会话。
- 会话列表只查询当前用户的会话。

这是后端检查，隐藏前端按钮或让用户猜不到编号都不能替代它。

不存在、他人的会话、尚未绑定的旧会话统一返回 `404 CONVERSATION_NOT_FOUND`，不提供消息内容。

## 9. 事务与锁继续各管一件事

| 机制 | 本步骤的作用 |
| --- | --- |
| ensureOwned 的事务 | 创建会话并确认拥有者 |
| appendTurn 的事务 | 本轮 user 与 assistant 两条消息一起提交或回滚 |
| synchronized(conversation) | 当前 Java 进程中，同一用户同一会话的生成按顺序进行 |

模型 HTTP 调用仍然在数据库事务之外。

会话归属必须在调用模型前确定，所以新会话可能先出现一行空会话。如果模型出错，这行会话可以保留，本轮消息不会保存。普通回答或完整流式回答成功后，才进入 appendTurn 的写事务。

事务回滚不会撤回已经发到浏览器的文字。保存失败时流式页面收到 error，而不是 done。连接中断、取消与数据库提交也不是一个原子操作；结果不确定时先查看历史，再决定是否重试。退出登录阻止后续请求，不承诺撤销所有已经开始的服务端任务。

## 10. 旧数据迁移为什么不自动认领

D5 的 chat_conversation 还没有 user_id。只在 CREATE TABLE 里新增字段不会修改已经存在的表，所以本次 SQL 使用：

```sql
ALTER TABLE chat_conversation
ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES app_user(id);
```

旧行的 user_id 保留 NULL，原消息保持不变。只有你在数据库中明确确认并绑定的旧会话，才会出现在指定用户的列表里。操作示例见 README。

当前 conversation_id 保持全局主键。B 使用 A 的同名编号会失败；B 创建新会话应该使用新编号。页面自动生成随机编号。

## 11. 接口清单

| 方法与路径 | 登录要求 | 请求/结果 |
| --- | --- | --- |
| GET /api/auth/csrf | 否 | 返回 headerName 和 token；必要时创建匿名 Session |
| POST /api/auth/register | 否，要求 CSRF | JSON 用户名、密码；返回 201 |
| POST /api/auth/login | 否，要求 CSRF | 表单用户名、密码；成功后带 Session Cookie |
| GET /api/auth/me | 是 | 当前 userId、username |
| POST /api/auth/logout | 要求 CSRF | 清除当前登录 Session |
| POST /api/chat | 是，要求 CSRF | 原 JSON 请求；返回 answer |
| POST /api/chat/stream | 是，要求 CSRF | 原 JSON 请求；start/delta/done/error |
| GET /api/conversations | 是 | 自己最近 100 个会话，包括已创建的空会话 |
| GET /api/conversations/{id}/messages | 是且拥有会话 | 最近 12 条消息，旧到新顺序 |

查看历史接口暂时沿用模型上下文的最近 6 轮窗口，完整分页历史以后再扩展。更早记录并未删除。

## 12. 常见错误

| 现象 | 优先检查 |
| --- | --- |
| 找不到 security 包 | pom 是否增加依赖并刷新 Maven |
| chat/streamChat/getOrCreate 参数不匹配 | ChatController、ChatService、两个 memory 文件是否一起替换 |
| AUTH_REQUIRED | 是否使用同一浏览器或接口工具登录；后端重启后是否重新登录 |
| CSRF_INVALID | 登录或退出后重新取凭证；Cookie 与 token 必须来自同一个会话 |
| USERNAME_TAKEN | 直接登录或换用户名，不需要重建用户表 |
| AUTH_INVALID_CREDENTIALS | 用户名、密码是否正确；密码保留大小写和空格 |
| AUTH_DATABASE_UNAVAILABLE | 数据库容器、连接配置和 app_user 表是否正常 |
| CONVERSATION_NOT_FOUND | 是否属于当前账号；是否为尚未手动绑定的 D5 旧会话 |
| 旧 stream-test.html 发送失败 | 改用新增 login-test.html；旧页面尚未实现登录与 CSRF 请求 |
| 两个普通标签页一起切换了账号 | 同一浏览器的标签共享 Cookie；第二人用无痕窗口或另一个浏览器 |
| localhost 登录后改 127.0.0.1 看不到登录状态 | 两个主机名的 Cookie 不共用，测试时固定同一地址 |

Apifox 中先 GET csrf 并保留 Cookie；注册用 JSON，登录用表单，再 GET csrf，之后给聊天 POST 设置 X-CSRF-TOKEN。不要把自己的真实密码或模型密钥填进准备提交到 GitHub 的测试文件。

## 13. 验证记录

本增量使用现有 Spring Boot 4.1.0 工程和 Java 17 实际编译打包；依赖解析到 Spring Security 7.1.0。你本机的 Java 21 可继续沿用。

验证使用实际启动的 Spring Boot、JDBC、Security 过滤器、Cookie/Session、事务代理，以及本地模拟模型服务。数据库运行时为 PGlite 0.5.12 的 PostgreSQL 18.6 WASM，通过 PostgreSQL 协议与 JDBC 连接，连接池设为 1。

共通过 14 组验证：D5 数据迁移、注册校验与密码哈希、登录/退出、Session ID 更换、CSRF、用户编号伪造、会话列表/历史/普通聊天/流式聊天隔离、两个账号争抢新编号、旧记录明确绑定、保存失败回滚、历史窗口、同会话并发、数据库错误与后端重启。

另外使用实际 Chromium 浏览器操作新测试页，完成注册、登录、流式回答、历史读取、刷新后保留登录、退出、切换第二个账号、越权读取/续聊拦截与普通聊天；桌面和 390px 手机宽度截图已检查，页面无横向溢出，未出现 JavaScript 页面异常。

测试环境未连接你的千问账号，也没有替你操作本机 Windows/Docker。合并后仍按 README 在你自己的 PostgreSQL 与千问环境验收。原生 PostgreSQL 多连接、多实例并发和公网部署不属于这次本地验证的覆盖范围。

## 14. 官方参考

- Spring Security 表单认证：<https://docs.spring.io/spring-security/reference/7.0/servlet/authentication/passwords/form.html>
- 密码存储与 PasswordEncoder：<https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>
- Session 与登录时更换 Session ID：<https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html>
- CSRF 与凭证刷新：<https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html>
- Spring 事务注解：<https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html>

下一轮讲解从 SecurityConfig 的入口规则开始，再跟到 Controller 中的 userId，最后看 Repository 的 WHERE 条件。先用页面把功能验证成功，再逐段读代码。
