# 数据库与认证迁移验收记录（2026-10-30 历史）

当前多模块验收见[第 11 步记录](11-maven-modules-verification.md)，下面的路径与命令是当时单工程的实际记录。

日期：2026-10-30。本记录对应本机实际执行，未提交或推送 Git。

## 环境与命令

- Spring Boot 4.1.0、JDK 21.0.8，Java 编译目标 17。
- MyBatis-Plus Boot 4 starter 3.5.18；Sa-Token Boot 4 / Redis / Jackson 3 插件 1.46.0。
- crypto 7.1.0 仅供 BCrypt；最终依赖与 jar 无 Security web/config/core。
- 测试 PostgreSQL 16.15，Redis 8.6.3（本机已有镜像）；独立容器、随机端口与独立文件目录。
- 真实 Edge 无界面回归，Playwright Core 1.64.1 仅为测试工具。

执行：

```powershell
mvn -q clean verify dependency:build-classpath "-Dmdep.outputFile=target/parser-classpath.txt"
$env:RUN_BROWSER_CHECK = 'true'
node scripts/verify-knowledge.mjs
mvn -q dependency:tree "-DoutputFile=target/dependency-tree.txt"
```

Maven 干净构建通过；独立应用首次启动及 Java 重启通过。项目没有 src/test 下的 JUnit 测试，业务验证实际由以下端到端脚本完成。接口参数空格及未用 import 整理后补跑 Maven verify。

## 实际通过项

1. D8 已有表增量迁移，旧文档记录保留
2. 未登录 API 被拦截，测试页可打开
3. BCrypt、错误密码、重复账号、真实 Redis 会话和仅 Bearer 认证，旧 CSRF 明确废止
4. 同账号多端独立 Token、固定八小时有效期和 Redis 到期失效
5. 注册登录、非同源写请求拒绝和知识库名称校验
6. 创建两个知识库并独立查询文档
7. 中文文件上传、路径名清理、PENDING 状态和原文下载
8. 普通聊天、提示词保留、可信 userId、会话归属和无归属旧记录保护
9. 最近六轮按消息编号排序，模型窗口受限且数据库完整历史保留
10. 同一会话并发请求按完整轮次协调，后续模型请求看到已保存历史
11. SSE start/delta/done 协议与保存顺序，模型失败、半截流、取消及超时不写成功记录
12. 真实数据库约束触发普通/SSE 整轮事务回滚，失败不发 done，解除故障后恢复
13. 跨用户列表、读取、下载、上传和跨知识库编号访问被拒绝
14. 第二个用户可以正常管理自己的资料
15. 缺失、空文件、类型、超限和错误编号校验，无原文件残留
16. 真实数据库约束失败：无文档记录，刚保存的文件已清理
17. 待处理结果查询、解析来源校验和跨用户/跨库解析权限
18. UTF-8 中文、BOM/换行规范化、Markdown 原文与字符数
19. 真实 PDFBox PDF 与 POI DOCX 提取文本
20. 损坏/加密/无文字 PDF、损坏 DOCX、非法编码、空白与超限：失败原因持久化，不保存部分文本
21. RUNNING 占用拒绝重复解析，中断超时后可重新解析
22. 写入解析结果失败不误报成功，解除故障后重试恢复
23. Java 重启后 Redis 登录恢复，知识库、解析文本和原文件仍存在
24. 退出只撤销当前 Token，原文件和解析文本拒绝访问，其他终端继续有效
25. Redis 不可用时读取和登录明确 503，不回落内存，连接恢复后原 Token 有效
26. 真实 Edge：注册、登录、sessionStorage、普通聊天和两个 SSE 页面
27. 真实 Edge：上传、解析、读取文本、携带请求头下载、刷新和退出清理
28. 新页面 JavaScript 语法检查

共 28 组通过。原始报告：`target/knowledge-test-10dde831/result.json`；首次/重启日志在同目录，真实浏览器截图为 `knowledge-page.png`。测试创建的应用与 PostgreSQL/Redis 容器已清理，未操作已有业务库。

## 静态及保护检查

- Git diff --check 通过；没有提交、推送或硬重置。
- 业务源码不再包含 JdbcTemplate、SecurityFilterChain、AuthenticationPrincipal 或 UserDetails；最终 jar 无已移动包的旧类。
- ModelClient SHA-256 与迁移前完全一致：4C518A76161EFD5EABC573B3F33B520A9DE5331750C84C2682D6E8E5FF243EEC。聊天系统提示词通过假模型入参断言，内容保持原样。
- src/main.zip 保留；迁移前源码、配置及文档备份保存在忽略目录 data/migration-baseline-20261030。
- 原项目 Redis 仅按其私有配置 PING 验证成功，端口 6379，没有扫描或清理共享实例业务键；密码未写入示例。

## 未验证和本地操作

- 没有发起真实付费模型请求；测试使用本机可控假服务，不能据此判断供应商可用性。
- 没有向用户现有 PostgreSQL 写入测试记录，没有替代用户在该库上的全部手动回归。
- 没有验证多 JVM 并发、独立前端跨域或 Redis 自身断电后的持久化；当前保证单 JVM 会话协调和 Java 重启恢复登录。
- IDEA 中增加 REDIS_PASSWORD（复制原工程私有配置的实际值，与你记忆的值不一致），保留 DB_PASSWORD 和 LLM 配置，重新加载 Maven，再停止旧进程并启动新代码。旧 Cookie 登录需要重新登录一次。
- HTTP 请求示例已适配 Bearer；IDEA HTTP Client 响应脚本未单独在 IDEA 内执行。真实页面、普通请求、SSE、上传及下载已由 Edge 回归验证。
