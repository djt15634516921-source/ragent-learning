# 多 Maven 模块整理验收记录

> 后续更新：D10 已新增文档切分及算法单元测试；最新功能与验收见[第 12 步](12-document-chunking.md)。下文保留模块化当次记录。

日期：2026-10-31。学习工程 D:\jastudy\idea\ragent-learning，参考工程仅作只读对照。

## 已执行

- Maven 3.9.12、JDK 21.0.8，Java 编译目标仍为 17。
- 从根目录运行 `mvn clean verify dependency:build-classpath "-Dmdep.outputFile=target/parser-classpath.txt"`，父工程和五个子模块均 BUILD SUCCESS。
- `RUN_BROWSER_CHECK=true` 运行 `node scripts/verify-knowledge.mjs`，实际使用 bootstrap 打包后的可运行 JAR，包含首次启动和 Java 进程重启。
- Git diff --check 通过，未提交或推送 Git。

## 回归结果

1. 五模块依赖方向、普通类库 JAR、唯一可运行 JAR 与配置/页面/SQL 资源装配
2. D8 已有表增量迁移，旧文档记录保留
3. 未登录 API 被拦截，测试页可打开
4. BCrypt、错误密码、重复账号、真实 Redis 会话和仅 Bearer 认证，旧 CSRF 明确废止
5. 同账号多端独立 Token、固定八小时有效期和 Redis 到期失效
6. 注册登录、非同源写请求拒绝和知识库名称校验
7. 创建两个知识库并独立查询文档
8. 中文文件上传、路径名清理、PENDING 状态和原文下载
9. 普通聊天、提示词保留、可信 userId、会话归属和无归属旧记录保护
10. 最近六轮按消息编号排序，模型窗口受限且数据库完整历史保留
11. 同一会话并发请求按完整轮次协调，后续模型请求看到已保存历史
12. SSE start/delta/done 协议与保存顺序，模型失败、半截流、取消及超时不写成功记录
13. 真实数据库约束触发普通/SSE 整轮事务回滚，失败不发 done，解除故障后恢复
14. 跨用户列表、读取、下载、上传和跨知识库编号访问被拒绝
15. 第二个用户可以正常管理自己的资料
16. 缺失、空文件、类型、超限和错误编号校验，无原文件残留
17. 真实数据库约束失败：无文档记录，刚保存的文件已清理
18. 待处理结果查询、解析来源校验和跨用户/跨库解析权限
19. UTF-8 中文、BOM/换行规范化、Markdown 原文与字符数
20. 真实 PDFBox PDF 与 POI DOCX 提取文本
21. 损坏/加密/无文字 PDF、损坏 DOCX、非法编码、空白与超限：失败原因持久化，不保存部分文本
22. RUNNING 占用拒绝重复解析，中断超时后可重新解析
23. 写入解析结果失败不误报成功，解除故障后重试恢复
24. Java 重启后 Redis 登录恢复，知识库、解析文本和原文件仍存在
25. 退出只撤销当前 Token，原文件和解析文本拒绝访问，其他终端继续有效
26. Redis 不可用时读取和登录明确 503，不回落内存，连接恢复后原 Token 有效
27. 真实 Edge：注册、登录、sessionStorage、普通聊天和两个 SSE 页面
28. 真实 Edge：上传、解析、读取文本、携带请求头下载、刷新和退出清理
29. 新页面 JavaScript 语法检查

共 29 组通过。报告 `target/knowledge-test-39f3b4de/result.json`，首次和重启日志同目录，真实 Edge 截图 `knowledge-page.png`。Maven 完整日志保存在忽略目录 `data/module-build.log`。本工程没有 JUnit 测试，以上业务验证由端到端脚本实际完成。

## 数据和改动保护

- 迁移前副本：`data/module-migration-baseline-2026-10-31T09-24-25-680Z`，移动清单与 SHA-256 位于其中 moved-files.json。
- 62 个文件迁移，58 个内容字节完全一致。其余四个只调整全局 Web 组件包名/公共异常 import、启动类注释、HTTP 上传样例相对路径。
- 用户业务 Service、Mapper、DO、模型客户端、系统提示词、解析器、文件存储、配置、数据库脚本和静态页均保留原内容。业务库和 Redis 原有键没有被测试写入或清理。
- 原 src/main.zip 完整归档到 docs/archive/main.zip。HTTP 请求样例集中到 docs/http。根目录旧 src 中的空目录已清理，没有残留旧源码参与构建。
- 独立测试容器、端口与文件目录；本机假模型，无真实付费模型调用。测试只清理自己创建的容器和应用。

## 仍需由本机开发环境确认

- IDEA 重新加载根 POM，运行类路径切换到 bootstrap，工作目录保持工程根目录，沿用原有环境变量；本轮未自动编辑你的 IDEA 私有运行配置。
- 实际启动验证使用 java -jar；文档中的 Maven run 路径已配置，但未另外执行 Maven run 启动。IDEA HTTP Client 脚本未在 IDEA 内自动执行。
- 没有对用户现有数据库执行写入回归，没有重新调用真实模型供应商；多 JVM 并发、独立前端跨域和 Redis 自身断电恢复均不在本轮验证范围。

建议手动提交说明：`refactor: split backend into Maven modules`。
