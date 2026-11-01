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
- 不删表重建、不清记录、不重新分配归属。结构变化与现有 db/chat-schema.sql 幂等迁移协调。每次结构变化须提供可重复执行的增量 SQL，保留旧记录与归属。
- 保留统一异常处理和错误码，区分参数、认证、权限、资源、数据库、文件和模型错误。不能吞异常返回空列表或成功，不记录密码、Token、Cookie、密钥或完整敏感正文。
- 凭证仅用环境变量或被忽略的私有配置，示例不含真实值。注释解释原因和边界，避免逐行复述。
- 验证使用独立 PostgreSQL/Redis、端口、文件目录和本机假模型；不得对用户业务数据做故障注入。编译、集成、浏览器和真实模型验证分别报告，不能虚构结果。
- 当前阶段完成 Embedding、pgvector 入库、知识库检索与带来源回答；普通聊天继续兼容。未来 Agent 沿用原项目 AgentScope，本轮不提前接入。

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

## D10 文档切分

- 切分在 rag/knowledge 内，业务 Service 继续接口＋Impl；TextSplitter 为明确的纯计算组件，不访问数据库或模型。
- 长度/位置按 Unicode 码点计算，默认 800/120，序号从 0 开始，位置为解析正文的左闭右开区间。不得将字符数冒充 Token 数或 PDF 页码。
- 文档正文版本与切分版本必须匹配才能读取片段；重新解析使旧片段失效，保存旧版本结果须返回冲突。
- 切分计算在事务外，整批替换和统计在独立 Repository Bean 的短事务内；批次失败必须恢复旧片段。相同版本和参数复用结果。
- 使用 PostgreSQL MyBatis-Plus 分页，保留用户/知识库/文档归属 JOIN；jsqlparser 版本与现有 MyBatis-Plus 统一为 3.5.18。
- 完整验证包含 TextSplitterTest、verify-chunks.mjs 和已有回归；不使用用户业务库做故障注入，不自动提交或推送。

- 本机私有连接覆盖可放工程根目录 config/application.properties，该文件必须被 Git 忽略，真实凭证不能写入示例。bootstrap 中的配置仍为共享基线；外部覆盖依赖正确的工程根工作目录，修改后须重启。

## D11 知识库问答必须遵守

- Embedding 与聊天分别负责向量和文本。当前默认百炼 text-embedding-v4、固定 1024 维、每批最多 10 条，复用现有密钥但不写入源码。ModelClient 与用户原有基础提示词继续保留。
- 文档向量与问题向量必须匹配 endpoint/model/dimensions profile；换空间需重新向量化，禁止静默混用、补零、截断或生成假向量。
- 向量化显式触发，有状态、失败原因、租约与 token。远程计算在事务外，全部向量与成功统计在独立 Repository 的短事务内提交；部分失败不能发布索引。
- chunk_revision 区分相同正文的不同切分；重解析/实际重切分失效旧索引，旧任务不能发布。检索在 SQL 同时过滤归属、正文/切分/索引版本与向量空间。
- 普通会话和选定知识库会话固定模式，切换时新建会话。SSE 维持原事件协议，done 增加来源且必须在完整问答与 sources_json 同事务保存后发送。
- 检索资料是当前 user 消息中的引用数据，不提升为 system 指令。无可用索引明确报错，无足够相关资料明确拒答；来源为当次快照，不随文档变化改写历史。
- 知识库模型输入按当前 sources 顺序显式提供 citationId、文件名与正文。旧助手正文不能直接充当本轮事实证据；当前用占位消息隔离发送副本，保留近期用户问题，数据库/页面历史与普通聊天不改写。未来调整须复验错误金额历史、缺失事实拒答及引用对应，不能硬编码评估题答案或宣称提示词等于事实核验。
- 完整回归包括 verify-rag.mjs；真实提供方验收脚本 verify-rag-provider.mjs 会产生调用费用，只在需要且使用合成资料、独立容器时执行。不得自动向量化用户已有资料或用业务库注入故障。
