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

**因此 `/auth/login` 必须保持幂等可重入**：同一 openid 重复调用只应返回新 token 与同一用户，不得产生任何副作用（不得重复建号、不得累加计数）——`findByOpenIdAndDeletedFalse().orElseGet(createUserOrReadConcurrent)` 满足，改动时须保持。

**登录不开事务（2026-10-01）**：旧实现 `login` 整体 `@Transactional`，微信 `code2Session`（读超时 10s）期间持有数据库连接——池只有 5 个连接，每次冷启动都静默登录，微信一抖动即可占满整池、全站超时。现在远程调用在事务外完成，查/建用户各自是仓库层单语句事务；首登并发建号由 `open_id` 唯一约束兜底，撞键（`DbConstraintViolations.isUniqueViolation`，MySQL 1062）即回读已建好的行。同一规则见 13-code-standards「事务边界」。

**Web 后台密码登录限速（2026-10-01）**：`WebAuthService#passwordLogin` 按**来源 IP**（`ClientIpResolver`，可信代理由 `server.forward-headers-strategy` 声明）记失败次数，窗口内失败满 `web-auth.password-login.max-failures`（默认 5）次即锁到窗口结束（`lockout-minutes` 默认 15），返回 1006；成功即清零。按 IP 而非用户名计数——用户名只有一个，按用户名锁 = 任何人都能把唯一的管理员锁在门外。扫码登录会话改为「先向微信生成小程序码、再落会话行」，外呼不再持有连接。

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

### 管理端用户辨认（2026-09-29）

**问题**：昵称零辨认力。生产实测（RDS 只读，2026-09-29）——存活用户 **873**，其中昵称仍是注册默认值 **857（98.2%）**、自定义昵称仅 15、有头像 8；近 30 天有主动行为 801 人里 790 人完全匿名。管理端列表一行行「微信用户」无法区分谁是谁。

**根因不是「没有能力」，是「没有动机」**：微信头像昵称填写能力（`open-type="chooseAvatar"` + `input type="nickname"`）小程序端已全落地（见 `pages/profile-edit`），但昵称在本平台**不出现在任何用户能获得回报的场景**（UGC 一律平台代发 ⇒ 昵称不对外展示），改了没好处、路径还深达 5 步，故转化率停在 1.7%。**结论：不要靠"引导改昵称"解决辨认问题。**

**三层辨认手段（按覆盖率排列）**

| 层 | 手段 | 覆盖 | 落地 |
|----|------|------|------|
| ① | **用户代号 `U#00472`**（`id` 派生、终身不变、零迁移） | **100%** | `UserCode.format` → `AdminUserItem.userCode` / `AdminUserDetailResponse.userCode` |
| ② | **常去门店**（近 90 天动作次数最多的门店，阈值 ≥2） | **21%**（实测 185/873；Top1 去重 43 家店） | `AdminUserStatsService#topVenuesFor` → `TopVenue` |
| ③ | **场景真实身份**（认领 / 舞伴入驻 / 招工已收姓名 + 电话 + 微信） | 约 2% | `qwt_venue_claims` / `qwt_dancers` / `qwt_recruitments`，既有 |

**代号规则（`UserCode`，唯一权威）**：格式 `U#` + id 补零到 5 位，超过 5 位**不截断**（不与已发出的老代号割裂）；搜索接受 `U#00472` / `u#472` / `U472` 三种写法，**纯数字不识别为代号**（否则「搜昵称 123」会被劫持成「找 id=123 的用户」）；默认昵称常量 `DEFAULT_NICKNAME` 由注册链路（`AuthService`）与判定逻辑（`UserCode.isDefaultNickname`）**共用同一处**——改默认值只改这里，否则老用户的默认昵称会被误判成自定义昵称。

**搜索契约**：`GET /admin/users?keyword=U#00472` 走代号精确命中（Service 层解析成 id 后单点返回，**不进列表 SQL**——六条排序/筛选变体各加一个 `OR u.id = :id` 会让签名与 `UserStatsSqlMirrorTest` 反射断言一起膨胀）；仍受 role / city / 活跃窗口三道筛选约束（代号只是定位方式，不是绕过筛选的后门）。

**前端渲染契约**：后端下发 `nicknameCustom` 布尔，前端据此决定主标题显示昵称还是代号——**禁止前端再写一份「默认昵称」字面量**（那必然与后端漂移）。无自定义昵称时主标题即代号，`userCode` 不再重复展示。

**红线**：代号与 `TopVenue` 只在管理端（`requireAdmin`）下发；`TopVenue` 只含门店名与城市（门店是平台公开黄页数据），**不含用户坐标、不含 openId**。「常去门店」窗口内事件刻意**包含非活跃档**（认领 / 上报暂停）——辨认要的是「他对哪家店有过动作」，只认领过门店、从没浏览过的人恰恰是最需要被认出来的那类。

**已知边界（可选项）**：阈值 2 时覆盖 21%；降到 1 可覆盖 52%（453 人有门店维度行为），但单次浏览不构成「常去」，会给出大量弱信号且文案需改为「去过」而非「常去」。当前取 2 以保证标签精确。

---

