# 鉴权机制与用户资料

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 鉴权机制

### 登录流程

`POST /auth/login`（公开接口，无需 token）：接收微信 `wx.login()` 的 code → `WechatService` 调用微信 `jscode2session` 换取 openid → 查找/创建用户 → 签发 HS256 JWT（payload 含 sub=userId, role, exp）→ 返回 token + UserInfo。

### 请求鉴权（软鉴权模式）

`AuthInterceptor` 拦截所有请求但**从不拦截**（`preHandle` 始终返回 `true`）：有 `Authorization: Bearer <token>` 时尝试解析 JWT → 校验签名和过期时间 → 查询用户 → 写入 `UserContext`（ThreadLocal）；token 缺失/无效/过期时视为匿名访问，请求继续。

- 公开接口：直接读取 `UserContext.getCurrentUserId()`（未登录时为 `null`）
- 需登录接口：Service 层显式调用 `UserContext.requireAuth()`，未登录时抛出 `AuthRequiredException` → `GlobalExceptionHandler` 返回 HTTP 401 + `{"code":1002, "message":"请先登录"}`
- 需管理员接口：调用 `UserContext.requireAdmin()`（未登录 401，非管理员 403）
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

