# AGENTS.md — quwuting-service

Spring Boot 4.1 + Java 25 + Spring Data JPA 后端服务。  
为趣舞厅微信小程序提供舞厅信息查询 REST API，当前阶段仅涉及数据展示，无交易/支付逻辑。

---

## 构建与运行

```bash
# 编译
./mvnw clean compile

# 运行测试
./mvnw test

# 打包（跳过测试）
./mvnw clean package -DskipTests

# 本地启动（需先配置 application-dev.yaml 数据源）
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

> 生产部署流程见「配置管理 → 生产部署」章节。

---

## 包结构

根包：`org.quwuting.quwutingservice`

```
base/           ← 抽象基类（BaseEntity：id / createdAt / updatedAt / deleted）
common/         ← 通用响应体（ApiResponse<T>）
exception/      ← 自定义异常类 + @RestControllerAdvice 全局处理
config/         ← Spring @Configuration 类（WebMvc、拦截器注册）
security/       ← JWT 工具、AuthInterceptor、UserContext（ThreadLocal 用户上下文）

venue/          ← 场所模块（按功能分包）
  controller/   ← REST 控制器，只定义路由和参数绑定，不含业务逻辑
  service/      ← 业务逻辑，调用 repository 并转换 DTO
  mapper/       ← Entity → Response DTO 转换器（@Component，跨模块复用）
  repository/   ← Spring Data JPA 接口，继承 JpaRepository
  entity/       ← JPA @Entity 实体类，映射数据库表
  dto/
    request/    ← 请求体 DTO（Java record）
    response/   ← 响应体 DTO（Java record），禁止直接暴露 Entity
  enums/        ← 业务枚举

user/           ← 用户模块
  controller/   ← UserController（GET /user/me 用户态刷新, POST /user/profile 昵称更新）
  service/      ← UserService（用户信息查询、昵称更新）
  mapper/       ← UserInfoMapper（User → UserInfoResponse，auth / user 模块共用）
  dto/
    request/    ← UpdateProfileRequest（仅昵称，产品无社交属性不含头像）
    response/   ← UserInfoResponse（登录、/user/me、资料接口共用响应体）
  entity/       ← User 实体（qwt_users）
  enums/        ← UserRole 枚举
  repository/   ← UserRepository

auth/           ← 认证模块
  controller/   ← AuthController（POST /auth/login）
  service/      ← AuthService + WechatService（调用微信 jscode2session）
  dto/
    request/    ← LoginRequest
    response/   ← LoginResponse（user 字段复用 user 模块的 UserInfoResponse）

favorite/       ← 收藏模块
  controller/   ← FavoriteController（GET /favorites, POST /favorites/{id}, POST /favorites/{id}/remove）
  service/      ← FavoriteService
  entity/       ← Favorite 实体（qwt_favorites）
  repository/   ← FavoriteRepository

venuepost/      ← 场所动态模块（公告 / 通知）
  controller/   ← VenuePostController（GET /venues/{venueId}/posts）
  service/      ← VenuePostService
  entity/       ← VenuePost 实体（qwt_venue_posts）
  repository/   ← VenuePostRepository
  dto/
    response/   ← VenuePostResponse
  enums/        ← PostPublisherType（OWNER 商家 / ADMIN 平台）

venuefeedback/  ← 场所信息纠错反馈模块
  controller/   ← VenueFeedbackController（POST /venues/{venueId}/feedbacks）
  service/      ← VenueFeedbackService
  entity/       ← VenueFeedback 实体（qwt_venue_feedbacks）
  repository/   ← VenueFeedbackRepository
  dto/
    request/    ← CreateFeedbackRequest（type + note）
    response/   ← VenueFeedbackResponse
  enums/        ← FeedbackType（CLOSED_DOWN / SUSPENDED / INACCURATE / OTHER）

venuestatusreport/  ← 场所状态众包上报模块（实时暂停信号，4h TTL）
  controller/   ← StatusReportController（POST /venues/{venueId}/status-reports, POST .../cancel）
  service/      ← StatusReportService（upsert 上报、撤销、活跃统计、频率限制、@CacheEvict 热度缓存）
  entity/       ← VenueStatusReport 实体（qwt_venue_status_reports）
  repository/   ← StatusReportRepository（含活跃计数+最新时间合并投影查询）
  dto/
    request/    ← SubmitReportRequest（reason + occurredAt + note，全可选）
    response/   ← ActiveReportSummary（公开）/ StatusReportResponse（管理端，含 note）
  enums/        ← ReportReason（CHECK 门店检查 / UNKNOWN 情况不明 / CLEARED 清场）

taginteraction/ ← 标签交互模块（点赞 + 维度评分）
  controller/   ← TagInteractionController（GET /venues/{id}/tags/stats, POST .../like, POST .../score）
  service/      ← TagInteractionService（toggle 点赞、upsert 评分、个人交互状态实时查询、批量点赞数）
                  TagAggregateStatsService（场所级聚合数据的 @Cacheable 专属 Bean，见「标签交互」章节）
  entity/       ← TagInteraction 实体（qwt_tag_interactions）
  repository/   ← TagInteractionRepository（含 GROUP BY 聚合查询、批量多场所聚合查询）
  dto/
    request/    ← LikeTagRequest / ScoreTagRequest
    response/   ← TagStatsResponse / TagLikeStats / DimensionScoreStats / WindowScore
  RatingDimensions ← 系统评分维度常量（服务、环境、音响效果、性价比）

storage/        ← 文件存储模块（前端直传 Supabase Storage，后端仅签发凭证）
  StorageController  ← GET /storage/upload-token（需登录，签发上传凭证）
  StorageService     ← 校验文件类型/大小、生成唯一路径、返回凭证
  StorageProperties  ← @ConfigurationProperties(prefix = "supabase.storage")
  FileCategory       ← 文件分类枚举（VENUE_COVER / VENUE_PHOTO / VENUE_QR）
  UploadTokenResponse ← 凭证 DTO（projectUrl / anonKey / bucket / uploadPath / publicUrl）
```

每个功能模块内部遵循分层：`controller → service → repository → entity`。  
禁止 controller 直接调用 repository，禁止 entity 依赖任何上层包。  
跨模块复用通过 `mapper/` 组件或 service 层注入实现。

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

## 用户资料

### 产品定位（根因）

本应用是**黄页工具**，不涉及社交——用户资料仅含昵称，**无头像**等社交属性。`UserInfoResponse` 不含 avatarUrl 字段。数据库 `qwt_users.avatar_url` 列为历史遗留（不再读写），后续迁移时清理。文件上传仅服务于场所图片（封面、相册、二维码），不涉及用户社交属性。

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

## 门店认领与管理权限

### 数据模型

`Venue.claimedBy`（`Long`，可空）：认领人用户 ID，引用 `qwt_users.id`，`null` 表示未被认领。认领后该用户获得门店管理权（发布动态、编辑信息等），与平台管理员共享管理入口可见性。

### 权限判定规则（canManage）

详情接口 `GET /venues/{id}` 返回 `VenueDetailResponse(venue, canManage, postCount)`，其中 `canManage` 由后端基于软鉴权上下文计算：

1. 平台管理员（`UserRole.ADMIN`）→ 对所有门店为 `true`
2. 门店认领人（`claimedBy` 等于当前用户 ID）→ 对该门店为 `true`
3. 匿名用户 / 其他用户 → 恒为 `false`

`canManage` 仅驱动前端管理入口的**展示**，安全边界在后端各写操作接口的角色校验。

### 管理写操作权限校验（requireManageOrAdmin）

所有管理写接口（场所更新、动态发布等）统一调用 `UserContext.requireManageOrAdmin(venue.getClaimedBy())`：先 `requireAuth()` 确保已登录，再判定 ADMIN 角色或 claimedBy 匹配，否则抛 1003。此方法是管理写操作的标准权限入口，新增管理接口时必须使用。

### 场所更新接口

`POST /venues/{id}/update`（管理员或认领人）：全量覆盖可编辑字段，请求体复用 `CreateVenueRequest`（字段相同）。`claimedBy` 不在此接口变更（认领流程另行约定）。`status` / `sortWeight` 为 null 时保留原值不覆盖。

### 详情接口与列表接口的分工

- `GET /venues`（列表）→ `Page<VenueResponse>`：不含权限与统计字段，保持轻量
- `GET /venues/{id}`（详情）→ `VenueDetailResponse`：组合 `VenueResponse` + `canManage` + `postCount`

record 不支持继承，组合结构（`VenueDetailResponse(VenueResponse venue, ...)`）是详情扩展的标准模式，后续新增详情专属字段一律追加到此 record。

---

## 场所动态（venuepost 模块）

### 设计定位

动态是门店的**公告 / 通知**流，发布方分两类：

- `OWNER`（商家）— 门店认领人发布，如招聘、活动预告
- `ADMIN`（平台）— 平台管理员发布，如规范提醒、处罚公告

`VenuePost` 冗余存储 `publisherName`（发布方展示名称），避免列表渲染时联表查询用户/门店。

### 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/venues/{venueId}/posts` | 分页查询动态（公开，按 createdAt 倒序） |
| POST | `/venues/{venueId}/posts` | 发布动态（管理员或认领人），请求体 `CreatePostRequest(title, content)` |

发布时 `publisherType` 与 `publisherName` 由后端根据角色自动判定（ADMIN → "去舞厅平台"，认领人 → 门店名），客户端不指定发布方身份。

---

## 场所热度（多维度统计）

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/venues/{id}/heat` | 公开 | 综合热度指数 + 分项统计 |
| POST | `/venues/{id}/view` | 软鉴权 | 记录详情页浏览（fire-and-forget） |

### 热度公式

```
heatScore = viewCount30d × 1
          + favoriteCount × 10
          + newFavoriteCount30d × 15
          + postCount × 5
          + ratingCount30d × 8
          + likeCount30d × 3
          + satisfactionScore × 20（无评分时为 0）
```

权重常量收敛在 `VenueHeatService` 内部，后续基于真实数据分布调优，接口路径与 `heatScore` 语义不变。

### 数据采集层

**浏览记录（`qwt_venue_views`）**：已登录用户按 `(venueId, userId, viewDate)` 联合唯一约束去重（同一天仅一条）；匿名用户 `userId=null`，每次访问均记录（无法去重，数据仅供参考）。前端进入详情页时 fire-and-forget 调用 `POST /venues/{id}/view`，失败静默。

**状态变迁日志（`qwt_venue_status_logs`）**：每次 `Venue.status` 字段变更时由 `VenueService` 自动写入（含创建时的初始记录 `fromStatus=null`）。记录 `fromStatus`、`toStatus`、`changedBy`、`createdAt`。用于统计"近 N 天暂停营业次数"和"当前状态持续天数"。

### 满意度计算

综合满意度 = 各维度（`RatingDimensions.ALL`）评分的等权均分，优先取近 30 天窗口数据，无近期数据时回退全量。评价总人数 < 3 时返回 `null`（前端展示"暂无足够评价"）。

### 统计口径：截至昨日（2026-07-31 确立）

`GET /venues/{id}/heat` 的所有滚动窗口指标（近30天浏览/收藏/动态/评价/点赞、近14天收藏趋势）统一以**昨天 24 点**为排他上界，而不是请求发生的"此刻"：

```java
LocalDate today = LocalDate.now();
LocalDate statsAsOfDate = today.minusDays(1);      // 展示给前端的"数据截至"日期
LocalDateTime windowEnd = today.atStartOfDay();    // 排他上界 = 今天 0 点 = 昨天 24 点
LocalDateTime since30d = windowEnd.minusDays(WINDOW_DAYS);
```

**根因**：当天数据是"过了一半的一天"，若窗口上界取"此刻"，会把这条不完整的当天数据和其余 29 条完整的历史整天数据混在同一个聚合/时间序列里——尤其是收藏趋势图，最新一根柱子必然比实际值偏低（不是因为收藏变少了，只是这天还没过完），用户会误读为"在下滑"。同一个场所在当天不同时刻多次请求也会得到不同的"近30天"统计结果，缺乏可复现性。

**规则**：
- 涉及"近 N 天"窗口统计的 Repository 查询方法必须同时接收 `since` 和 `until` 两个排他边界参数（`[since, until)`），不能只传 `since` 靠调用方自然到"现在"
- `until` 统一取 `LocalDate.now().atStartOfDay()`（即今天 0 点），由 `VenueHeatService.getHeat` 一处计算后传给所有子查询，禁止各查询各自计算
- **例外**：`VenueStatusLogRepository.countSuspensionsAndLatestTime` 的 `latestcreatedat`（当前状态持续天数的依据）代表"当前状态"这一实时事实，不是滚动窗口聚合，不受该上界约束，保持全量 `MAX`
- `VenueHeatResponse.statsAsOfDate`（`yyyy-MM-dd`，即昨天）随接口返回，**前端必须在热度 Tab 醒目展示**（当前实现：Tab 顶部 accent 底色横幅 + 底部数据说明重复提示），不得让用户在不知情的情况下把"含当天不完整数据"的口径当作完整统计解读

### 收藏趋势（VenueHeatResponse.favoriteTrend）

`GET /venues/{id}/heat` 在收藏总数/新增数之外，附带近 14 天每日新增收藏数的时间序列 `favoriteTrend: List<FavoriteTrendPoint(date, count)>`，供前端渲染收藏趋势图。

- 窗口取 14 天而非与热度其余指标一致的 30 天：趋势图是给人看"升降走势"的可视化，30 根柱子在小程序小屏图表上过密；14 天足够识别趋势且渲染负担更小。窗口常量 `VenueHeatService.TREND_WINDOW_DAYS` 独立于 `WINDOW_DAYS`，互不影响
- 窗口锚点为 `statsAsOfDate`（昨天），即 14 天窗口是 `[昨天-13, 昨天]`，不含今天——与上述"统计口径：截至昨日"约定一致
- 数据源：`FavoriteRepository.countDailyFavoritesSince`（原生 SQL `date_trunc('day', created_at)::date` 分组，JPQL 无法表达按天截断分组，原因已在查询注释中说明）
- **服务端补零**：`VenueHeatService.computeFavoriteTrend` 对没有收藏记录的日期补 `count=0`，保证返回序列总是 14 个连续日期点，前端无需处理"缺失日期"分支，柱状图天然对齐
- 与 `newFavoriteCount30d`（30 天窗口总数）同源但独立查询，不做互相推导——两者语义不同（一个是求和统计，一个是按天时间序列），保持查询职责单一

### 营业稳定性

- "暂停营业次数" = 近 30 天内（截至昨日）`toStatus = SUSPENDED` 的状态变迁记录数
- "当前状态持续天数" = 最近一条状态日志的 `createdAt` 距今天数——这是实时事实，不受"截至昨日"窗口约束（见上节）
- `SUSPENDED`（暂停营业）语义为被迫关门（警察检查等），与 `CLOSED`（休息中，正常未到营业时间）不同

### 状态可信度（StatusConfidence）

`VenueHeatResponse` 新增 `statusConfidence` 字段，向用户传达"当前状态信息有多可信"。枚举值：`HIGH` / `MEDIUM` / `LOW`。

计算逻辑为二维矩阵——稳定性（suspensionCount30d）× 当前状态持续天数（currentStatusDays）：

| | currentStatusDays 短（≤ 7 天） | currentStatusDays 长（> 7 天） |
|------|------|------|
| **稳定**（suspensionCount30d == 0） | HIGH | HIGH |
| **不稳定**（suspensionCount30d > 0） | MEDIUM | LOW |

- **稳定场所恒为 HIGH**：近 30 天无暂停记录的场所，无论最近一次状态更新距今多久，可信度都是 HIGH——稳定本身就是最强的可信信号，不需要额外要求"近期更新过"
- 不稳定场所才按 currentStatusDays 细分：状态刚切换不久（≤7 天）→ MEDIUM（近期确认过，虽然不稳定但刚有更新）；状态持续较长时间（>7 天）→ LOW（不稳定且长时间未确认，数据可能过时）
- `currentStatusDays` 沿用「营业稳定性」中"最近一条状态日志 createdAt 距今天数"的定义，是实时事实，不受"截至昨日"窗口约束
- **活跃上报覆盖规则**：当 `VenueHeatService` 从 `StatusReportService` 获取到 `activeCount > 0`（近 4 小时有用户报告暂停）时，`computeStatusConfidence` 直接返回 `LOW`，跳过上述矩阵——众包实时信号优先级高于历史稳定性矩阵。根因：矩阵基于历史暂停记录（管理员维护的 `Venue.status` 变迁），无法反映"此刻正在发生"的关门事件；用户现场上报正是为了弥补这一滞后

### 查询性能优化（条件聚合 + 本地缓存）

热度接口和标签统计接口的 DB 往返次数已通过**条件聚合**大幅压缩：

- `GET /venues/{id}/heat`：原 14 次串行 DB 往返 → 7 次（浏览 PV+UV 合并、收藏 total+30d 合并、动态 total+30d 合并、评价 rating+like+raters 合并、状态暂停+最新时间合并）
- `GET /venues/{venueId}/tags/stats`：原 7 次 → 4 次（评分全量+30d+7d 三窗口合并为一条 `AVG(CASE WHEN...)` 查询、用户点赞+评分合并为一条）

根因：服务器在阿里云 ECS，数据库在 Supabase（AWS ap-south-1），单次 DB 往返约 100-200ms，查询次数 × 延迟 = 接口总延迟。压缩查询次数是最直接有效的优化手段。

**本地缓存（Caffeine，60s TTL）**：`VenueHeatService.getHeat` 和 `TagAggregateStatsService.getAggregate` 加了 `@Cacheable`。热度/标签统计是聚合数据，60 秒内的精度偏差对用户无感知，但可避免同一场所短时间内的重复计算（多用户同时浏览、同一用户切换 Tab 等场景）。缓存配置在 `config/CacheConfig`，maxSize=500，TTL=60s。

**缓存内容的强制约束（2026-07-31 标签点赞"消失又恢复"事故后确立）**：被缓存的数据必须与请求者身份无关——只允许缓存所有用户共享的聚合结果（点赞总数、评分均值等），禁止把当前用户的个人交互状态（"我是否已赞"、"我的评分"）一并放进缓存值或缓存 key。个人状态必须在每次请求时实时查询（成本是一次简单的按 userId+venueId 查询，远低于聚合计算）。

根因：早期 `TagInteractionService.getTagStats` 以 `{venueId, userId}` 为 key 整体缓存（含 likedByMe/myScore），点赞/评分写操作未失效缓存，用户点赞后 60 秒 TTL 内会出现"返回列表再进入详情页看到操作前的状态"；因用户重新打开小程序的间隔通常 > 60s，缓存已自然过期，问题被掩盖，误判为"完全重启后恢复正常"是预期行为。修复方案：聚合数据（点赞计数、评分均值）拆到独立的 `TagAggregateStatsService`，以 venueId 为 key 缓存；个人交互状态在 `TagInteractionService.getTagStats` 中永远实时查询、不缓存；同时 toggleLike/score 写操作对聚合缓存做 `@CacheEvict`，不必等待 TTL 自然过期，保证点赞后所有用户很快就能看到最新点赞数。

**Spring `@Cacheable` 自调用陷阱（必须知晓）**：Spring 基于动态代理实现 AOP，方法内部通过 `this.xxx()` 调用同类的另一个 `@Cacheable`/`@CacheEvict` 方法会绕开代理，注解静默失效（不报错，但缓存/失效都不生效）。这是 `TagAggregateStatsService` 被拆成独立 Bean 而非 `TagInteractionService` 内部私有方法的直接原因——被缓存的方法必须从另一个 Bean 上调用。新增任何带缓存注解的方法时，若调用方和被调用方在同一个类里，先检查是否触发此陷阱。

**新增查询时的约定**：当一个 Service 方法需要同一张表的多个 COUNT/aggregation 时，优先用 `SUM(CASE WHEN condition THEN 1 ELSE 0 END)` 合并为单条 SQL，不拆多次往返。JPQL 不支持 `COUNT(DISTINCT CASE WHEN...)` 时，使用 `@Query(nativeQuery = true)` 并注明原因。

---

## 场所状态上报（venuestatusreport 模块）

### 设计定位

舞厅门店状态变更（警察检查、突然关门）发生频率远高于管理员手动更新 `Venue.status` 的能力——极端情况可能 30 分钟内多轮检查导致反复开关门。`venuefeedback` 模块是异步管理员审核流程（有 `handled` 状态），无法满足实时性需求。此模块提供**实时众包信号层**：用户在现场一键报告"现在关门了"，信号对其他用户即时可见。

**与 venuefeedback 的边界**（重要）：

- `venuefeedback.SUSPENDED` = "我不在场但认为状态信息有误"→ 异步管理员审核 → `handled` 布尔流转
- `venuestatusreport` = "我现在就在现场，刚确认关门"→ 实时 TTL 信号 → 自动过期，无需管理员介入

两者共存，语义边界清晰：一个走异步审核，一个走实时众包。`venuefeedback` 不重复承担实时信号职责。

### 独立信号层（不修改 Venue.status）

用户上报**不改变** `Venue.status` 字段。`Venue.status` 的变更权仍属管理员/认领人（`POST /venues/{id}/update`）。用户上报作为独立信号层，出现在热度接口中为"N人报告暂停"的众包标记。管理员可在管理后台查看活跃报告并决定是否据此手动更新 `Venue.status`（管理端接口后续约定）。

### TTL 语义（4 小时活跃窗口）

```java
LocalDateTime since = LocalDateTime.now().minusHours(ACTIVE_REPORT_TTL_HOURS);  // 4h
```

活跃报告 = `createdAt >= now - 4h`。这是**实时窗口**，锚点为请求发生的"此刻"，与「统计口径：截至昨日」的 `windowEnd = today.atStartOfDay()`（排他上界，排除当天不完整数据）是两套不同的时间语义——一个是实时状态信号，一个是历史聚合完整性。两者互不矛盾：

- 活跃报告数（`activeReportCount`）和最新报告时间（`latestReportTime`）是实时事实，不受"截至昨日"窗口约束
- 与 `currentStatusDays`（当前状态持续天数）同理：都是"当前状态"这一实时事实，而非滚动窗口聚合

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/venues/{venueId}/status-reports` | 需登录 | 上报暂停（body 可空=快速上报，或含 reason/occurredAt/note） |
| POST | `/venues/{venueId}/status-reports/cancel` | 需登录 | 撤销我的上报（软删除） |

### 数据模型

`qwt_venue_status_reports` 表：一个用户对一个场所至多一条活跃报告（`UNIQUE(userId, venueId)`），重新上报 = upsert 覆盖（刷新 `createdAt` 续期 TTL）。撤销 = 逻辑删除（`deleted = true`），再次上报 = 恢复（`deleted = false`），复用收藏模块的逻辑删除模式。

索引：`(venueId, createdAt)` 覆盖活跃计数查询，`(userId)` 覆盖用户频率限制查询。

### Upsert 语义（软删恢复模式）

`submitReport` 使用 `findByUserIdAndVenueId`（**不限 `deleted`**）查找已有记录，与 `FavoriteService.addFavorite` 同模式：

1. 找到**活跃记录**（`deleted=false`）→ 更新字段（reason/occurredAt/note）
2. 找到**软删记录**（`deleted=true`）→ 恢复：设 `deleted=false`、刷新 `createdAt = now` 续期 TTL、更新字段。频率限制检查仅在恢复时触发（恢复 = 新报告行为）
3. 未找到 → 新建 INSERT，catch `DataIntegrityViolationException` 处理并发竞态

**根因（为什么不查 `findByUserIdAndVenueIdAndDeletedFalse`）**：UNIQUE 约束 `qwt_uk_status_report_user_venue` 在 `(userId, venueId)` 上，不含 `deleted` 列——软删记录仍占用唯一槽位。若仅查活跃记录，撤销后再次上报会走到 INSERT 分支，与软删记录冲突。`FavoriteService` 的 `findByUserIdAndVenueId`（含软删）+ 恢复模式是标准做法，此模块此前遗漏了此模式导致 `AssertionFailure` 崩溃。

**`@CreationTimestamp` 仅 INSERT 时设值**：恢复软删记录时需手动 `setCreatedAt(now)` 刷新 TTL，否则旧 `createdAt` 可能已超过 4h TTL，恢复后立即"过期"。

**`DataIntegrityViolationException` catch 后必须 `entityManager.clear()`**：`save()` 失败后 Hibernate session 拋留 id=null 的脏实体，后续 JPQL 查询触发 auto-flush 时抛 `AssertionFailure: Entry for instance has a null identifier`。`entityManager.clear()` 清除脏实体后，`getActiveReportSummary` 的查询才能正常执行。

### 防刷机制

频率限制（`MAX_REPORTS_PER_HOUR = 5`）：滑动窗口（now - 1h），统计用户上报的不同场所数，超过阈值抛 1006。**仅对新上报生效**——已有活跃报告的续期更新不触发频率检查（否则"续期"会被误判为重复上报而拦截）。

### 审核安全

- `note` 字段存储于 DB 但**不公开返回**（`ActiveReportSummary` 仅含 `activeCount` + `latestReportTime`），仅供管理端查看（`StatusReportResponse` 含 note，后续管理接口使用）
- `ReportReason` 枚举命名避免敏感词：`CHECK`（门店检查）、`UNKNOWN`（情况不明）、`CLEARED`（清场）——不出现"警察/扫黄"等微信审核敏感词

### 缓存失效（跨 Bean @CacheEvict）

`submitReport` 和 `cancelReport` 标注 `@CacheEvict(value = CACHE_VENUE_HEAT, key = "#venueId")`，写入后立即失效热度缓存。`CACHE_VENUE_HEAT` 的属主是 `VenueHeatService`，跨 Bean 失效是 Spring 缓存的标准能力（缓存名全局唯一），与 `TagInteractionService` 失效 `CACHE_TAG_STATS`（属主 `TagAggregateStatsService`）同模式。

`getActiveReportSummary()` 本身不缓存（无 `@Cacheable`），由 `VenueHeatService.getHeat()` 的 `@Cacheable` 包裹，60s TTL 内的重复请求命中缓存。

---

## 标签交互（taginteraction 模块）

### 设计定位

标签交互分为两种独立信号：

- **点赞（endorsement）**：二值信号，"我认同这个标签描述"。适用于管理员添加的描述性标签（Venue.tags）。
- **评分（rating）**：1-10 量化评价，"这个维度体验如何"。适用于系统定义的标准评分维度（`RatingDimensions.ALL`），与管理员标签相互独立。

### 数据模型

`qwt_tag_interactions` 表：一个用户对一个舞厅的一个标签至多一行（`UNIQUE(userId, venueId, tag)`）。`liked`（boolean）与 `score`（Integer 1-10）为两个独立列——可只点赞、只打分、或两者兼有。`tag` 字段存储标签文本（与 Venue.tags JSON 数组中的字符串一致）。

索引：`(venueId, tag)` 覆盖聚合查询，`(venueId, tag, updatedAt)` 覆盖时间窗口查询。

### 评分维度（RatingDimensions）

系统统一定义的标准维度列表，所有舞厅共享，不依赖管理员是否添加了对应标签。新增维度只需修改 `RatingDimensions.ALL` 常量，前端通过 `tag-stats` 接口的 `dimensions` 字段自动同步。

维度分两类：

| 类别 | 维度 | 锚定文案 | 说明 |
|------|------|----------|------|
| 体验评估 | 服务、环境、音响效果、性价比 | 1 最差 / 10 最好 | 主观质量打分，全量均分有参考价值 |
| 现场状况 | 舞伴氛围、客流热度、舞伴年龄层 | 各异（很少/很多、冷清/爆满、偏成熟/偏年轻） | 众包实时体感上报，时效性强，近 7d/30d 窗口均分更有参考价值 |

"现场状况"维度的设计动机：舞伴和客流是舞厅决策的核心变量（舞伴找客人多的店，客人找舞伴多的店），但无法由管理员维护（实时变化），只能众包。复用评分基础设施（1-10 量表 + 时间窗口 + 防刷冷却）是最低成本方案。

审核安全：维度命名使用角色术语（"舞伴""客流"）和主观体感词（"氛围""热度""年龄层"），不出现"男/女""人数"等字样，避免被解读为按性别统计的社交/陪侍类应用。

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/venues/{venueId}/tags/stats` | 公开（软鉴权） | 标签点赞统计 + 维度评分（含时间窗口）+ 当前用户状态 |
| POST | `/venues/{venueId}/tags/like` | 需登录 | body: `{tag}`，toggle 语义（首次=赞，再次=取消） |
| POST | `/venues/{venueId}/tags/score` | 需登录 | body: `{tag, score}`，upsert 语义（首次=打分，再次=修改覆盖） |

### 防刷机制

评分接口设 60 秒冷却期（`SCORE_COOLDOWN_SECONDS`）：同一用户同一维度在冷却期内重复提交返回 1006 错误。基于 `updatedAt` 判定（改分后自动刷新时间戳）。点赞无冷却（toggle 幂等，唯一约束兜底）。

### 时效性

评分统计支持三个时间窗口：全部 / 近 30 天 / 近 7 天。基于 `updatedAt`（用户最近一次修改评分的时间）过滤，改分后新分数归入近期窗口。聚合实时计算（当前数据规模无需物化视图）。

### 约束

- 点赞仅允许对场所当前 `tags` 列表中存在的标签操作（管理员删除标签后不可再赞，历史数据保留但不展示；重新添加后历史恢复）
- 评分仅允许 `RatingDimensions.ALL` 中的维度
- 评分只保留最新分（覆盖式），不保留历史版本
- 热度公式已纳入标签交互数据：`ratingCount30d × 8 + likeCount30d × 3 + satisfactionScore × 20`（见"场所热度"章节）

### 列表页标签热度（VenueResponse.tagLikeCounts）

场所列表（`GET /venues`）与收藏列表（`GET /user/favorites` 等复用 `VenueResponseMapper` 的接口）在 `VenueResponse.tags` 之外新增 `tagLikeCounts: Map<String, Long>`（tag → 点赞数），用于卡片上展示标签的认同热度。

- **不含 `likedByMe`**：列表层是识别信息，不需要"我是否已赞"这一评估层个人状态（详情页 `/tags/stats` 才携带），避免为一整页场所都计算当前用户的个人交互状态
- **批量查询而非逐条查询**：`TagInteractionService.batchGetTagLikeCounts(List<Long> venueIds)` 用一条 `venueId IN (...)` 分组查询覆盖整页场所，`VenueService.listVenues` / `FavoriteService.getFavoriteVenues` 在拿到 `Page<Venue>`/`List<Venue>` 后统一批量查询一次，禁止在 map 循环里逐个场所查询（N+1）
- 该批量查询**不缓存**：列表页请求的场所集合每次不同（翻页、筛选变化），复用 `TagAggregateStatsService` 的单场所缓存收益低，直接实时查询
- `VenueResponseMapper.toResponse(Venue)` 单参重载默认传空 Map（创建/编辑表单回显场景不需要标签热度），`toResponse(Venue, Map<String, Long>)` 重载供需要展示热度的场景显式传入

---

## 文件存储（storage 模块）

### 架构：前端直传 Supabase Storage

后端**不接收文件流**，仅签发上传凭证。前端凭凭证直传 Supabase Storage REST API，上传成功后将公开 URL 写入业务字段随表单提交。

```
前端 wx.chooseMedia 选图
  → GET /storage/upload-token（后端校验登录态 + 文件类型/大小 → 生成唯一路径 → 返回凭证）
  → wx.uploadFile 直传 Supabase Storage（Authorization: Bearer anonKey）
  → 上传成功 → publicUrl 写入业务字段（imageUrl / photos / wechatQr）
```

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/storage/upload-token` | 需登录 | 参数：category, fileName, fileSize → 返回 UploadTokenResponse |

### 文件分类（FileCategory）

| 枚举值 | 路径前缀 | 用途 |
|--------|----------|------|
| VENUE_COVER | `venue-covers/` | 场所封面图 |
| VENUE_PHOTO | `venue-photos/` | 场所相册 |
| VENUE_QR | `venue-qr/` | 微信二维码 |

上传路径格式：`{prefix}/{userId}/{uuid}.{ext}`（按用户隔离，UUID 保证唯一）。

### 配置

```yaml
supabase:
  storage:
    project-url: ${SUPABASE_PROJECT_URL:}   # 如 https://xxxx.supabase.co
    anon-key: ${SUPABASE_ANON_KEY:}         # 公开密钥，RLS 策略控制访问
    bucket: ${SUPABASE_STORAGE_BUCKET:qwt-public}  # 公开读 bucket
    max-file-size: 5242880                  # 5MB
    allowed-extensions: .jpg,.jpeg,.png,.webp
```

### 安全模型

- `anonKey` 是 Supabase 的公开密钥，安全性由 Storage RLS 策略保障（bucket 设为 public read，upload 需有效 JWT）
- `serviceRoleKey` 绝不下发前端（本模块不使用）
- 后端在签发凭证前已完成文件类型/大小校验，前端直传时 Supabase RLS 为第二道防线

### 约束

- 文件上传仅服务于**场所图片**（封面、相册、二维码），不涉及用户头像等社交属性
- 新增文件分类只需扩展 `FileCategory` 枚举 + 前端 `FileCategory` 类型
- 禁止后端接收 MultipartFile 中转上传（前端直传，后端零文件流）
- 禁止在凭证响应中暴露 serviceRoleKey

---

## 列表排序与城市统计

### 复合评分排序

`GET /venues` 支持可选 `latitude` / `longitude`（用户定位，gcj02），列表按服务端复合评分排序（分页正确性要求排序必须在库内完成）：

```
score = sortWeight（运营权重）
      + 收藏数 × 20 + 动态数 × 10（热度，与 /heat 占位公式同向）
      + 100 / (1 + 距离km)（Haversine 邻近加成，无坐标时为 0）
```

距离项使本地场所在全国列表中自然置顶，跨城市时衰减至可忽略（100km 外加成 ≈ 1），由热度与运营权重决定顺序。产品意图：默认展示全国列表但"本地化感知"，不自动按城市过滤（早期数据稀疏，自动过滤到无数据城市 = 首屏空白）。

### 双查询拆分（Postgres 平台坑位，重要）

排序拆为 `searchRanked`（带坐标）与 `searchRankedNoLocation`（无距离项）两个 JPQL 查询，Service 按坐标有无显式分流，**不要合并为"坐标可空的单查询"**：

Postgres 对无类型的 null 绑定参数推断为 `bytea`，JPQL 中 `radians(:latitude)` 在坐标为 null 时报 `function radians(bytea) does not exist`；SQL 层 `cast(? as float8)` 也救不了（`cannot cast type bytea to double precision`）。唯一干净的解法是让数学函数参数永远非 null——拆查询、Service 分流、`searchRanked` 用原生 `double` 形参（编译期排除 null）。两查询的筛选条件由 `VenueRepository.LIST_FILTERS` 编译期常量共享，避免重复。

### 城市词表与筛选

城市 / 区县按标准行政区划名（前端 `picker mode="region"` 产出，如"绍兴市"）**精确匹配**，写入与查询共用同一词表。禁止模糊匹配兜底——会掩盖写入端数据质量问题。存量脏数据走一次性清洗 SQL，不改查询逻辑。

### 城市统计接口

`GET /venues/cities` → `List<CityStatsResponse(city, venueCount)>`，按场所数倒序，供前端"热门城市"数据驱动展示。注意路由：字面量 `/venues/cities` 与路径变量 `/venues/{id}` 共存时 Spring 优先匹配字面量，无需特殊处理。

### 热门场所标记（VenueResponse.isHot）

`VenueResponse` 新增 `isHot` 字段（boolean），标记该场所在同城市中属于热门场所。

- **城市内相对排名**：按复合评分（见「复合评分排序」）在同城市场所中取 top 20%，最少 1 个。即"热门"是相对同城市其他场所而言，不是绝对分数阈值
- **查询实现**：`VenueRepository.findHotVenueIds()` 使用 PostgreSQL 窗口函数（`ROW_NUMBER() OVER (PARTITION BY city ORDER BY score DESC)`）在库内完成城市内排名，避免在 Java 侧逐城市遍历。排序口径为 `sortWeight + 收藏数×20 + 动态数×10`（与列表查询的无坐标变体一致，不含距离项——距离是用户维度，场所热度排名不应因请求者位置变化）。Service 层在 `listVenues` 中无条件调用此方法，获取热门 ID 集合后转为 `Set<Long>` 供 `result.map()` 内 `contains` 检查
- **VenueResponseMapper 三参重载**：`toResponse(Venue, Map<String, Long> tagLikeCounts, boolean isHot)` ——在已有双参重载基础上追加 `isHot` 参数。Service 层先调用 `venueRepository.findHotVenueIds()` 获取热门 ID 集合并转为 `Set<Long>`，再在 `result.map()` 中传入 `hotVenueIds.contains(v.getId())`。双参重载默认 `isHot=false`，单参重载亦默认 `isHot=false`（创建/编辑回显场景无需热门标记）

---

## 开发测试数据

`src/main/resources/db/seed-dev.sql` 提供开发环境种子数据（5 个场所、3 个用户、4 条动态、3 条收藏），覆盖已认领 / 未认领、各场所状态、商家 / 平台动态等场景。使用方式：应用以 dev profile 启动一次（自动建表）后，在 Supabase SQL Editor 或 psql 中手动执行。脚本末尾通过 `setval` 重置 IDENTITY 序列，避免后续自增 ID 冲突。

---

## HTTP API 规范

**只允许 GET 和 POST，禁止使用 PUT、PATCH、DELETE。**

| 场景 | 方法 | 示例 |
|------|------|------|
| 列表查询 / 单条查询 | GET | `GET /venues?city=绍兴市&latitude=30.0&longitude=120.5&page=0&size=20` |
| 关键字 / 复杂条件搜索 | GET | `GET /venues/search?q=爵士` |
| 带复杂过滤的分页（参数过多） | POST | `POST /venues/search` |
| 创建资源 | POST | `POST /venues` |
| 逻辑更新（状态变更） | POST | `POST /venues/{id}/status` |
| 逻辑删除 | POST | `POST /venues/{id}/disable` |
| 信息纠错反馈 | POST | `POST /venues/{venueId}/feedbacks`（需登录） |
| 场所状态上报 | POST | `POST /venues/{venueId}/status-reports`（需登录，body 可空=快速上报） |
| 撤销状态上报 | POST | `POST /venues/{venueId}/status-reports/cancel`（需登录） |

URL 使用复数名词，全小写，单词间用 `-` 连接：`/job-posts`、`/venue-tags`。

---

## 统一响应格式

所有接口统一返回 `ApiResponse<T>`：

```java
// dto/response/ApiResponse.java
public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

- 成功：`code = 0`
- 业务错误：`code` 使用自定义错误码（`1xxx` 客户端错误，`5xxx` 服务端错误）
- HTTP 状态码：始终返回 `200`，错误信息通过 `code` 字段区分

### 错误码登记表（新增错误码必须避开已占用值）

| code | 含义 |
|------|------|
| 1001 | 参数校验失败 / 资源不存在 |
| 1002 | 未登录（token 缺失 / 无效 / 过期），HTTP 401 |
| 1003 | 权限不足（非管理员）/ 微信接口业务错误 |
| 1004 | 用户不存在 |
| 1005 | 文件校验失败（类型 / 大小超限） |
| 1006 | 操作过于频繁（评分防刷冷却期内 / 状态上报频率超限） |
| 1007 | 无效的标签或评分维度 |
| 5000 | 未知服务器错误（兜底） |
| 5001 | 微信接口响应异常（无响应 / 解析失败） |
| 5002 | 文件保存失败（IO 异常） |

---

## JPA 实体规范

```java
// ✅ 正确：Entity 只用 @Getter/@Setter，不用 @Data（避免 equals/hashCode 问题）
@Entity
@Table(name = "venue")
@Getter
@Setter
public class Venue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    private VenueStatus status;   // 枚举用 STRING，禁止用 ORDINAL

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- 主键统一用 `Long id`，策略 `IDENTITY`
- 时间戳字段用 `LocalDateTime`，配合 `@CreationTimestamp` / `@UpdateTimestamp`
- 枚举映射用 `@Enumerated(EnumType.STRING)`，禁止 `ORDINAL`
- 逻辑删除字段命名 `deleted`（`boolean`），不物理删除数据
- 禁止在 Entity 中写业务方法

---

## 多值字段（JSON 数组列）

标签（`tags`）、相册（`photos`）等"一对多、无独立元数据、仅整体读写"的字段，以 **JSON 数组字符串**存储于单个 `varchar` 列（如 `["爵士","商务"]`），不建关联表：

- Entity 中为 `String` 类型，`@Column(length = ...)` 按上限估算（如 photos 9 张 × 500 URL → 5000）
- 序列化/反序列化统一收敛在 `mapper/` 组件与 Service 的私有工具方法中（注入 `ObjectMapper`），新增同类字段时复用 `serializeStringList` / `serializeList` / `deserializeList`，不另起炉灶
- Response DTO 中为 `List<String>`，**空数据返回空列表而非 null**（前端无需判空两套逻辑）
- Request DTO 中为 `List<String>`，用 `@Size(max = N)` 限制数量、`List<@Size(max = 500) String>` 限制单元素长度，与列长度约束呼应
- 若字段未来需要独立查询、排序、元数据（如图片描述、上传者），再升级为关联表——届时迁移脚本单独约定

**结构化对象列表**同理：门票（`tickets`）、舞伴费用（`partnerFees`）以 JSON 对象数组存储，DTO 定义为 `venue/dto/` 下的共享 record（`TicketEntry` / `PartnerFeeEntry`，请求与响应复用），Request 中用 `List<@Valid TicketEntry>` 触发嵌套校验，跨字段约束在 Service 层校验。

- `TicketEntry`：`{"label":"晚场","type":"FIXED","price":30}`——label 为条件自由文本，type 为枚举（FIXED/FREE）
- `PartnerFeeEntry`：`{"label":"5点前","unit":"MINUTE","minutes":5,"price":20}`——label 为条件自由文本（可空），unit 为计量单位枚举（`PartnerFeeUnit`：MINUTE 按分钟 / SONG 按曲数），minutes 为计量数量（unit=SONG 时语义为曲数，字段名保留以兼容存量数据）。请求中 unit 可省略（Service 层 `normalizePartnerFees` 默认 MINUTE），存储时始终写入显式 unit 值。设计动机：江浙沪按时长阶梯、西安等地按连曲计费，同一店内可有时段差异——与 TicketEntry 的"label + 类型"模式对齐，新增计费形态只需扩展 unit 枚举。

---

## Repository 规范

```java
// ✅ 继承 JpaRepository，复杂查询用 @Query JPQL
public interface VenueRepository extends JpaRepository<Venue, Long> {

    // 简单条件：方法命名推导
    Page<Venue> findByStatusAndCityOrderByCreatedAtDesc(
        VenueStatus status, String city, Pageable pageable);

    // 复杂条件：显式 JPQL（禁止 native SQL，除非无法实现）
    @Query("SELECT v FROM Venue v WHERE v.status = :status AND " +
           "(:keyword IS NULL OR v.name LIKE %:keyword%)")
    Page<Venue> search(@Param("status") VenueStatus status,
                       @Param("keyword") String keyword,
                       Pageable pageable);
}
```

- 优先方法命名推导，方法名超过 4 个条件时改用 `@Query`
- 禁止 `@Query(nativeQuery = true)`，除非 JPQL 无法实现且已注释原因
- 分页统一使用 `Pageable` 参数，返回 `Page<T>`
- **单行多列聚合查询禁止以 `Object[]` 为返回类型，必须使用接口投影（Interface-based Closed Projection）**。Spring Data JPA 4.x 将 `Object[]` 解释为"行数组"而非"列值数组"，导致 `ClassCastException`。标准做法：在 Repository 内定义嵌套投影接口（getter 名与 SELECT alias 对应），查询方法返回该接口类型。多行查询仍用 `List<Object[]>`（不受影响）。

```java
// ✅ 正确：接口投影（类型安全，跨版本稳定）
public interface VenueViewRepository extends JpaRepository<VenueView, Long> {
    interface PvUvStats {
        Long getPv();
        Long getUv();
    }

    @Query("SELECT COUNT(v) as pv, COUNT(DISTINCT v.userId) as uv FROM VenueView v " +
           "WHERE v.venueId = :venueId AND v.viewDate >= :since")
    PvUvStats countPvAndUvByVenueIdSince(@Param("venueId") Long venueId, @Param("since") LocalDate since);
}

// ❌ 禁止：Object[] 接收单行多列（Spring Data JPA 4.x 语义变更致 ClassCastException）
@Query("SELECT COUNT(v), COUNT(DISTINCT v.userId) FROM VenueView v ...")
Object[] countPvAndUv(...);
```

投影接口命名约定：嵌套于 Repository 接口内部（co-located，不新增文件）；JPQL 用 `as alias` 与 getter 名对应；native query 的 alias 使用全小写（PostgreSQL 对未加引号标识符做小写折叠），getter 名同步全小写（如 `getRatingcount()`）。

### 投影接口 getter 类型必须匹配 Hibernate 的实际映射类型（2026-07-31 热度接口 500 事故根因）

投影接口的 getter 返回类型必须与 Hibernate 对该 SQL 列类型的**实际映射类型**一致，而非直觉上"看起来对应"的 JDBC 遗留类型。Hibernate 6+（Spring Boot 4.x 默认版本）对原生查询结果的默认类型映射已改为优先 `java.time.*`：

| SQL 类型 | Hibernate 6+ 默认映射 | 禁止使用的历史遗留类型 |
|----------|----------------------|----------------------|
| `DATE` | `java.time.LocalDate` | `java.sql.Date` |
| `TIMESTAMP` | `java.time.LocalDateTime` | `java.sql.Timestamp` |

若投影接口的 getter 声明为遗留类型（如 `java.sql.Date getDay()`），`ProjectingMethodInterceptor` 在把 Hibernate 实际返回的 `LocalDate` 转换为声明类型时找不到匹配的 `Converter`，直接抛 `UnsupportedOperationException: Cannot project java.time.LocalDate to java.sql.Date`——**编译期不报错，只在运行时首次命中该查询才炸**，且异常堆栈指向调用方（`VenueHeatService`）而非真正的根因（Repository 投影接口）。

**规则**：新增/修改任何原生查询（`nativeQuery = true`）的投影接口时，DATE/TIMESTAMP 列一律用 `java.time.LocalDate`/`java.time.LocalDateTime` 声明 getter，禁止使用 `java.sql.*` 包下的类型。JPQL 查询同理（Hibernate 对 JPQL 结果的映射规则一致）。


### 分页参数安全（强制）

Controller 接收的 `page` / `size` 参数必须在 **Service 层**钳制后再构造 `PageRequest`，防止客户端传入极端值导致 OOM 或异常：

```java
private static final int MAX_PAGE_SIZE = 50;

page = Math.max(0, page);
size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
PageRequest pageable = PageRequest.of(page, size);
```

新增任何分页接口时必须遵循此模式。

---

## DTO 规范

```java
// 请求体：Java record（Spring 6+ 支持 record 绑定）
public record VenueSearchRequest(
    String keyword,
    String city,
    String district,
    @Min(0) int page,
    @Max(50) @Min(1) int size
) {}

// 响应体：Java record，不暴露内部 id 以外的敏感字段
public record VenueResponse(
    Long id,
    String name,
    String address,
    String phone,
    String businessHours,
    List<String> tags
) {}
```

- 请求/响应 DTO 均用 Java **record**，禁止用 Lombok `@Data`
- Service 层负责 Entity → DTO 转换，禁止在 Controller 层转换
- 响应 DTO 字段分类原则：
  - **系统内部字段**（`deleted`、`createdAt`）：禁止暴露，仅用于审计/运维
  - **用户决策时效字段**（`updatedAt`）：必须暴露——黄页产品中场所开关状态不稳定，用户需要"数据最后更新时间"判断信息可靠度，这是信任信号而非内部审计字段

---

## 异常处理

```java
// 自定义业务异常
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}

// 全局处理器
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handle(BusinessException ex) {
        return ApiResponse.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handle(ConstraintViolationException ex) {
        return ApiResponse.fail(1001, "参数校验失败: " + ex.getMessage());
    }
}
```

- 禁止在 Service 层 catch 后吞掉异常
- Controller 层禁止 try-catch，统一由 `GlobalExceptionHandler` 处理
- 日志用 `@Slf4j`，错误级别：业务异常用 `warn`，系统异常用 `error`

### 并发写入竞态处理（唯一约束 + catch 模式）

对有唯一约束的 INSERT 操作（收藏、标签交互、浏览记录等），check-then-act 存在并发窗口：两个请求同时通过"不存在"检查后都尝试 INSERT，第二个触发唯一约束异常。标准处理模式：

```java
try {
    repository.save(entity);
} catch (DataIntegrityViolationException e) {
    // 并发竞态：另一请求已插入，幂等忽略
    log.debug("并发冲突，幂等忽略: ...");
}
```

适用条件：INSERT 是幂等的（重复插入不影响业务语义）。若 INSERT 后还需后续操作（如 toggle），冲突时应重新查询已有记录再执行后续逻辑。

已有实例：`VenueViewService.recordView`、`FavoriteService.addFavorite`、`TagInteractionService.toggleLike/score`。

---

## JSON 序列化（Jackson 3.x）

Spring Boot 4.x 自动配置的是 **Jackson 3.x**（`tools.jackson`），不再是 Jackson 2.x。

```java
// ✅ 正确：使用 Jackson 3.x 包名
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

// ✅ 注解仍在旧包下（jackson-annotations 未迁移）
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
```

- `ObjectMapper`、`TypeReference`、`JsonProcessingException` 等核心类在 `tools.jackson.*` 包下
- `@JsonFormat`、`@JsonProperty`、`@JsonIgnore` 等注解仍在 `com.fasterxml.jackson.annotation` 包下
- 禁止在 pom.xml 中显式引入 `com.fasterxml.jackson.core:jackson-databind`（2.x），会覆盖 Spring Boot 管理的 3.x 版本
- 禁止 import `com.fasterxml.jackson.databind.*` 或 `com.fasterxml.jackson.core.*`（2.x 包名）
- 需要 `ObjectMapper` 时直接注入 Spring 容器中的 Bean，禁止手动 `new ObjectMapper()`

---

## 配置管理

```
src/main/resources/
  application.yaml          ← 公共配置（不含敏感信息，所有 profile 共享）
  application-dev.yaml      ← 本地开发（不提交 git，已加入 .gitignore）
  application-prod.yaml     ← 生产（已提交，敏感值全部通过环境变量注入）
```

- 基础 `application.yaml` 的 `ddl-auto` 为 `validate`（安全默认），仅 `application-dev.yaml` 覆盖为 `update`
- 禁止在任何 yaml 文件中硬编码数据库密码、密钥等敏感信息
- 本地开发使用 `application-dev.yaml`，该文件已列入 `.gitignore`
- 环境变量占位符禁止带空默认值（`${SECRET}` 而非 `${SECRET:}`），确保遗漏配置时启动即失败

### 生产部署

基础设施：阿里云 ECS（内嵌 Tomcat，`java -jar` 方式运行）+ Cloudflare Tunnel（HTTPS 终止，转发至 `localhost:8080`）。

**强制约束（2026-07-25 OOM 事故后确立）**：

- **禁止在生产环境使用 `mvn spring-boot:run`**。该命令会同时运行 Maven JVM + fork 应用 JVM，双倍内存开销且无进程守护。生产唯一启动方式为 systemd 管理的 `java -jar`。
- **JVM 必须配置内存上限**。由 `deploy/deploy.sh` 在 ExecStart 中硬编码 `-Xmx` / `-XX:MaxMetaspaceSize` / `-XX:+ExitOnOutOfMemoryError`，禁止使用无约束的默认值。
- **必须通过 systemd 管理进程**（自动重启、资源硬限制、日志归集）。systemd unit 由 `deploy/deploy.sh` 自动探测 JAVA_HOME 后内联生成（避免硬编码 `/usr/bin/java` 导致 status=203/EXEC）。
- **业务配置直接写在服务器上的 `application-dev.yaml`**，不依赖外部环境变量文件（简化部署流程，该文件不进 git）。

**部署流程（一键脚本）**：

```bash
# 首次部署（服务器上执行）
# 1. 确保仓库已 clone 到 /root/quwuting-service
git clone https://github.com/JamesSmith888/quwuting-service.git /root/quwuting-service

# 2. 确保 src/main/resources/application-dev.yaml 中有真实的业务配置（DB/JWT/Supabase）
#    该文件已在服务器上，不进 git

# 3. 一键部署（打包 + 创建用户 + 写 systemd unit + 启动）
sudo bash deploy/deploy.sh

# 后续升级（服务器上执行）
git pull
sudo bash deploy/deploy.sh --no-user --no-unit   # 仅重新打包+重启
```

脚本支持 `--no-user`（跳过用户创建）、`--no-unit`（跳过 unit 重写）、`--no-package`（跳过 Maven 打包）。默认使用 `dev` profile（可通过 `SPRING_PROFILE` 环境变量覆盖）。

**deploy.sh 关键机制说明**：

| 机制 | 实现 | 作用 |
|------|------|------|
| JAVA_HOME 自动探测 | `readlink -f $(which java)` | 避免硬编码 `/usr/bin/java`（tarball JDK 无此路径，导致 status=203/EXEC） |
| 专用系统用户 | `appuser`（nologin shell） | 进程隔离，最小权限运行 |
| JVM 参数硬编码 | ExecStart 中 `-Xmx512m -Xms256m -XX:MaxMetaspaceSize=192m` | 限制堆大小，防止 OOM Kill |
| `-XX:+ExitOnOutOfMemoryError` | JVM flag | JVM 内 OOM 立刻退出，让 systemd 拉起而非僵死 |
| `-XX:+HeapDumpOnOutOfMemoryError` | JVM flag | 留 hprof 到 `/var/log/quwuting-service/`，事后分析 |
| `Restart=always` + `RestartSec=10s` | systemd 守护 | 异常退出后 10s 自愈 |
| `StartLimitBurst=5` / `StartLimitIntervalSec=120` | 2 分钟内最多重启 5 次 | 防止配置错误导致无限重启循环 |
| `MemoryMax=950M` / `MemoryHigh=800M` | cgroup 硬/软限制 | JVM 逃逸时兜底（仍 < 物理 2GB 一半），OS 不被拖死 |

**HikariCP 连接池**（`application-dev.yaml`）：maximumPoolSize=5, minimumIdle=2。低流量小程序 + 高延迟 DB（~150ms/往返）场景下 5 连接足够，减少内存占用。leak-detection-threshold=30s 用于排查连接泄漏。

Cloudflare Tunnel 的 `config.yml` 中 ingress 指向 `http://localhost:8080`。

### Supabase 连接池兼容性（强制）

Supabase 提供三类接入点，JDBC 配置必须与池化模式匹配，否则运行期报 `prepared statement "S_N" already exists`（SQLState 42P05）：

| 接入点 | 端口 | 模式 | JDBC 要求 |
|--------|------|------|-----------|
| `aws-1-<region>.pooler.supabase.com` | 6543 | 事务池化 | URL **必须**附加 `prepareThreshold=0` |
| `aws-1-<region>.pooler.supabase.com` | 5432 | 会话池化 | 无特殊要求 |
| `db.<project-ref>.supabase.co` | 5432 | 直连 | 无特殊要求，用户名用 `postgres` |

根因：PG JDBC 驱动默认启用服务端命名预编译语句（`prepareThreshold=5`，同一 SQL 执行 5 次后提升为命名语句 S_1/S_2…），而事务池化会在事务之间更换物理后端连接，命名语句的命名空间挂在物理后端上，多路复用必然冲突。`prepareThreshold=0` 禁用服务端命名预编译，是事务池化环境下的标准解法。

新增/修改数据源配置时（含生产 `${DB_URL}`），若 URL 指向 6543 端口，必须检查 `prepareThreshold=0` 是否存在。

---

## 命名规范

| 类别 | 规则 | 示例 |
|------|------|------|
| 类名 | PascalCase | `VenueController`, `VenueService` |
| 方法名 | camelCase，动词开头 | `findVenueById`, `searchVenues` |
| 变量名 | camelCase | `venueList`, `pageResult` |
| 常量 | SCREAMING_SNAKE_CASE | `MAX_PAGE_SIZE` |
| 数据库表名 | `qwt_` 前缀 + snake_case 复数 | `qwt_venues`, `qwt_job_posts` |
| 数据库索引名 | `qwt_idx_` 前缀 + snake_case | `qwt_idx_city`, `qwt_idx_status` |
| 数据库列名 | snake_case | `business_hours`, `created_at` |
| URL 路径 | 复数名词，kebab-case | `/venues`, `/job-posts` |

---

## 禁止操作

- 禁止使用 PUT、PATCH、DELETE HTTP 方法
- 禁止 Controller 直接调用 Repository
- 禁止 Entity 直接作为 API 响应体返回
- 禁止 `@Query(nativeQuery = true)` 无注释使用
- 禁止 `ddl-auto: create` 或 `update` 出现在生产配置
- 禁止在 yaml 文件中硬编码密码、Token 等敏感信息
- 禁止在 Entity 上使用 `@Data`（会破坏 JPA equals/hashCode 契约）
- 禁止枚举用 `@Enumerated(EnumType.ORDINAL)`（数据库值依赖顺序，易出错）
- 禁止 import `com.fasterxml.jackson.databind.*` 或 `com.fasterxml.jackson.core.*`（Spring Boot 4.x 使用 Jackson 3.x `tools.jackson.*`）
- 禁止在 pom.xml 显式引入 `com.fasterxml.jackson.core:jackson-databind`（由 Spring Boot BOM 管理）
- 禁止表名/索引名省略 `qwt_` 前缀（多项目共享同一 Supabase 数据库）
- 禁止在 `@Column` 中写 MySQL 特有 `columnDefinition`（如 `tinyint`），应省略让 Hibernate 按方言映射
- 禁止在用户 API 响应中引入头像等社交属性字段（产品为黄页工具，UserInfoResponse 仅含 id/openId/nickname/role）
- 禁止在 Venue 模型中使用"人均消费"（price）/"最低消费"（minConsumption）字段——舞厅领域无此概念，消费模型为 tickets（门票规则）+ partnerFees（舞伴费用多模式列表，unit 区分 MINUTE/SONG）
- 禁止添加 CORS 配置（`addCorsMappings`）——唯一客户端为微信小程序（`wx.request` 不受同源策略约束），CORS 仅降低防御深度
- 禁止在生产环境使用 `mvn spring-boot:run`（双 JVM 内存翻倍 + 无进程守护，2026-07-25 OOM 事故根因）
- 禁止生产 `java -jar` 启动时省略 JVM 内存参数（JVM flags 由 `deploy/deploy.sh` 硬编码在 ExecStart 中统一管理，至少含 `-Xmx`）
- 禁止单行多列聚合查询以 `Object[]` 为返回类型（Spring Data JPA 4.x 语义变更致 ClassCastException），必须使用接口投影
- 禁止把用户个人交互状态（"我是否已赞"、"我的评分"等）和场所级公共聚合数据放进同一个缓存 key——个人状态必须实时查询，缓存只允许存放与请求者身份无关的聚合结果
- 禁止在同一个 Service 类内部通过 `this` 调用被 `@Cacheable`/`@CacheEvict` 标注的方法——自调用会绕开 Spring AOP 代理，缓存注解静默失效；被缓存的方法必须拆到另一个 Bean 中
- 禁止列表页关联统计（如标签点赞数）按场所逐条查询——批量查询整页涉及的 ID（`IN (...)`），避免 N+1
- 禁止原生查询/JPQL 投影接口的 DATE/TIMESTAMP 列 getter 声明为 `java.sql.Date`/`java.sql.Timestamp`——Hibernate 6+ 默认映射为 `java.time.LocalDate`/`LocalDateTime`，类型不符会在运行时抛 `UnsupportedOperationException`（见「投影接口 getter 类型」章节）
- 禁止「近 N 天」滚动窗口统计只传 `since` 不传 `until` 上界——必须锚定「截至昨日」（`until = 今天 0 点`），不得让当天未走完的部分数据混入窗口聚合（见「统计口径：截至昨日」章节）
- 禁止用户状态上报修改 `Venue.status` 字段——上报是独立信号层，`Venue.status` 变更权属管理员/认领人（见「场所状态上报」章节）
- 禁止在 `ActiveReportSummary`（公开响应）中返回 `note` 字段——note 仅管理端可见，审核安全要求（见「场所状态上报 → 审核安全」）

---

## AI 代理常见错误

| 错误 | 正确做法 |
|------|----------|
| 用 PUT/DELETE 定义接口 | 一律改为 POST，路径加语义动词 `/disable` `/update` |
| Controller 直接 `return entity` | Service 转换为 DTO，Controller 包装 `ApiResponse.ok(dto)` |
| Repository 用 nativeQuery | 改写为 JPQL `@Query` |
| Entity 加 `@Data` | 改为 `@Getter` + `@Setter`，手写或不写 equals/hashCode |
| DTO 用 Lombok `@Data` | 改用 Java record |
| 密码写入 yaml | 改为 `${ENV_VAR}` 占位符 |
| `ddl-auto: update` 出现在 prod | 必须是 `validate` 或 `none` |
| Service 抛出 checked exception | 统一抛 `BusinessException`（RuntimeException 子类） |
| import `com.fasterxml.jackson.databind.*` | 改为 `tools.jackson.databind.*`（Jackson 3.x），注解除外 |
| 表名/索引名不加 `qwt_` 前缀 | 共享数据库必须加前缀：`qwt_venues`、`qwt_idx_city` |
| Entity `columnDefinition` 写 MySQL 语法 | 不写 `columnDefinition`，让 Hibernate 按方言自动映射 |
| 给用户 API 加头像 / 社交属性字段 | 产品为黄页工具非社交；UserInfoResponse 仅含 id/openId/nickname/role；storage 模块仅服务场所图片，不涉及用户头像 |
| Venue 消费字段用 `price`（人均）/ `minConsumption`（低消） | 舞厅领域无此概念；用 `tickets`（门票规则 JSON 列表）+ `partnerFees`（舞伴费用多模式 JSON 列表，unit 区分 MINUTE/SONG），共享 DTO record 在 `venue/dto/` |
| 在登录链路获取 / 要求前端上送昵称 | 微信 jscode2session 不返回资料；昵称经 `POST /user/profile` 由用户主动提交，角色等变更经 `GET /user/me` 静默同步 |
| 原生查询投影接口 DATE/TIMESTAMP 列声明 `java.sql.Date`/`java.sql.Timestamp` | 改用 `java.time.LocalDate`/`LocalDateTime`——Hibernate 6+ 默认映射为 java.time 类型，声明遗留类型会在首次命中该查询时运行时报错 |
| 「近 N 天」统计只传 `since` 让窗口自然到"现在" | 同时传 `since` + `until`（`until` 固定为今天 0 点），把当天不完整数据排除在窗口外，统一锚定「截至昨日」 |
| JPQL 数学函数传可空坐标参数（`radians(:latitude)` + null） | PG 将 null 参数推断为 bytea 直接报错；拆成带坐标 / 无坐标两个查询，Service 分流，坐标形参用原生 `double` |
| 城市筛选用 LIKE 模糊匹配"兼容"非标准名 | 精确匹配 + 写入端统一 region picker 标准名；脏数据走一次性清洗 SQL，不在查询端容错 |
| 单行多列聚合查询返回 `Object[]` 再下标强转 | 用 Repository 嵌套接口投影（getter 名 = SELECT alias），编译期类型安全，不受 Spring Data JPA 版本语义变更影响 |
| 把 likedByMe/myScore 等个人状态塞进以 `{venueId, userId}` 为 key 的聚合缓存 | 聚合数据（venueId 为 key）与个人状态（永远实时查询）彻底分离，写操作对聚合缓存做 `@CacheEvict`，不要只依赖 TTL |
| 在同一 Service 类内 `this.xxx()` 调用本类的 `@Cacheable` 方法 | 绕开 AOP 代理导致缓存静默失效；把被缓存的方法拆到独立 Bean（如 `TagAggregateStatsService`），从外部注入调用 |
| 列表页展示的关联统计按 venueId 循环单独查询 | 收集整页 venueId 后一次 `IN (...)` 批量查询（如 `TagInteractionService.batchGetTagLikeCounts`） |
| 用 venuefeedback 做实时状态上报（误用异步审核做实时信号） | venuefeedback = 异步管理员审核流程；venuestatusreport = 实时 4h TTL 众包信号。两者共存，不可混用（见「场所状态上报」章节） |
| 用户上报后修改 Venue.status | 用户上报是独立信号层，不改 Venue.status；管理员后续可决定是否据此手动更新（见「独立信号层」章节） |

---

## 验证清单（每次修改后检查）

- [ ] 无 PUT、PATCH、DELETE 方法注解
- [ ] Controller 只做参数绑定，业务逻辑在 Service
- [ ] 所有接口返回 `ApiResponse<T>`
- [ ] 新增 Entity 没有使用 `@Data`
- [ ] 枚举字段使用 `EnumType.STRING`
- [ ] 无敏感信息硬编码在 yaml
- [ ] Jackson 相关 import 使用 `tools.jackson.*`（注解 `com.fasterxml.jackson.annotation` 除外）
- [ ] 新增表名/索引名带 `qwt_` 前缀
- [ ] Entity 无 MySQL 特有 `columnDefinition`
- [ ] 新增 Repository 方法有对应 Service 单元测试
- [ ] `./mvnw test` 通过
