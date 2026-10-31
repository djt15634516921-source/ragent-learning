# Ragent 学习工程开发约定

## 必须遵守

- 修改前确认目录、读取最新源码并检查 Git 状态。学习工程 `D:\jastudy\idea\ragent-learning`，参考工程 `D:\jastudy\idea\ragent`。保留用户未提交修改、自定义提示词、解析功能和源码压缩包。不自动提交、推送或硬重置。
- 使用 Maven 多模块：framework、infra-ai、system、rag、bootstrap。根 POM 聚合并统一版本；仅 bootstrap 启动和打包可运行 JAR。保持 Java 17 编译目标、Spring Boot 4.1.0、PostgreSQL，当前验证使用 JDK 21。不进行无关升级或降级。
- 依赖方向：bootstrap → system/rag/framework；rag → infra-ai/framework；system → framework；infra-ai → framework。基础模块不能反向依赖业务或启动模块。当前 rag 只通过 Sa-Token API 获取可信 userId，没有 system Java 类型依赖，不为了模仿目录添加无用依赖。
- 模块 POM 只声明实际需要的依赖；版本统一放根 dependencyManagement，不能把所有依赖放父 dependencies 让每个模块继承。业务类库不应用 Spring Boot repackage。
- 数据访问统一 MyBatis-Plus 3.5.18（spring-boot4-starter）；认证统一 Sa-Token 1.46.0（Boot 4 starter、Redis Template、Jackson 3 插件）。新版本必须核对官方兼容说明并实际启动。禁止混用 Boot 3 starter。
- Spring Security 仅保留独立 crypto 用于 BCrypt。不恢复 SecurityFilterChain、UserDetails、AuthenticationPrincipal 或第二套认证状态。
- system 模块放用户认证；rag 模块内部按 chat/knowledge 业务组织；infra-ai 放模型调用；framework 仅放跨模块基础约定；bootstrap 只放启动、全局 Web 装配和运行资源。Java 包名尽量稳定，内部目录按需创建。业务 Service 为接口和带 @Service 的 Impl；Controller 构造器注入接口，不直接访问 Mapper。不机械创建空目录、MapperImpl 或通用 IService 继承。
- 实体统一 DO 后缀。DTO 接收参数，VO 限定响应；不返回密码哈希或内部存储键。Mapper 继承 BaseMapper，关联查询、归属原子写入及 PostgreSQL 特性保留自定义 SQL。Repository 仅在承担归属、转换或事务等实质职责时保留。
- 当前用户通过已验证的 StpUtil 获取，业务显式传 userId，SSE worker 不读取请求线程上下文。所有资源操作保留归属过滤，旧无归属会话不能被自动认领。他人资源按现有约定返回 404。
- 只接受 Authorization: Bearer 请求头，不从 Cookie、请求体或 URL 读取 Token。浏览器在当前标签页 sessionStorage 保存，401 清理，不自动重放写请求。下载用带请求头的 fetch+Blob，禁止 Token URL。
- 注册/登录接收 JSON，保留 BCrypt 12 轮及已有哈希，密码正确后才能建立会话。Redis DAO 必须是实际持久层；故障明确返回 503，禁止内存回退。
- Redis 默认 127.0.0.1:6379、DB 0，密码经 REDIS_PASSWORD 注入；键前缀必须以 ragent-learning: 开头，默认 ragent-learning:auth:。不清库或删除其他工程的键。
- 登录默认绝对有效期 8 小时、不自动续期；多端各自获得新 Token，退出仅撤销当前 Token。行为变更必须同步文档和测试。
- 当前页面同源部署，无任意来源 CORS。写请求的 Origin 若存在必须匹配当前服务。认证不依赖自动 Cookie，因此移除旧 CSRF 交换；/api/auth/csrf 明确返回 410。未来若引入 Cookie，须重新设计 CSRF。
- 完整问答由 ChatMemoryRepository.appendTurn 经 Spring 代理在一个短事务中保存 user/assistant 两条消息。禁止同类内部调用绕过代理，禁止把模型等待、SSE 发送或长文件解析放进数据库事务。
- 保留同会话本机并发锁、最近 6 轮历史、消息编号顺序和 start/delta/done/error。完整回答保存后才发 done。取消、超时、半截流或写库失败不保存成功轮次。
- 上传与解析分开；上传写库失败清理新文件。解析保留状态、失败原因、完整正文、5 分钟租约及 parse_token 条件更新；旧任务不能覆盖新任务。Parser 不访问 Mapper。
- 不删表重建、不清记录、不重新分配归属。结构变化与现有 db/chat-schema.sql 幂等迁移协调。本轮框架迁移不需要新增表结构。
- 保留统一异常处理和错误码，区分参数、认证、权限、资源、数据库、文件和模型错误。不能吞异常返回空列表或成功，不记录密码、Token、Cookie、密钥或完整敏感正文。
- 凭证仅用环境变量或被忽略的私有配置，示例不含真实值。注释解释原因和边界，避免逐行复述。
- 验证使用独立 PostgreSQL/Redis、端口、文件目录和本机假模型；不得对用户业务数据做故障注入。编译、集成、浏览器和真实模型验证分别报告，不能虚构结果。
- 本轮不新增向量化、检索或 Agent。未来 Agent 沿用原项目 AgentScope，本轮不提前接入。

## 可选建议

- 新抽象、分页接口、缓存和分布式锁须有明确场景；当前单实例锁不代表多实例并发保证。
- 保持显式 import、四空格缩进与构造器注入，优先保证学习代码可读。
- 按影响范围验证，完整命令见 README。可选浏览器回归使用 RUN_BROWSER_CHECK=true。
- 讲解沿实际输入输出、Controller、ServiceImpl、Mapper/组件展开，重点理解身份、归属、事务及排错证据。

## 多模块开发和验证

- 应用配置、SQL 初始化、静态测试页统一放 bootstrap/src/main/resources；HTTP 样例在 docs/http。原始源码压缩包归档 docs/archive/main.zip，保留字节。
- IDEA 运行 RagentLearningApplication 时选择 bootstrap 模块类路径，工作目录固定项目根目录，环境变量沿用原配置；不要让相对路径 data/knowledge 指向 bootstrap/data。
- 根目录构建使用 mvn clean verify。命令启动先构建，再从根目录 java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar。spring-boot:run 仅对 bootstrap 执行，先 install 本地模块；不要把 run 目标用 -am 应用到所有模块。
- 完整回归构建：mvn clean verify dependency:build-classpath "-Dmdep.outputFile=target/parser-classpath.txt"，然后 node scripts/verify-knowledge.mjs。脚本使用 bootstrap 的可运行包及 rag 的解析依赖类路径。
