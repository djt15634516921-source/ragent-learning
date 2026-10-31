# 登录身份与会话归属（迁移后）

Sa-Token 检查“请求是谁发的”；业务层带用户条件检查“这个人能否使用这条会话、知识库或文档”。登录成功不能访问他人的资源。

## 注册和登录

注册：AuthController.register → AuthService → AuthServiceImpl.register → 校验账号和密码 → BCryptPasswordEncoder.encode → UserMapper.insert。PostgreSQL 自增主键由 MyBatis-Plus 回填，返回 UserVO，不返回密码哈希。账号统一小写，密码按原样处理。

登录：AuthController.login → AuthServiceImpl.login → UserMapper.selectOne → PasswordEncoder.matches → StpUtil.login → Redis。密码正确才建立会话。原有 BCrypt 哈希继续有效，不修改用户密码。

登录接收 JSON，不再使用表单登录过滤器。响应 LoginVO 包含 token 和 expiresIn，页面存入当前标签页 sessionStorage。后续请求显式携带 `Authorization: Bearer <token>`；Cookie、请求体、URL 均不能替代此请求头。

## 后续请求与 worker

AuthConfig 的 MVC 拦截器调用 StpUtil.checkLogin → Redis 恢复 userId → Controller 用 StpUtil.getLoginIdAsLong → Service 显式接收 userId → Repository/Mapper 校验资源归属。

SSE 请求线程先取得可信 userId，再传给 worker。worker 不读取请求线程认证上下文。容器 ASYNC/ERROR 二次分派不重复认证，原始请求已检查。

## 保存和退出

默认 Redis 键前缀 `ragent-learning:auth:`，绝对有效期 8 小时，不自动续期。同账号每次登录拿不同 Token，多端并行；退出仅撤销当前请求 Token。

Java 重启不清 Redis 会话。Redis 自身若丢失数据，相应用户需重登；Redis 数据持久化由部署决定。本轮验证了 Java 重启，没有声称 Redis 无持久化重启也能恢复。

浏览器刷新保留 sessionStorage，关闭标签页丢弃本地凭证，服务端会话到期清理。换域名、端口或新标签页按独立登录处理。401 清理 Token；503 不冒充成功，不回落内存。

## CSRF 和跨域

当前凭证由脚本显式放入请求头，浏览器不自动携带认证 Cookie；第三方站点不能直接读取本页 sessionStorage，也没有任意来源 CORS 授权。写请求若有 Origin，必须匹配当前服务，登录和注册也检查。

旧 Cookie/CSRF 交换已移除，`GET /api/auth/csrf` 返回 410。下载必须 fetch 携带 Bearer 后保存 Blob。未来改 Cookie 或独立前端来源时，必须重新设计 CSRF 和允许来源，不能只改一个开关。

## 排错

| 错误 | 排查 |
| --- | --- |
| 400 USERNAME_INVALID / PASSWORD_INVALID | 注册输入和 BCrypt 72 字节边界 |
| 401 AUTH_INVALID_CREDENTIALS | 账号密码错误，不会建立会话 |
| 401 AUTH_REQUIRED | Token 缺失、到期或已退出；重新登录 |
| 403 ORIGIN_DENIED | 同源访问，不混用 localhost 和 127.0.0.1 |
| 404 *_NOT_FOUND | 资源不存在或归属不匹配 |
| 410 AUTH_PROTOCOL_CHANGED | 旧 csrf 调用，更新页面/请求 |
| 503 AUTH_SESSION_UNAVAILABLE | Redis 地址、端口、密码和连通性 |
| 503 AUTH_DATABASE_UNAVAILABLE / DATABASE_UNAVAILABLE | PostgreSQL 连接及用户查询 |

完整结构与验证见 [迁移说明](10-migration-architecture.md)。独立 spring-security-crypto 只供 BCrypt；工程不再有 SecurityFilterChain、UserDetails 或 AuthenticationPrincipal。
