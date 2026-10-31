# MyBatis-Plus、Sa-Token 迁移与架构说明

## 结果与保护范围

基于 `D:\jastudy\idea\ragent-learning` 的最新工作区实施，`D:\jastudy\idea\ragent` 仅作参考。保留已存在的未提交修改、原始压缩包（现归档 `docs/archive/main.zip`）、自定义聊天提示词、ModelClient 修改、文档解析正文/状态/失败原因。迁移前副本在被 Git 忽略的 `data/migration-baseline-20261030`。

数据库/认证迁移当时保持单工程；当前已按[第 11 步](11-maven-modules.md)拆为五个 Maven 模块，Java 17 编译目标、Spring Boot 4.1.0、PostgreSQL 不变。选择 MyBatis-Plus 3.5.18 的 Boot 4 starter，官方说明 Boot 4 专用 starter 自 3.5.13 提供。[MyBatis-Plus 安装说明](https://baomidou.com/getting-started/)

Sa-Token 固定 1.46.0，使用 spring-boot4-starter、redis-template、jackson3；Boot 4 支持从 1.45 引入，Jackson 3 插件与当前 Boot 匹配。[Sa-Token 1.45 发布说明](https://sa-token.com/blog/release/sa-token-v1-45-0.html)、[JSON 插件说明](https://sa-token.com/plugin/json-extend.html)

所有业务数据库访问已由 JdbcTemplate 迁到 Mapper。普通用户/知识库 CRUD 使用 BaseMapper、QueryWrapper；保留归属 JOIN、INSERT SELECT、ON CONFLICT、RETURNING、条件状态更新等必要 SQL。现有最近 100 个会话及最近 6 轮窗口保持原协议，本轮不额外增加分页 API。

现有数据库不需要本轮新增字段或表。继续沿用 `chat-schema.sql` 的 CREATE IF NOT EXISTS/幂等 ALTER，不删表、不改旧归属。测试仅连接独立容器；没有用用户数据库做故障注入。

## 包结构与依赖

Java 共同根包为 `com.dujiteng.ragentlearning`，以下展示业务逻辑包。物理位置：system 包在 system 模块，chat/knowledge 在 rag，infra 在 infra-ai；公共异常在 framework；HTTP 全局处理位于 bootstrap 的 bootstrap.web。

```text
system/
  controller/AuthController
  config/AuthConfig
  service/AuthService
  service/impl/AuthServiceImpl
  mapper/UserMapper
  entity/UserDO
  dto/CredentialsRequest
  vo/UserVO, LoginVO
chat/
  controller/ChatController
  config/ChatStreamConfig
  service/ChatService
  service/impl/ChatServiceImpl
  memory/ConversationMemoryStore, ChatMemoryRepository
  mapper/ConversationMapper, MessageMapper
  entity/ChatConversationDO, ChatMessageDO
  dto/ChatRequest
  vo/ChatResponseVO, ChatMessageVO, ConversationVO
knowledge/
  controller/KnowledgeController
  service/KnowledgeService, DocumentParsingService
  service/impl/KnowledgeServiceImpl, DocumentParsingServiceImpl
  repository/KnowledgeRepository
  mapper/KnowledgeBaseMapper, KnowledgeDocumentMapper
  entity/KnowledgeBaseDO, KnowledgeDocumentDO
  dto/CreateKnowledgeBaseRequest
  vo/KnowledgeBaseVO, DocumentVO, DocumentTextVO, DocumentDownload
  parse/DocumentParser, PlainTextDocumentParser, TikaDocumentParser
  storage/FileStorage, LocalFileStorage
infra/llm/ModelClient, ModelResponseParser, ModelStreamParser, StreamCancellation
common/ChatApiException
bootstrap/web/ChatExceptionHandler, ApiResponseHeaders
```

Controller 负责 HTTP、可信当前用户、调用 Service 和响应；接口描述业务，Impl 组织流程。Mapper 负责数据库，DO 映射表，DTO 承载输入，VO 仅暴露允许字段。Client、Parser、Storage 承担外部调用、解析、文件存储。

ChatMemoryRepository 保留整轮事务和归属边界；KnowledgeRepository 保留资源归属、DO/VO 转换和解析状态协调。原 UserRepository 的简单 CRUD 合并进 UserMapper，没有同时保留两套转发层。

自增 BIGSERIAL/identity 主键用 IdType.AUTO 回填；字符串会话编号用 INPUT。下划线字段映射驼峰，PostgreSQL timestamptz 使用 OffsetDateTime，文档响应转换 Instant；不会重新编号旧主键。DocumentDownload 和 DocumentRecord 是服务内部下载信息，不直接序列化为接口 JSON。

## 一次登录

1. login-test.html 将用户名和原始密码作为 JSON 发给 `AuthController.login`，注册走 `register`，不会自动登录。
2. Controller 调 `AuthService.login` 接口，实际执行 `AuthServiceImpl.login`。
3. `UserMapper.selectOne` 查 app_user；`PasswordEncoder.matches` 校验现有 BCrypt 哈希。未知账号也做一次虚拟哈希比对，错误统一 401。
4. 密码正确后 `StpUtil.login(userId)`；框架使用 `AuthConfig.saTokenDao` 提供的 Redis DAO。这个 Bean 标记 Primary，确保带独立前缀的实现被选中，框架默认 DAO 不参与会话读写。
5. `LoginVO` 只返回 userId、username、token、expiresIn。`auth.js` 把 Token 放 sessionStorage，为所有 API 添加 `Authorization: Bearer`。
6. 后续请求由 AuthConfig 的拦截器 `StpUtil.checkLogin` 验证；Controller 调 `StpUtil.getLoginIdAsLong`，不相信请求体的 userId。

Redis 默认前缀 `ragent-learning:auth:`，只影响学习项目会话。默认绝对 8 小时、不自动续期，多端各发新 Token，退出只撤销当前 Token。Java 重启可恢复未过期登录。[Redis 集成](https://sa-token.com/up/integ-redis.html)、[Sa-Token 配置](https://sa-token.com/use/config.html)

Redis 不可用时读取与登录返回 503 AUTH_SESSION_UNAVAILABLE，不回落内存。DB 错误与 Redis 错误分别处理。注册和 me 不返回 UserDO 或密码哈希。

当前凭证不通过 Cookie 自动发送，前后端同源、不开放任意 CORS，写请求检查 Origin。旧 csrf 接口返回 410，旧 Cookie 登录不再有效。若未来引入 Cookie/跨源前端，应重新设计 CSRF 与可信来源。sessionStorage 可被同源脚本访问，保持 textContent 展示文档内容，不将原文插入 HTML。

## 一次聊天

1. `ChatController.chat` 取可信 userId，调 `ChatService.chat` → `ChatServiceImpl.chat`，校验会话编号与问题。
2. `ConversationMemoryStore.getOrCreate` → `ChatMemoryRepository.ensureOwned` → `ConversationMapper.createIfAbsent`，ON CONFLICT 后检查拥有者。旧 NULL 归属不认领。
3. 对同一个用户/会话的 Conversation 加本机锁，`history` → `MessageMapper.recent`，带 user_id 的 JOIN 按 id 取最近 12 条，再正序送给模型。
4. `ModelClient.chat` 或 `stream` 执行实际模型调用。这里没有数据库长事务，原有提示词和模型客户端保留。
5. 完整回答后 `Conversation.appendTurn` 调另一个 Spring Bean `ChatMemoryRepository.appendTurn`，代理开启事务，两次 `MessageMapper.insertOwned` 同时提交或回滚。
6. 普通接口返回 answer；SSE 由 Controller 发 start/delta/done/error，只有落库成功后发 done。

SSE worker 显式接收请求线程已验证的 userId。保留取消、默认 90 秒超时和错误处理；模型没有 DONE、用户取消、超时、写库失败都不将半截回答保存为成功。网络在数据库提交后断开，客户端可能收不到 done，应查历史确认，不自动重放。

锁当前只覆盖单 JVM。未来多实例运行需要单独设计并发方案，本轮不引入分布式锁或 Agent 框架。

## 一次文档解析

1. 上传独立完成：`KnowledgeController.upload` → `KnowledgeServiceImpl.upload` → 归属/文件校验 → `FileStorage.store` → `KnowledgeRepository.createDocument` → `KnowledgeDocumentMapper.createOwned`。带归属 INSERT SELECT RETURNING 返回 PENDING；写库失败清理新文件。
2. 页面点击解析，带 Bearer 发 POST。`KnowledgeController.parse` 取得可信 userId → `DocumentParsingService.parse` → `DocumentParsingServiceImpl.parse`。
3. `KnowledgeRepository.findDocument` 的 JOIN 同时校验 user、knowledgeBase、document。越权在读取文件前结束。
4. `startParsing` 通过一条条件 UPDATE 占用任务，写 RUNNING、开始时间和新 parse_token。5 分钟有效租约内重复请求 409，超时可重试。
5. `FileStorage.load` 读取原文件，按 fileType 选择 `PlainTextDocumentParser.parse` 或 `TikaDocumentParser.parse`。这一段不占数据库事务；Parser 不访问数据库。
6. `finishParsing` 用当前 parse_token 条件一次性保存完整文本/SUCCESS/时间，或 FAILED/可展示原因；旧任务不能覆盖新任务。写库失败明确 503，不伪装成功。
7. `KnowledgeController.text` → parsing.text → Repository → Mapper 返回 DocumentTextVO。页面用 textContent 展示。原文下载用 Bearer fetch+Blob，无 Token URL。

## 与原始 Ragent 对应

| 学习版职责 | 原项目已核对的对应位置 | 当前简化 |
| --- | --- | --- |
| system 的 AuthService、AuthConfig | system/user/controller/AuthController、user/config/SaTokenConfig、user/service | 不复制用户上下文框架、权限与演示模式；业务显式传 userId |
| ChatServiceImpl、Conversation/MessageMapper | rag/service/impl/RAGChatServiceImpl、rag/dao/mapper/ConversationMapper、ConversationMessageMapper | 直接模型问答，暂不检索；本机锁、最近六轮 |
| KnowledgeServiceImpl、DocumentParsingServiceImpl | knowledge/service/impl/KnowledgeBaseServiceImpl、KnowledgeDocumentServiceImpl；core/ingest/DefaultIngestionKernel | 同步解析，暂不分块、向量化、索引调度 |
| DocumentParser、TikaDocumentParser、FileStorage | core/parser/DocumentParser、TikaDocumentParser、MarkdownDocumentParser；rag/service/FileStorageService | 平文本与本地存储，不复制结构化 Block、MinerU、OCR 或复杂表格 |
| 未来 Agent | agent/service/impl/AgentChatServiceImpl 的 AgentScope 路线 | 本轮没有接入 |

原项目对应路径相对 `D:\jastudy\idea\ragent`，Java 包前缀为 com.nageoffer.ai.ragent。学习版保持依赖方向与职责，不按目录机械复制全部类。

## 验证与边界

实际验证环境：JDK 21、Spring Boot 4.1.0、独立 PostgreSQL 16、独立有密码 Redis、本机假模型，真实 Edge 无界面浏览器。测试脚本 `scripts/verify-knowledge.mjs`，可选浏览器部分 `scripts/verify-browser.mjs`；命令见 README。

当前通过的 28 组覆盖：D8 旧表增量/记录保留；注册、重复账号、错误密码、BCrypt；仅 Bearer、旧 csrf 废止、Origin 拒绝；真实 Redis 键和到期；多端登录/退出范围；可信 userId、旧无归属会话及用户隔离；普通/SSE 聊天、六轮历史、同会话并发；真实数据库约束触发整轮回滚；模型错误/半截流/取消/超时；上传下载与跨库隔离；原文件失败清理；TXT/MD/真实 PDF/DOCX；损坏、加密、无文本、编码/长度失败持久化；租约重试和结果写库故障；Java 重启恢复登录与业务数据；Redis 故障 503 和恢复；浏览器端完整操作及退出清理；页面脚本语法。

报告路径随每次执行变化，位于 target/knowledge-test-*/result.json。最终复核的准确命令、版本和报告另见 [本轮验收记录](10-migration-verification.md)。

未验证：没有用真实付费模型发新请求；没有对用户现有数据库执行写入或全部业务手动回归；没有验证多 JVM 并发、独立前端跨域或 Redis 自身断电后的持久化。原项目 Redis 已按其私有配置只读 PING 成功（6379），没有清理或扫描其业务键。无需从这些测试推断生产可用性。

## 现在需要理解

先掌握四个问题：userId 从哪来；每次查询如何限定归属；为什么模型等待不在事务中；为什么 done 必须在完整保存之后。再跟一遍解析的“占用任务 → 读文件 → 解析 → 条件写结果”，能区分解析失败和数据库保存失败即可。

建议手动 Git 提交说明：`refactor: migrate persistence to MyBatis-Plus and auth to Sa-Token Redis`。请先在 IDEA 查看差异，继续由你手动提交和推送。
