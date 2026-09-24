# 鉴权机制与用户资料

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 鉴权机制

### 登录流程

`POST /auth/login`（公开接口，无需 token）：接收微信 `wx.login()` 的 code → `WechatService` 调用微信 `jscode2session` 换取 openid → 查找/创建用户 → 签发 HS256 JWT（payload 含 sub=userId, role, exp）→ 返回 `token` + `expiresIn` + UserInfo。

**响应必须携带 `expiresIn`（秒，OAuth2 `expires_in` 语义，2026-09-24 新增）**：客户端据此落盘本地过期时刻，从而能判断"凭证是否仍然有效"并在失效前主动静默续期。此前只返回 `token`，客户端无从预判，只能被动等某个请求 401 才发现掉线——这是「隔几天登录状态就过期且无法自动续期」的**契约缺口**。`expiresIn` 的唯一事实源 = `JwtUtil.getExpiresInSeconds()`（读 `jwt.expiry-days`），禁止在 Service/DTO 另写常量。

`exp` 的单位契约：**epoch 毫秒**（非 RFC 7519 的 NumericDate 秒）。本项目 token 无第三方消费方（客户端不解析、不经 JWT 网关），毫秒与 `System.currentTimeMillis()` 同量纲、免换算；**对外契约一律用标准 `expiresIn`（秒）表达**——对外标准、对内简单。

### 会话续期（2026-09-24 契约）

**后端不提供 refresh token 端点**：`POST /auth/login` 同时承担「首次登录」与「凭证过期后续期」两条链路——微信 `jscode2session` 是随时可执行的静默能力（无授权弹窗），等价于一个"永远可用的续期凭证"，再引入 refresh token 只会多出轮换/撤销/存储一致性三类状态而收益为零。届时有端（如 Web 管理后台）需要治理会话时，判据与做法见前端仓 `docs/auth-and-user.md`「为什么不用 refresh token」。

**因此 `/auth/login` 必须保持幂等可重入**：同一 openid 重复调用只应返回新 token 与同一用户，不得产生任何副作用（不得重复建号、不得累加计数）——`findByOpenIdAndDeletedFalse().orElseGet(createUser)` 的现状即满足，改动时须保持。

### 401 重放安全契约（重要，禁止破坏）

客户端在收到 401 后会**静默续期并重放原请求**（含 POST 写操作）。该重放之所以安全，依赖一条后端不变量：

> **401 只可能由 `UserContext.requireAuth()` 抛出，而它必须是 Controller/Service 方法中
> 任何副作用（写库、发消息、外部调用）与事务提交之前的第一个语句。**

即：`requireAuth()` 抛 401 时，服务端从未执行过该请求的任何写操作，重放不会造成重复提交。
新增写接口时**必须**把 `UserContext.requireAuth()` / `requireAdmin()` 放在方法首位——
放在中间会让"401 之后其实已经写了一半"成为可能，届时客户端重放将产生重复副作用
（且是静默发生的）。`@Transactional` 方法同理：鉴权在事务开始前完成。

不满足此不变量的接口不能依赖客户端的 401 自愈，必须自行处理（当前**全仓无可例外接口**）。

### 请求鉴权（软鉴权模式）

`AuthInterceptor` 拦截所有请求但**从不拦截**（`preHandle` 始终返回 `true`）：有 `Authorization: Bearer <token>` 时尝试解析 JWT → 校验签名和过期时间 → 查询用户 → 写入 `UserContext`（ThreadLocal）；token 缺失/无效/过期时视为匿名访问，请求继续。

- 公开接口：直接读取 `UserContext.getCurrentUserId()`（未登录时为 `null`）
- 需登录接口：Service 层显式调用 `UserContext.requireAuth()`，未登录时抛出 `AuthRequiredException` → `GlobalExceptionHandler` 返回 HTTP 401 + `{"code":1002, "message":"请先登录"}`。**401 = 唯一的"凭证失效"信号**，客户端据此续期重放（见上「401 重放安全契约」）——因此 401 不得用于表达"权限不足"等其它语义（非管理员一律走 `1003` + HTTP 200，见 `requireAdmin()`）
- 需管理员接口：调用 `UserContext.requireAdmin()`（未登录 401，非管理员 403 业务码）
- 请求结束后 `afterCompletion` 自动清除 ThreadLocal

此设计适配黄页类产品：浏览无需登录，仅操作类接口（收藏、管理等）按需校验身份。

### 微信 API 调用规范

`WechatService` 调用微信开放接口时遵循以下约定（针对微信 API 的已知坑位）：

- **响应体统一以 `String.class` 接收，再用注入的 `ObjectMapper` 手动解析**。微信 API 响应的 Content-Type 不可靠（`jscode2session` 已知会以 `text/plain` 返回 JSON 体），RestClient 默认的 Jackson 转换器仅接受 `application/json`，直接 `.body(XxxResponse.class)` 会抛 `UnknownContentTypeException`。`String.class` 由 `StringHttpMessageConverter` 处理，兼容任意 Content-Type。
- 外部 API 调用的 RestClient **必须配置超时**（当前约定：connect 5s / read 10s，见 `WechatService` 常量），避免微信接口无响应时阻塞请求线程。
- 失败模式统一转换为 `BusinessException`：微信业务错误码（`errcode != 0`）→ `1003`，响应解析失败/无响应 → `5001`。禁止将底层异常（`UnknownContentTypeException`、`ResourceAccessException` 等）直接透传给全局异常处理器。

### 配置

```yaml
wechat:
  appid: wx054a26bf9b1424dc   # 公开信息
  secret: ${WECHAT_SECRET}    # 环境变量注入，无默认值（未设置则启动失败）

jwt:
  secret: ${JWT_SECRET}       # 环境变量注入，无默认值（未设置则启动失败）
  expiry-days: 7
```

`JwtUtil` 构造函数强制校验 secret 非空且长度 ≥ 32 字符，不满足则抛 `IllegalStateException` 拒绝启动——防止遗漏环境变量时以空密钥签发 token。

### 角色

`UserRole` 枚举：`ADMIN`（超管）/ `USER`（普通用户）。超管在数据库 `qwt_users` 表中手动设置 role 字段，前端不做管理入口。写操作接口的角色校验由后端负责。

---


---
## 用户资料

### 产品定位（根因）

本应用是**黄页工具**，用户资料以昵称为主，**头像为选填**社交属性（2026-08-12 修正：此前文档误记「无头像」——`UserInfoResponse` 已含 avatarUrl，`qwt_users.avatar_url` 由 `POST /user/profile` 正常读写，微信 `chooseAvatar` 选图后直传 Supabase 落库）。文件上传分类已扩展至用户头像 / 舞伴照片 / 认领营业执照（见 FileCategory），不再仅限场所图片。

### 资料来源（平台约束）

微信 `jscode2session` **只返回 openid**，登录链路天然拿不到昵称（`getUserProfile` 等客户端接口已被微信废弃）。注册时昵称默认"微信用户"是**正常初始态**，昵称由用户在小程序端主动提交后经 `POST /user/profile` 写入——不要在登录链路中尝试获取或要求前端随登录上送资料。

### 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/user/me` | 返回当前用户最新信息，需登录。客户端用户态与服务端的唯一同步通道 |
| POST | `/user/profile` | 更新昵称（必填），需登录，返回最新 UserInfo |

### 用户态刷新约定（重要）

客户端缓存的用户信息仅在登录时写入一次快照，服务端单方面变更（数据库调整角色等）不会自动同步——`GET /user/me` 即为此设计的同步通道，前端在页面 onShow 时静默调用。新增任何影响 `UserInfoResponse` 的服务端写操作后，无需额外通知机制，客户端下次刷新即自愈。

---

