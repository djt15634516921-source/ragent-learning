# 第 08 步：知识库与原文档上传

> 当前进度：已接通知识库检索问答，见 [D11 说明](13-knowledge-rag.md)。下文保留对应阶段的实现边界和验收记录。

> D10 文档切分已接入，查看[切分说明与当前验收步骤](12-document-chunking.md)。

> 当前按[第 11 步](11-maven-modules.md)组织多模块：本章业务 Java 在 rag/src/main/java，配置/SQL/页面在 bootstrap/src/main/resources。历史验收日期与结果保留。

> 2026-10-30：数据库/认证迁移及真实 Edge 回归已完成，共 28 组通过，见第 10 步架构说明与验收记录。文中 2026-10-29 验证小节是历史记录。

本轮先集中理解登录功能，再开始知识库、文档元数据与上传。已有最小聊天页继续使用。

已由用户确认的本机结果：注册、登录、真实模型流式回答、查看最近历史。此前已确认 PostgreSQL 聊天记录持久化和重启续聊。当前验证使用独立 PostgreSQL、Redis 和独立 Java 进程，详见第 6 节；不把它等同于用户在现有数据库中的全部手动验收。

## 1. 当前登录功能怎样记

1. 注册：AuthController → AuthServiceImpl → BCrypt 哈希 → UserMapper.insert。
2. 登录：JSON 账号密码 → AuthServiceImpl.login → UserMapper 查账号 → BCrypt matches → StpUtil.login → Redis。
3. 后续请求：auth.js 从当前标签页 sessionStorage 读取 Token，显式添加 Authorization: Bearer。
4. Controller 从 StpUtil 取可信 userId，再显式传给 Service 和 SSE worker。
5. Repository/Mapper 的归属条件检查资源是否属于当前用户，登录成功不能替代这一检查。

AuthConfig 配置入口检查和 Redis DAO。Java 重启保留未过期会话；退出只撤销当前 Token，多端登录独立。旧 CSRF 交换已移除，详细说明见第 06、10 步。

## 2. 本轮场景和原项目对应

场景：用户创建“Java 学习资料”“业务资料”两个知识库，上传文件后查看记录、下载原文件；其他用户不能访问这些知识库和文档。

本轮输入：知识库名称/说明、一个文件；输出：知识库编号、文档编号、原文件名、文件大小、类型、PENDING 状态。

原项目入口（相对完整项目根目录）：

- rag/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java：知识库业务入口。
- rag/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java 的 upload：存原文件，保存文档元数据和待处理状态。
- rag/src/main/java/com/nageoffer/ai/ragent/rag/service/FileStorageService.java：存储接口，隔离底层实现。

原项目 upload 与 startChunk/executeChunk 分开；学习版本沿用这一职责划分。本轮上传成功后状态为 PENDING，尚未执行解析、分块、Embedding 或检索，也不改变已有聊天的资料范围。

## 3. 改了哪些位置

| 位置 | 职责 |
| --- | --- |
| knowledge/controller/KnowledgeController | 从认证结果取得用户，接收创建、列表、上传、下载请求 |
| knowledge/service/impl/KnowledgeServiceImpl | 校验输入、先检查归属、组织文件保存和元数据写入 |
| knowledge/repository/KnowledgeRepository | 带当前用户条件查询和保存知识库/文档 |
| knowledge/storage/FileStorage | 文件存储接口 |
| knowledge/storage/LocalFileStorage | 本地目录实现，后端生成路径，原名作为元数据保存 |
| common/ChatExceptionHandler | 新增文件过大、上传格式、数字编号错误的 JSON 响应；业务异常处理扩展到新 Controller |
| db/chat-schema.sql | 只新增知识库、文档表及索引，启动时幂等创建 |
| static/knowledge-test.html | 携带 Bearer 请求头，创建知识库、上传、列表、下载 |
| application.yaml | multipart 单文件 10 MB，整个请求 11 MB |
| .gitignore | 排除 data/，不把用户上传文件提交到 Git |

所有 Java 路径相对 src/main/java/com/dujiteng/ragentlearning。

数据关系：app_user → knowledge_base → knowledge_document。文档通过知识库确定用户归属，不再复制一份可能不一致的 owner 字段。

默认原文件位置：相对启动目录的 data/knowledge/{用户编号}/{知识库编号}/{随机编号}.{扩展名}。可用 KNOWLEDGE_STORAGE_ROOT 环境变量指定固定绝对目录；改启动目录或存储根目录时，需要同时迁移已有原文件。接口返回文档编号，不返回磁盘路径。

当前允许 txt、md、pdf、docx 扩展名，单文件 10 MB；扩展名校验不等于实际格式解析。实际内容是否能被解析在下一步处理。本轮下载通过带归属检查的接口，以附件返回。

## 4. 顺着一次上传看

Bearer 请求头 → Sa-Token/Redis 验证身份 → Controller 取得 userId 和 MultipartFile → Service 检查知识库归属、文件名/大小/扩展名 → FileStorage 保存原文件 → Repository 写文档记录 → 返回 PENDING。

知识库与文档记录在 PostgreSQL，原文件在文件存储，这是两个保存位置。

数据库事务不会自动撤回文件写入。当前对捕获到的写库失败进行补偿：删除本次刚保存的文件；测试已覆盖数据库明确拒绝写入的场景。进程在两个动作之间崩溃，或数据库提交结果不明时，仍可能需要核对记录与原文件，后续可靠性阶段再补对账与清理机制。

本轮重点看三个方法：KnowledgeService.upload 的顺序、KnowledgeRepository.findDocument 的用户和知识库条件、LocalFileStorage.store 怎样生成存储路径。

## 5. 本机怎样验收

1. 在 IDEA 停止并重新启动 RagentLearningApplication，沿用现有数据库和模型运行配置。新 SQL 会创建 knowledge_base、knowledge_document 两张表。
2. 打开 http://localhost:18081/login-test.html 登录原账号，再点“管理知识库与上传文档”，或打开 http://localhost:18081/knowledge-test.html。
3. 建立“Java 学习资料”和“业务资料”两个知识库。
4. 把 docs/examples/knowledge-demo.md 上传到第一个知识库。应显示“待处理”、文件名与大小；第二个知识库应没有这份文档。
5. 点击文件名，确认下载内容与原文件一致；刷新页面，记录仍在。
6. 无痕窗口登录另一个用户，其知识库列表应不含前一个用户的知识库；复制前一个用户的文档下载地址访问，应返回 404。

数据库核对：

```sql
SELECT id, user_id, name FROM knowledge_base ORDER BY id;
SELECT id, knowledge_base_id, original_filename, file_size, status, storage_key
FROM knowledge_document ORDER BY id;
```

接口（除页面外都要求登录；写请求检查同源 Origin）：

| 方法 | 路径 | 输入/结果 |
| --- | --- | --- |
| POST | /api/knowledge-bases | JSON name、description；返回 201 |
| GET | /api/knowledge-bases | 当前用户的知识库 |
| POST | /api/knowledge-bases/{id}/documents | multipart 字段 file；返回 201 和 PENDING |
| GET | /api/knowledge-bases/{id}/documents | 指定知识库的文档 |
| GET | /api/knowledge-bases/{id}/documents/{documentId}/file | 下载原文件 |

上传必须用 FormData/multipart；浏览器自动填写 Content-Type 与 boundary，页面只添加 Bearer 请求头。未登录返回 401；非同源 Origin 返回 403；他人知识库/文档返回 404；空文件 400、不支持的扩展名 415、超过大小限制 413。

本机验收完成后，用 IDEA 检查本次 diff 并提交，建议说明：feat: 新增知识库与原文档上传。原来 ModelClient 的未提交改动属于用户已有改动，本轮未修改该文件，提交时按实际内容决定是否包含。

## 6. 实际验证记录（2026-10-29）

- 使用本机 Java 21、Maven 构建打包成功。
- scripts/verify-knowledge.mjs 启动独立的原生 PostgreSQL 16 容器和独立 Java 应用，自动选择空闲端口，验证结束后清理测试应用和容器。未连接现有 ragent_learning 数据库，未调用模型平台。
- 11 组通过：未登录拦截及页面路由；注册登录/CSRF/名称校验；两个知识库独立列表；中文原文上传下载、文件名清理和 PENDING；跨用户及跨知识库访问拒绝；第二用户正常操作；缺失/空文件/类型/超限/错误编号；明确数据库失败后的文件清理；真实 Java 进程重启恢复；退出后拦截；新页面 JavaScript 语法。
- 数据库失败测试实际添加 CHECK 约束拒绝 rollback.md，确认没有新增文档记录且原文件数量不变。
- 页面 JavaScript 通过语法检查；本轮未做真实浏览器点击和截图。现有 D 盘数据库及浏览器交互仍按第 5 节由用户验收。

复跑方式（需要 Docker、Java、Maven、Node.js 22+）：

```powershell
mvn clean verify dependency:build-classpath "-Dmdep.outputFile=target/parser-classpath.txt"
node scripts/verify-knowledge.mjs
```

## 7. 下一步

D9 代码现已接入，见 [第 09 步笔记](09-document-parsing.md)：TXT/Markdown、文本型 PDF/DOCX 解析，记录 RUNNING/SUCCESS/FAILED 和失败原因。上面的复跑命令也已更新，包含迁移、认证、聊天和解析检查；默认 26 组，启用真实浏览器后共 28 组；第 6 节保留的是 D8 当次的 11 组记录。随后 D10 进行分块。检索和基于资料回答要等后面的 Embedding、向量入库和 RAG 流程。

官方资料：[Spring 文件上传示例](https://spring.io/guides/gs/uploading-files/)、[Spring MVC Multipart](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html)。
