# Ragent 学习工程

现有工程已实现普通/流式聊天、多轮历史与 PostgreSQL 持久化、注册登录与用户隔离、知识库管理、文档上传下载、TXT/Markdown/PDF/DOCX 文本解析，带来源和重叠的文档切分，以及基于 Embedding + pgvector 的知识库问答（普通/流式回答及来源）。当前统一为 **MyBatis-Plus + Sa-Token + Redis**，采用 framework、infra-ai、system、rag、bootstrap 五个 Maven 模块。

2026-11-02 学习重点已按用户提供的简历教程调整为原项目核心能力的实现、理解与验证。基础 RAG 是当前起点；Agent、Skills、人工确认、分层记忆、混合检索和效果评测的差距与推进顺序见 [核心学习路线](docs/15-core-learning-roadmap.md)，这些计划项尚不能作为已实现能力。

学习工程：`D:\jastudy\idea\ragent-learning`。参考工程：`D:\jastudy\idea\ragent`。

## 本机启动

1. 启动 PostgreSQL 和 Redis。现有 Redis 对外端口为 6379；密码以原项目 `bootstrap/src/main/resources/application.yaml` 为准，不要依赖记忆中的值。
2. IDEA 运行配置的 Environment variables 中保留 DB_PASSWORD 和原来的模型变量。在同一处配置 REDIS_PASSWORD，保存后重新启动应用。`.env.example` 仅供填写参考，Spring Boot **不会自动读取 `.env`**。
3. Maven 重新加载根 pom.xml。运行 RagentLearningApplication 时，模块类路径选择 bootstrap，工作目录设为 D:\jastudy\idea\ragent-learning，沿用原有环境变量。停止旧进程后再运行新配置。
4. 同一标签页打开 [登录和聊天页](http://localhost:18081/login-test.html)，注册后登录，通过页内链接进入 [知识库页](http://localhost:18081/knowledge-test.html)。[独立 SSE 页](http://localhost:18081/stream-test.html) 也使用同一标签页凭证。

沿用 JDK/Maven；Java 编译目标 17，当前验证用 JDK 21，Spring Boot 保留 4.1.0。命令启动先在根目录执行 `mvn clean verify`，再执行 `java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar`。Maven run 和 IDEA 详细设置见第 11 步。

| 变量 | 用途及默认值 |
| --- | --- |
| DB_PASSWORD | 现有 PostgreSQL 密码；保留原数据与账号 |
| SPRING_DATASOURCE_URL / USERNAME | 默认 jdbc:postgresql://127.0.0.1:5432/ragent_learning / postgres |
| REDIS_HOST / REDIS_PORT | 默认 127.0.0.1 / 6379 |
| REDIS_PASSWORD / REDIS_USERNAME | 按本机配置填写；无 ACL 用户时用户名留空 |
| REDIS_DATABASE | 默认 0，按独立键前缀隔离 |
| AUTH_REDIS_PREFIX | 默认 ragent-learning:auth:，必须以 ragent-learning: 开头 |
| AUTH_TOKEN_TIMEOUT | 默认 28800 秒，绝对有效期 8 小时，不自动续期 |
| SERVER_PORT | 默认 18081 |
| CHAT_STREAM_TIMEOUT_MS | 默认 90000 毫秒，超时取消生成 |
| KNOWLEDGE_STORAGE_ROOT | 默认 data/knowledge，沿用已有原文件目录 |
| LLM_CHAT_URL / LLM_MODEL / LLM_API_KEY | 沿用现有可用模型配置，参数不变 |
| EMBEDDING_URL / MODEL / API_KEY | 地址默认从聊天地址派生；模型 text-embedding-v4、1024 维；密钥默认复用 LLM_API_KEY |
| RAG_TOP_K / RAG_MIN_SCORE / RAG_MAX_INDEX_CHUNKS | 默认 4 / 0.35 / 500，控制召回与单文档向量化规模 |
| RAG_REWRITE_ENABLED / RAG_REWRITE_TIMEOUT_MS | 默认 true / 3000；知识库连续提问额外调用一次现有模型补全检索问题，失败/超时明确标记回退 |

## 当前认证协议

注册、登录均为 JSON：`{"username":"你的账号","password":"你的密码"}`。注册不会自动登录；登录响应含 userId、username、token、expiresIn。后续请求统一 `Authorization: Bearer <token>`。

页面用 sessionStorage 保存 Token，不保存密码，不使用 Cookie，不向 URL 拼 Token。刷新与 Java 重启后未过期登录继续有效；关闭标签页丢弃客户端凭证，服务端最迟到期清理。sessionStorage 按来源和标签页隔离，新标签页可能需要重新登录。

同账号允许多端在线，每次登录发新 Token。退出仅撤销当前 Token，其他终端仍有效。401 清理本页凭证并提示重登，不自动重放写请求。Redis 不可用返回 503 AUTH_SESSION_UNAVAILABLE，保留客户端凭证以待恢复。

下载使用带请求头的 fetch+Blob。同源部署，不开放任意 CORS；浏览器跨源写请求拒绝。旧 Cookie 登录不再有效，需要用新页面重新登录。旧 `/api/auth/csrf` 明确返回 410 AUTH_PROTOCOL_CHANGED。

## 代码与学习说明

- [当前学习重点：教程亮点对照、阶段验收与下一步](docs/15-core-learning-roadmap.md)。
- [当前架构：RAG核心、统一模型入口与重排扩展位置](docs/17-rag-architecture-alignment.md)。
- [当前 D15：连续追问改写、输入输出与验收](docs/16-query-rewrite.md)。
- [当前 D11：知识库检索问答与实际调用链](docs/13-knowledge-rag.md)。
- [D14：固定十题评估、资料与排错记录](docs/14-rag-evaluation.md)：先记录当前结果，再验证后续检索改动。
- [D10：文档切分、来源与页面验收](docs/12-document-chunking.md)。
- [多模块目录、依赖与开发流程](docs/11-maven-modules.md)：当前工程组织与启动方式。
- [开发规范](AGENTS.md)：必须遵守与可选建议。
- [迁移结果、包结构、三条调用链、原项目对应和验证](docs/10-migration-architecture.md)。
- [当前登录流程](docs/06-login.md)、[原文上传](docs/08-knowledge-upload.md)、[文本解析](docs/09-document-parsing.md)。
- HTTP 请求：[认证](docs/http/auth-requests.http)、[聊天](docs/http/chat-requests.http)、[重启续聊](docs/http/persistence-requests.http)、[文档](docs/http/knowledge-requests.http)。真实凭证由本地环境提供。

运行资源在 bootstrap/src/main/resources，用户代码在 system，聊天/知识库在 rag，模型客户端在 infra-ai，共用异常在 framework。

DO 映射表，DTO 接收参数，VO 限制响应；Controller 注入 Service 接口，Impl 组织业务，Mapper 访问数据库。保留有实际归属和事务职责的 Repository，没有手写 MapperImpl 或强制继承通用 IService。

框架迁移保留了旧表结构；D10 在现有幂等 SQL 末尾新增片段表和正文/切分版本字段，不重新分配资源归属。D11 增量启用 pgvector、向量索引状态、切分批次、会话知识库与消息来源，不删除旧记录。迁移前工作区备份在被忽略的 `data/migration-baseline-20261030`，不作为源码提交。原始压缩包保存在 docs/archive/main.zip，自定义模型提示词保留。多模块迁移前备份见第 11 步。

## 验证

需要 Docker、Maven、JDK、Node.js 20+。脚本创建并清理自己的 PostgreSQL/Redis 容器、文件目录和端口；模型请求由本机假服务回答，不产生外部模型费用。

```powershell
mvn clean verify dependency:build-classpath "-Dmdep.outputFile=target/parser-classpath.txt"
node scripts/verify-knowledge.mjs
```

可选真实 Edge 回归（测试工具安装到忽略目录，不增加业务依赖）：

```powershell
npm install --prefix target/browser-check --no-save playwright-core@1.64.1
$env:RUN_BROWSER_CHECK = 'true'
node scripts/verify-knowledge.mjs
```

报告与日志在 `target/knowledge-test-<随机编号>/`。Maven verify负责构建，业务回归由Node脚本执行。最新架构对齐通过19个单元测试及58组集成/Edge检查，见 [架构验收记录](docs/17-rag-architecture-alignment.md)。D15用户曾完成一条真实模型连续追问观察，见 [D15验收记录](docs/16-query-rewrite.md)；本次架构版本未追加真实付费调用，系统性真实效果评测仍待完成。此前真实百炼最小RAG历史结果见 [D11验收记录](docs/13-knowledge-rag-verification.md)，不能把编译或假模型通过等同于真实模型效果通过。

## 本机 Redis 登录错误排查

出现 503 AUTH_SESSION_UNAVAILABLE 时先检查 Redis 和密码。运行配置缺少 REDIS_PASSWORD 时，应用仍可能启动，但登录时 Redis 会拒绝读取会话。当前统一在 IDEA 运行配置的 Environment variables 中添加 REDIS_PASSWORD，保存后重新启动应用。

本机 Redis 连接已验证。数据库密码、Redis 密码和模型参数继续使用现有运行配置的环境变量，工作目录保持 D:\jastudy\idea\ragent-learning。
