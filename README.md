# Ragent 学习工程 · 第 1 课

## 这一版做到了什么

这一版提供独立的 Maven/Spring Boot 工程和一个问候接口。代码已经包含依赖、配置、启动类、Controller、Service 和构造方法注入。

访问 `http://localhost:8081/api/hello?name=Du`，预期返回：

```text
你好，Du。Ragent 学习工程已启动。
```

这是请求调用链的练习，返回内容由本地 Java 方法生成。模型调用、数据库和 RAG 能力会在后续课程逐步加入。

## 1. 在你的电脑上打开

1. 将压缩包解压到 `D:\jastudy\idea`。解压后找到包含 `pom.xml` 的目录；可把它重命名为 `ragent-learning`。
2. 在 IDEA 中用 **File → Open** 打开该目录，或打开其中的 `pom.xml` 并作为项目导入。
3. 沿用原 Ragent 能运行的 JDK 和 Maven 配置。这个工程的 Java 编译目标为 17；建议先使用原项目同一套 JDK。
4. 在 Maven 面板加载/重新加载依赖，等依赖解析结束。
5. 找到 `RagentLearningApplication`，运行它的 `main` 方法。
6. 浏览器访问 `http://localhost:8081/api/hello?name=Du`。

Spring Boot 版本 `4.1.0` 和 Web MVC 依赖名称来自此次提供的 Ragent 源码。首次加载需要下载依赖；Maven 仓库和镜像沿用你已经跑通原项目的配置。

## 2. 也可以使用命令启动

如果已经配置好 Maven，在项目根目录运行：

```powershell
mvn spring-boot:run
```

工程也带有原项目的 Maven Wrapper。Windows PowerShell 可运行：

```powershell
.\mvnw.cmd spring-boot:run
```

Wrapper 首次使用可能需要下载 Maven。JDK 必须完整可用，IDEA 运行时也要选择正确的项目 SDK。

## 3. 本课只重点看四个位置

| 文件 | 职责 | 这次要理解什么 |
|---|---|---|
| `pom.xml` | 声明依赖和构建方式 | Web 能力从哪里来，版本由谁管理 |
| `RagentLearningApplication.java` | 启动应用 | 组件为何能被 Spring 找到 |
| `HelloController.java` | 接收请求和返回响应 | URL、参数、Service 调用如何对应 |
| `HelloService.java` | 实现当前业务 | 业务逻辑为什么放在这里 |

`application.yaml` 配置应用名和端口，默认端口为 8081。若端口冲突，可修改这个端口并使用修改后的地址访问。

## 4. 顺着一次请求理解工程

浏览器发送 GET `/api/hello?name=Du` → Spring MVC 找到 `HelloController.hello` → 绑定参数 `name` → 调用 `HelloService.greet` → 返回字符串 → 浏览器显示结果。

再看启动时发生了什么：

1. `@SpringBootApplication` 启动自动配置和组件扫描。
2. `HelloService` 位于启动类所在包的子包中，`@Service` 使它被注册为 Spring 管理的组件。
3. Spring 创建 `HelloController` 时发现构造方法需要 `HelloService`，便传入这个对象。
4. 请求到达时，Controller 已经持有可调用的 Service。

所以，构造方法注入发生在对象装配阶段；收到每个请求时，Controller 使用已经装配好的依赖处理请求。

## 5. 怎样与原 Ragent 对应

| 学习工程 | 原项目对应职责 |
|---|---|
| `HelloController` | 类似 `RAGChatController` / `AgentChatController` 的 HTTP 入口 |
| `HelloService` | 类似问答业务层，之后会组织更多组件 |
| 一个工程中按包分工 | 原项目进一步拆为 `rag`、`agent`、`infra-ai` 等 Maven 模块 |
| 普通字符串响应 | 原项目还支持 SSE 事件和更丰富的数据结构 |

先通过小工程理解边界，后续每增加一项能力都标明它在原项目中的对应位置。你已经跑通的原项目作为效果和源码参照。

## 6. 今天怎样验收

- [ ] 工程成功启动，日志中的端口与访问地址一致。
- [ ] `/api/hello` 返回使用默认名字的问候语。
- [ ] `/api/hello?name=Du` 返回包含 `Du` 的问候语。
- [ ] 能找到请求进入的 Controller 方法。
- [ ] 能指出问候语在哪个 Service 方法里生成。
- [ ] 能解释 Service 对象怎样进入 Controller。

本课不要求你重新手写这些代码。请你先判断下面的需求应该改哪里，再由 AI 协助实现：

> 把欢迎语改成“欢迎你，Du，开始学习 Ragent”。

完成后重新访问接口，确认行为发生预期变化。这是一次“提出设计—AI 修改—运行验收”的小练习。

## 7. 如果运行失败，先收集这些证据

| 现象 | 先看哪里 |
|---|---|
| Maven 依赖一直无法解析 | Maven 面板的具体报错、仓库/镜像和使用的 Maven 配置 |
| 提示 JDK 或编译版本错误 | IDEA 的 Project SDK、Maven 使用的 JDK、运行配置 |
| 提示端口被占用 | 启动日志；调整 `application.yaml` 中的端口 |
| 浏览器连接失败 | 应用是否仍在运行、日志中的实际端口 |
| 返回 404 | 地址是否为 `/api/hello`，是否启动了这个学习工程 |

把具体报错和复现步骤发给 AI，按证据逐项定位。

## 8. 后续如何推进到完整项目

每轮使用相同节奏：原项目体验 → 写清输入输出和职责 → AI 实现 → 运行同类用例 → 解释设计 → 验证一个变化或失败场景。

后续顺序：非流式模型调用 → SSE → 会话保存 → 文档入库 → 向量检索与 RAG → Agent 与 MCP → 取消、容错、并发和评估。

“学会”的验收是能解释主流程、定位代码和数据、组织 AI 完成变更，并通过真实结果判断是否完成；每个阶段都会检查这些能力。

## 本次生成时的验证情况

已核对父 POM 与 Web MVC 依赖名称，检查 XML、源码文件与目录、配置和压缩包完整性。生成环境没有 Maven 和独立的 `javac` 命令，尚未完成实际 Maven 构建与启动；需要在你已经配置好的 IDEA 环境中验证。运行后如有问题，我们继续据实际报错修正。
