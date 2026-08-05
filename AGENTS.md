# AGENTS.md — quwuting-service

Spring Boot 4.1 + Java 25 + Spring Data JPA 后端服务。  
为趣舞厅微信小程序提供舞厅信息查询 REST API，当前阶段仅涉及数据展示，无交易/支付逻辑。

---

## 构建与运行

```bash
# 编译
./mvnw clean compile

# 运行测试（contextLoads 需连库，约定带 dev profile 执行，
# 同时验证 SchemaIntegrityChecker 对真实库的完整性检查）
./mvnw test -Dspring.profiles.active=dev

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
config/         ← Spring @Configuration 类（WebMvc、拦截器注册）+ RequestTimingFilter（请求耗时过滤器）+ SchemaIntegrityChecker（启动时 Schema 完整性检查，fail-fast）
security/       ← JWT 工具、AuthInterceptor、UserContext（ThreadLocal 用户上下文）

venue/          ← 场所模块（按功能分包）
  controller/   ← REST 控制器，只定义路由和参数绑定，不含业务逻辑
  service/      ← 业务逻辑：VenueService, VenueHeatService（热度计算 + 内嵌 refresh-ahead 缓存）, VenueViewService（浏览 upsert）, VenueLookupService（@Cacheable(sync) 场所查找缓存层）
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

venuefeedback/  ← 统一用户上报模块（原"场所信息纠错反馈"，2026-08-05 泛化；详见「统一用户上报」章节）
  controller/   ← VenueFeedbackController（POST /venues/{venueId}/feedbacks 提交通道）
                  ReportAdminController（GET /admin/reports, POST /admin/reports/{id}/resolve|dismiss 管理端）
  service/      ← VenueFeedbackService（提交、管理端列表/处理/忽略、维护承诺文案组装）
  entity/       ← VenueFeedback 实体（qwt_venue_feedbacks）
  repository/   ← VenueFeedbackRepository（JpaSpecificationExecutor 组合筛选分页）
  dto/
    request/    ← CreateFeedbackRequest（type + note）
    response/   ← VenueFeedbackResponse（提交响应，含 maintenanceHint）/ AdminReportResponse（管理端列表项）
  enums/        ← FeedbackType（CLOSED_DOWN / SUSPENDED / INACCURATE / PRICE / OTHER）
                  ReportStatus（PENDING / RESOLVED / DISMISSED 状态机）

venuestatusreport/  ← 场所状态众包上报模块（实时暂停信号，4h TTL）
  controller/   ← StatusReportController（POST /venues/{venueId}/status-reports, POST .../cancel）
  service/      ← StatusReportService（upsert 上报、撤销、活跃统计、频率限制、@CacheEvict 热度缓存）
  entity/       ← VenueStatusReport 实体（qwt_venue_status_reports）
  repository/   ← StatusReportRepository（含活跃计数+最新时间合并投影查询）
  dto/
    request/    ← SubmitReportRequest（reason + occurredAt + note，全可选）
    response/   ← ActiveReportSummary（公开）/ StatusReportResponse（管理端，含 note）
  enums/        ← ReportReason（CHECK 门店检查 / UNKNOWN 情况不明 / CLEARED 清场）

taginteraction/ ← 评分交互模块（维度评分；原"标签点赞"已被 venuereaction/ 替代，见「Reaction 快速反馈系统」章节）
  controller/   ← TagInteractionController（GET /venues/{id}/tags/stats, POST .../score）
  service/      ← TagInteractionService（upsert 评分、个人评分状态实时查询）
                  TagAggregateStatsService（场所级评分聚合 + 内嵌 refresh-ahead LoadingCache，见「查询性能优化」章节）
  entity/       ← TagInteraction 实体（qwt_tag_interactions，liked 列为历史遗留字段，不再读写）
  repository/   ← TagInteractionRepository（含 GROUP BY 聚合查询、三窗口评分聚合查询）
  dto/
    request/    ← ScoreTagRequest
    response/   ← TagStatsResponse / DimensionScoreStats / WindowScore
  RatingDimensions ← 系统评分维度常量（服务、环境、音响效果、性价比——原"现场状况"三维度已被 Reaction 替代）

venuereaction/  ← Reaction 快速反馈模块（Telegram Reaction 式表情反馈，替代原"标签点赞"，详见「Reaction 快速反馈系统」章节）
  controller/   ← VenueReactionController（GET /venues/{id}/reactions/stats, POST /venues/{id}/reactions/{code}）
  service/      ← VenueReactionService（toggle 参与、个人状态实时查询、列表页徽标编排）
                  VenueReactionAggregateService（场所级四窗口聚合 + 内嵌 refresh-ahead LoadingCache）
  entity/       ← VenueReaction 实体（qwt_venue_reactions）
  repository/   ← VenueReactionRepository（四窗口条件聚合查询、批量多场所 Top Reaction 查询）
  dto/
    response/   ← ReactionBadge（列表徽标）/ ReactionStat（详情完整统计）/ ReactionStatsResponse
  ReactionCode  ← Reaction 字典枚举（emoji + label，后台维护，不允许用户自由创建）

venueshare/    ← 场所分享事件模块（分享追踪 P2 数据通道，2026-08-05 新增，详见「分享追踪」章节）
  controller/   ← VenueShareController（POST /venues/{id}/shares, POST /venues/{id}/share-opens）
  service/      ← VenueShareService（SHARE/OPEN 事件写入 + 60s 频控，fire-and-forget 语义）
  entity/       ← VenueShare 实体（qwt_venue_shares，事件日志，只追加）
  repository/   ← VenueShareRepository（纯 append，无查询需求）
  dto/
    request/    ← RecordShareRequest（channel，@Pattern 校验）/ RecordShareOpenRequest（shareFrom）
  enums/        ← ShareEventType（SHARE / OPEN）

venue/config/   ← 门店默认标签配置
  VenueDefaultsConfig ← @ConfigurationProperties(prefix = "venue.default")，标签合并/过滤工具方法

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
          + positiveReactionCount30d × 3（仅 Polarity.POSITIVE 的 code，见「Reaction 快速反馈系统」章节）
          + (satisfactionScore − 6) × 20（无评分时为 0，6 分为中性基准）
```

权重常量收敛在 `VenueHeatService` 内部，后续基于真实数据分布调优，接口路径与 `heatScore` 语义不变。

**2026-08 缺陷修复确立的三条语义**（详情页热度专项）：
1. **Reaction 分极性**：仅正向 Reaction（人气旺/氛围好/音乐棒等 9 项）计入热度；负向（服务问题/排队太久/人气冷清/消费较高/环境一般/人多拥挤 6 项）**不计入公式**，以 `negativeReactionCount30d` 单独下发，前端展示"负面反馈 N 条·不计入热度指数"——修复"被吐槽的店热度反而更高"的语义硬伤。中性（普通）也不计入。极性定义在 `ReactionCode.Polarity`（唯一事实源），mega-query 通过 `:positiveCodes`/`:negativeCodes` 参数过滤。
2. **满意度中性偏移**：满意度贡献 = `(score − 6) × 20`，6 分（及格线）为中性基准，高于 6 加分、低于 6 扣分——低分店热度真实下降，口碑差不再靠收藏/浏览撑高。
3. **公式文案后端下发**：`VenueHeatResponse.formulaText/formulaDetail` 由后端生成（权重唯一事实源），前端直接渲染、**禁止硬编码权重**（历史上前端 computeHeatFormula 硬编码 ×1/×10/×15/×5/×8/×3/×20，权重调整后展示即失真——已删除）。

### 数据采集层

**浏览记录（`qwt_venue_views`）**：已登录用户按 `(venueId, userId, viewDate)` 联合唯一约束去重（同一天仅一条）；匿名用户 `userId=null`，无法按身份去重，2026-08 起叠加 **60s 简单频控**（`VenueViewService` 内嵌 Caffeine，key = `venueId:客户端IP`，X-Forwarded-For 第一个地址，取不到时降级为固定 key 的场所级防抖）——压制脚本连点/自动刷新放大 PV。频控尽力而为，多 IP 分布式刷无法拦截；已登录用户由 upsert 按天去重，无需频控。前端进入详情页时 fire-and-forget 调用 `POST /venues/{id}/view`，失败静默。

**状态变迁日志（`qwt_venue_status_logs`）**：每次 `Venue.status` 字段变更时由 `VenueService` 自动写入（含创建时的初始记录 `fromStatus=null`）。记录 `fromStatus`、`toStatus`、`changedBy`、`createdAt`。用于统计"近 N 天暂停营业次数"和"当前状态持续天数"。

### 满意度计算

综合满意度 = 各维度（`RatingDimensions.ALL`）评分的等权均分，优先取近 30 天窗口数据，无近期数据时回退全量。评价总人数 < 3 时返回 `null`（前端展示"暂无足够评价"）。

**窗口口径（2026-08 确立）**：满意度窗口与 `ratingCount30d` 统一按 `created_at`（评分创建时间）统计，而非 `updated_at`——改分不把记录拉回窗口，防"定期改分让计数/满意度常青"的刷分漏洞（历史实现用 updated_at，用户反复改分即可让该行一直在窗口内）。注意：详情页评分 Tab 的三窗口展示（`TagAggregateStatsService.aggregateScoresMultiWindowByTag`）仍用 updated_at，那是"评分展示"的时效语义，与热度统计口径不同，勿混用。

### 统计口径：截至昨日（2026-07-31 确立）

`GET /venues/{id}/heat` 的所有滚动窗口指标（近30天浏览/收藏/动态/评价/Reaction、近14天收藏趋势）统一以**昨天 24 点**为排他上界，而不是请求发生的"此刻"：

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

### 查询性能优化（两轮：条件聚合 → 跨表合并 + refresh-ahead 缓存）

根因：服务器在阿里云 ECS，数据库在 Supabase（AWS ap-south-1），单次 DB 往返约 300-500ms，**接口延迟 ≈ 串行往返次数 × 单次往返**。应用层优化的唯一抓手是压缩每个接口的串行 DB 往返数；根本解是 DB 迁近区（迁移前所有新接口设计必须以"最少往返"为第一约束）。

**第一轮（同表条件聚合）**：同一张表的多个 COUNT/aggregation 用 `SUM(CASE WHEN...)` 合并为单条 SQL。**第二轮（跨表合并 + 编排优化）**：跨表单值聚合收敛为标量子查询 mega-query、两步查询合并为联查/upsert、聚合缓存 refresh-ahead。当前各接口冷启动往返数：

| 接口 | 往返构成 | 冷启动 | 缓存命中 |
|------|---------|--------|---------|
| `GET /venues/{id}/heat` | mega-query(1) + 收藏趋势(1) + 满意度(0~2，raters<3 跳过) | ~1s | ~3ms |
| `GET /venues/{venueId}/tags/stats` | 聚合（与详情页共享缓存，单飞回源 1 次）+ 个人状态(1) | ~0.5s | ~10ms |
| `GET /venues/{id}` | venue 缓存 + tagAggregate 缓存 + postCount/hasMyReport 合并(1) | ~1.2s | ~0.5s |
| `POST /venues/{id}/view` | upsert(1) | ~0.5s | — |
| `GET /favorites` | 收藏+场所联查(1) + Reaction 徽标批量(1，单条 SQL 返回 countAll+count7d+count30d) + 个人参与状态批量(1，需登录才能访问此接口，恒触发) | ~1.2s | — |
| `GET /venues` | 主查询(1) + count(1) + Reaction 徽标批量(1，单条 SQL 返回 countAll+count7d+count30d) + 个人参与状态批量(1，仅登录触发)（hotVenueIds 缓存 5min） | ~1.5s | 同左 |

关键合并查询（全部在对应 Repository 有根因注释）：

- `VenueRepository.countHeatCounters`：**跨 6 张表的标量子查询 mega-query**，一次往返取回热度公式与可信度所需的全部单值计数器。标量子查询是跨表合并的标准手段——各子查询均命中 `(venue_id, ...)` 索引，库内执行毫秒级，网络开销收敛为 1 次往返。多行形态（趋势时间序列、分组均值）不参与标量合并，保持独立查询
- `TagInteractionRepository.aggregateScoresMultiWindowByTag`：三窗口评分聚合单条 GROUP BY（原点赞计数 + 评分聚合合并查询已随点赞功能移除，仅保留评分部分）
- `VenueReactionRepository.aggregateByVenue` / `countByVenueIdsGroupByCode`：Reaction 四窗口条件聚合 / 批量场所三窗口计数（countAll + count7d + count30d 单条 SQL，条件 SUM 内联），取代原标签点赞批量查询
- `VenuePostRepository.findDetailStats`：动态总数 + "我是否已上报"（EXISTS 标量子查询，个人状态实时不缓存，匿名 userId=null 时 EXISTS 恒 false）
- `FavoriteRepository.findFavoriteVenuesByUserId`：收藏 + 场所 JPQL 联查（排序键为收藏 createdAt），取代"查收藏再批量查场所"两步
- `VenueViewRepository.upsertView`：`INSERT ... ON CONFLICT ON CONSTRAINT ... DO NOTHING` 无条件幂等写入，取代 check-then-act（SELECT 存在性 + INSERT + catch）。**约定：有唯一约束的幂等写入一律 upsert**，check-then-act 多一次往返且存在并发窗口

**聚合缓存（refresh-ahead，服务内嵌 Caffeine LoadingCache）**：`venueHeat`（VenueHeatService）与 `tagStats`（TagAggregateStatsService）**不走 Spring CacheManager**——`refreshAfterWrite` 要求 LoadingCache（构建时提供 loader，否则启动报 `refreshAfterWrite requires a LoadingCache`），Spring 缓存抽象无法为单个缓存注入各自的加载器。故采用与 AuthInterceptor 用户缓存相同的"服务内嵌原生 Caffeine"模式：loader 即聚合计算方法本身，获得四项语义：

1. **预刷新**：`refreshAfterWrite(60s)`——条目写入 60s 后，下一次访问立即返回旧值并**异步**重载。活跃场所的用户不再周期性吃到同步冷加载（早期 `expireAfterWrite(60s)` 硬过期导致每 60 秒出现一个 2s+ 慢请求，这是二轮优化要消除的核心症状）
2. **单飞**：同 key 并发回源只加载一次（LoadingCache 天然语义），详情页并发请求共享同一份回源
3. **刷新失败保留旧值**：瞬态 DB 抖动降级为数据滞后而非请求失败
4. **硬过期兜底**：`expireAfterWrite(30min)`，仅长期无访问的条目被驱逐

异步刷新运行在 ForkJoinPool.commonPool，无请求上下文——被缓存值必须与请求者身份无关（见「缓存内容的强制约束」），这是 refresh-ahead 安全的前提。

**实体缓存（Spring CacheManager + sync=true）**：`venueCache`（60s）与 `hotVenueIds`（5min）仍由 `config/CacheConfig` 托管，`@Cacheable(sync = true)`。sync=true 使 Spring 走 `cache.get(key, loader)` 路径，获得与 LoadingCache 相同的单飞语义（非 sync 的 `@Cacheable` 并发冷请求会重复回源）。这两类缓存是单查询低成本加载，到期单飞冷加载可接受，不用 refresh-ahead。

**写路径缓存逐出（显式化）**：聚合缓存改为内嵌 LoadingCache 后，失效从 `@CacheEvict` 注解改为**显式调用属主服务的 `invalidate(venueId)`**（注解无法作用于非 Spring 托管缓存）。完整逐出矩阵——任何改变热度/标签聚合输入的写操作完成后必须逐出对应缓存：

| 写操作 | 逐出动作 |
|--------|---------|
| score（TagInteractionService） | `tagAggregateStatsService.invalidate` + `venueHeatService.invalidate` |
| toggle（VenueReactionService） | `venueReactionAggregateService.invalidate` + `venueHeatService.invalidate` |
| addFavorite / removeFavorite（FavoriteService） | `venueHeatService.invalidate`（收藏数是热度输入；幂等无写入分支不逐出） |
| submitReport / cancelReport（StatusReportService） | `venueHeatService.invalidate`（活跃报告数是热度输出） |
| createPost（VenuePostService） | `venueHeatService.invalidate` + `@CacheEvict(hotVenueIds, allEntries)`（动态数参与热度与热门排序） |
| updateVenue（VenueService） | `@Caching` 逐出 venueCache + hotVenueIds + `tagAggregateStatsService.invalidate`（tags 是聚合组装依据）+ `venueHeatService.invalidate`（status/状态日志是热度输出） |
| createVenue（VenueService） | `@CacheEvict(hotVenueIds, allEntries)`（新场所无缓存存量） |

**VenueLookupService（场所查找缓存层）**：独立 Bean（与 `TagAggregateStatsService` 同模式——避免 `@Cacheable` 自调用陷阱），包装 `VenueRepository.findByIdAndDeletedFalse` 和 `findHotVenueIds`。详情页并发请求（详情/标签统计/热度）查询同一场所实体——缓存后仅首个请求回源（sync=true 单飞）。**写路径不使用本缓存**：`VenueService.updateVenue` 直接调用 Repository，通过 `@CacheEvict` 即时失效 `venueCache` 和 `hotVenueIds`。

此外，`AuthInterceptor` 内嵌 Caffeine 用户缓存（2min TTL，maxSize=500，直接使用 Caffeine API 非 Spring CacheManager），消除每个带 token 请求的鉴权查库往返。

**`@CacheEvict` 不可重复（Spring 4.x 平台坑位）**：Spring Boot 4.x 中 `@CacheEvict` 不是 `@Repeatable`，同一方法上写两个 `@CacheEvict` 会编译报错。需用 `@Caching(evict = { @CacheEvict(...), @CacheEvict(...) })` 包装多个失效操作。`VenueService.updateVenue` 即用此模式同时失效 `venueCache`（按 key）和 `hotVenueIds`（allEntries）。

**缓存内容的强制约束（2026-07-31 标签点赞"消失又恢复"事故后确立）**：被缓存的数据必须与请求者身份无关——只允许缓存所有用户共享的聚合结果（点赞总数、评分均值等），禁止把当前用户的个人交互状态（"我是否已赞"、"我的评分"）一并放进缓存值或缓存 key。个人状态必须在每次请求时实时查询（成本是一次简单的按 userId+venueId 查询，远低于聚合计算）。此约束同时是 refresh-ahead 异步刷新安全的前提（刷新线程无请求上下文）。

根因：早期 `TagInteractionService.getTagStats` 以 `{venueId, userId}` 为 key 整体缓存（含 likedByMe/myScore），点赞/评分写操作未失效缓存，用户点赞后 60 秒 TTL 内会出现"返回列表再进入详情页看到操作前的状态"；因用户重新打开小程序的间隔通常 > 60s，缓存已自然过期，问题被掩盖，误判为"完全重启后恢复正常"是预期行为。修复方案：聚合数据（点赞计数、评分均值）拆到独立的 `TagAggregateStatsService`，以 venueId 为 key 缓存；个人交互状态在 `TagInteractionService.getTagStats` 中永远实时查询、不缓存；写操作显式 `invalidate`，不等待刷新周期，保证点赞后所有用户很快看到最新点赞数。

**Spring `@Cacheable` 自调用陷阱（必须知晓）**：Spring 基于动态代理实现 AOP，方法内部通过 `this.xxx()` 调用同类的另一个 `@Cacheable`/`@CacheEvict` 方法会绕开代理，注解静默失效（不报错，但缓存/失效都不生效）。被缓存的方法必须从另一个 Bean 上调用。内嵌 LoadingCache 的 loader（`this::computeXxx`）同理不走代理——loader 方法不要挂任何依赖 AOP 的注解（事务、缓存），其内部调用其他 Bean 的代理方法（如 `venueLookupService.findById`）仍然生效。

**新增查询时的约定**：
- 同一张表的多个 COUNT/aggregation → `SUM(CASE WHEN...)` 条件聚合合并为单条 SQL；JPQL 不支持 `COUNT(DISTINCT CASE WHEN...)` 时用 `@Query(nativeQuery = true)` 并注明原因
- 跨表的多个**单值**聚合 → 标量子查询 mega-query 合并为 1 次往返（见 `countHeatCounters`）；多行形态（时间序列/分组）保持独立查询
- 有唯一约束的幂等写入 → `INSERT ... ON CONFLICT DO NOTHING` upsert，禁止 check-then-act
- 主从两步查询（先查关系表再查实体表）→ 评估能否联查（见 `findFavoriteVenuesByUserId`）

---

## 统一用户上报（venuefeedback 模块，2026-08-05 泛化）

### 设计定位

本模块从「场所信息纠错反馈」泛化为**统一用户上报模板**：任何"信息缺失/有误需管理员维护"的场景共用同一张表、同一个提交通道（`POST /venues/{venueId}/feedbacks`）与同一套管理端处理流程（`/admin/reports`）。**新增上报场景 = 扩展 `FeedbackType` 枚举 + 前端入口**，禁止为单个场景新写表/接口/表单——这是"通用模板"的可扩展性保证。

与 `venuestatusreport`（实时 4h TTL 众包信号）的边界保持：本模块是**异步管理员审核流程**（有处理状态），实时信号职责不在此承担。`SUSPENDED` 类型在此 = "不在场但认为状态信息有误"，现场确认关门走 venuestatusreport（见「场所状态上报」章节）。

### 类型与状态机

- `FeedbackType`：CLOSED_DOWN（已关门/停业）/ SUSPENDED（暂停营业）/ INACCURATE（信息有误）/ **PRICE（价格信息缺失或有误——门票/舞伴数据缺失空态的上报入口，2026-08-05 新增）** / OTHER（其他）
- `ReportStatus` 状态机：PENDING（待处理）→ RESOLVED（已处理）/ DISMISSED（已忽略）。**终态固定不可回退**——RESOLVED 表示管理员已核实并完成维护，DISMISSED 判定为误报/无需处理；布尔 handled 无法区分两种终态语义（历史 `handled` 列保留为实体兜底映射，见下文「Schema 演进」）

### 数据模型

`qwt_venue_feedbacks` 表：venueId + userId + type + note + status + handledBy + handledAt + handled（遗留兜底列）。索引 `(venueId)`、`(userId)`、`(status, createdAt)`（管理端状态筛选分页）。

**Schema 演进（2026-08-05 起默认自动更新，无手动 SQL）**：status 列默认值由 `@ColumnDefault("'PENDING'")` **单一通道**声明（配合 `@Column(length=20, nullable=false)` + 字段初始化器），`ddl-auto:update` 对已有数据的表自动加列时生成 `ADD COLUMN ... NOT NULL DEFAULT 'PENDING'` 不失败，存量行自动落为 PENDING——2026-08-05 曾因 columnDefinition 与 @ColumnDefault **双声明 DEFAULT** 报 "multiple default values specified"（修复 + 根因见「Schema 演进 → 事故根因」）；handledBy / handledAt 为可空列直接自动加列；遗留 `handled` 布尔列由实体字段映射兜底（@Deprecated + `@ColumnDefault("false")`，**双态兼容**：列已存在 → 匹配无操作，列已被历史脚本删除 → update 自动重建且存量行落默认值；insert 恒写 false 不违反约束）。**本模块不再需要任何手动迁移脚本**（上一版 migrate-report-status.sql 已删除）——新增字段一律通过 @ColumnDefault 默认值/可空列让 update 自动完成，规则见「Schema 演进（自动更新优先）」章节。

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/venues/{venueId}/feedbacks` | 需登录 | 提交上报（type 必填 + note 可选），响应含 maintenanceHint |
| GET | `/admin/reports` | ADMIN | 平台级列表（status/type 可选筛选，分页倒序，含 venueName） |
| POST | `/admin/reports/{id}/resolve` | ADMIN | 标记已处理（幂等：终态重复操作直接成功） |
| POST | `/admin/reports/{id}/dismiss` | ADMIN | 标记已忽略（幂等） |

管理端列表场所名称批量查询（`VenueRepository.findByIdInAndDeletedFalse`）消除 N+1；已逻辑删除的场所回退"已下架场所"占位。

### 维护承诺配置（maintenanceHint）

提交响应携带 `maintenanceHint`（"已通知管理员，我们会在 X 日内维护好"），**X 来自配置 `app.reports.maintenance-days`（`config/ReportsProperties`，默认 3，缺失自动回退）**——前端 toast/空态直接展示，禁止硬编码承诺天数。调整承诺天数只改一处配置。

---

## 分享追踪（venueshare 模块，2026-08-05 新增）

### 设计定位

「去舞厅」是线下社交消费场景，分享（转发到好友/群聊）是产品自然增长的主要通道。本模块承载分享行为的**事件追踪数据通道**（P2）：前端上报分享动作与被分享者打开，数据落 `qwt_venue_shares` 事件日志，支撑邀请排行 / 热门传播门店 / 回流归因分析。**前端入口与分享内容契约见前端 AGENTS.md「分享能力规范」章节**（此处只述后端契约）。

### 数据模型（单表双事件）

`qwt_venue_shares`：`(id, venue_id, user_id, event_type, channel, share_from, created_at)`

| 字段 | 语义 |
|------|------|
| `event_type` | SHARE（分享动作） / OPEN（被分享者打开） |
| `user_id` | 事件发起者（分享者 / 打开者），匿名为 NULL（仅参与 IP 频控，不参与身份归因） |
| `channel` | 分享渠道（仅 SHARE）：BUTTON（页内按钮）/ MENU（右上角菜单）/ TIMELINE（朋友圈） |
| `share_from` | 归因来源（仅 OPEN）：原分享者用户 ID，来自分享路径 `share_from` 参数 |

事件日志语义：**只追加，不修改不删除**，无唯一约束（每次分享/打开是一条独立事件）。**Schema 由 ddl-auto:update 自动创建**（2026-08-05「Schema 演进自动更新优先」策略：新表不再写迁移脚本，`@Index` 声明随实体自动建索引）。

### 接口（软鉴权，fire-and-forget）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/venues/{id}/shares` | 软鉴权（匿名可上报） | 记录分享动作，body `{channel}`（可选，@Pattern 校验 400） |
| POST | `/venues/{id}/share-opens` | 软鉴权（匿名可上报） | 记录分享打开，body `{shareFrom}`（可选） |

与 `POST /venues/{id}/view` 同语义族（`VenueViewService` 模式）：

- **fire-and-forget**：失败不影响主流程；**不做场所存在性校验**（事件端点由详情页发起，场所不存在时详情页已 404，冗余的场所查询对事件端点是不合理的延迟负担；孤儿事件不会被任何统计引用）
- **60s 频控**：同场所同身份（已登录按 userId，匿名按 IP）60s 窗口内最多记 1 条（Caffeine，`VenueShareService`），压制脚本连点刷事件放大分享/回流量的漏洞（尽力而为，多 IP 分布式刷无法拦截，与浏览频控同语义）

### 边界（与热度公式解耦）

分享维度**不在热度公式闭集内**（公式由产品定义，见「场所热度」章节），本模块**不 invalidate 热度缓存**、不参与任何展示逻辑——纯分析数据源。若未来产品将分享纳入热度公式，需同步修改公式文案（`formulaText`/`formulaDetail` 后端下发）与热度服务。

---

## 场所状态上报（venuestatusreport 模块）

### 设计定位

舞厅门店状态变更（警察检查、突然关门）发生频率远高于管理员手动更新 `Venue.status` 的能力——极端情况可能 30 分钟内多轮检查导致反复开关门。`venuefeedback` 模块是异步管理员审核流程（有 PENDING/RESOLVED/DISMISSED 状态机），无法满足实时性需求。此模块提供**实时众包信号层**：用户在现场一键报告"现在关门了"，信号对其他用户即时可见。

**与 venuefeedback 的边界**（重要）：

- `venuefeedback.SUSPENDED` = "我不在场但认为状态信息有误"→ 异步管理员审核 → `ReportStatus` 状态机流转（RESOLVED/DISMISSED）
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

**活跃判定口径契约（2026-08-05 修复，根因案例）**：所有"活跃报告"判定点必须经参数传入同一 TTL 窗口（`StatusReportService.ACTIVE_REPORT_TTL_HOURS` 为唯一常量权威源），**SQL 层禁止自行定义时间窗**。活跃判定点清单：

- `VenueRepository.countHeatCounters` 的 `reportcount` / `latestreporttime`（热度聚合，`reportSince` 参数）
- `StatusReportRepository.countActiveAndLatestTime`（提交/撤销响应摘要）
- `VenuePostRepository.findDetailStats` 的 `hasmyreport` EXISTS（详情页个人已报告标记）——**历史实现只过滤 `deleted = false` 漏 TTL 过滤**，与热度聚合口径不一致：TTL 过期后 `activeReportCount` 归零但 `hasMyStatusReport` 恒真，详情页"已报告·补充"按钮永不还原（用户必须手动撤销）。此为修复根因，新增活跃判定查询时必须对照本清单。

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/venues/{venueId}/status-reports` | 需登录 | 上报暂停（body 可空=快速上报，或含 reason/occurredAt/note） |
| POST | `/venues/{venueId}/status-reports/cancel` | 需登录 | 撤销我的上报（软删除） |
| GET | `/status-reports/mine` | 需登录 | 我的全部状态上报（用户级资源，顶层路径，见下「我的上报记录」） |

### 我的上报记录（GET /status-reports/mine，2026-08-05 新增）

详情页「我的上报记录」弹窗的数据源。**用户维度资源**（跨场所），路由放顶层 `/status-reports/mine` 而非场所子资源路径（与 `/favorites` 用户级资源模型一致，区别于 `/venues/{venueId}/status-reports` 的场所子资源）。

- **范围**：仅未撤销（`deleted = false`）记录，含已过期（TTL 外）——「已过期」记录前端标注后提醒用户可重新上报；已撤销记录不返回（撤销是用户主动收回动作，soft delete 属内部实现细节，语义上不再属于"上报记录"）
- **实现**：`StatusReportRepository.findMyReportsByUserId` 原生 SQL JOIN `qwt_venues` 一次取回场所名称/城市/区县/地址（消除 N+1）；`active` / `expiresAt` 在 `StatusReportService.listMyReports` 按 `ACTIVE_REPORT_TTL_HOURS` 统一计算（TTL 唯一权威源，SQL 不自行定义时间窗）
- **场所软删除不回退占位**：JOIN 不过滤 `v.deleted`——记录真实性不因场所下架而消失，与 /admin/reports 的"已下架场所"占位策略有意区分（那是管理端当前列表，这是用户历史记录）
- **响应**：`MyStatusReportResponse`（id/venueId/venueName/venueCity/venueDistrict/venueAddress/createdAt/active/expiresAt），前端展示剩余时间只做 `expiresAt - now` 纯计算，不持有 TTL 常量

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

### 缓存失效（显式 invalidate 热度缓存）

`submitReport` 和 `cancelReport` 在写入完成后调用 `venueHeatService.invalidate(venueId)` 显式失效热度缓存（热度为 `VenueHeatService` 内嵌 LoadingCache，不走 Spring `@CacheEvict`，见「查询性能优化 → 写路径缓存逐出」）。活跃报告数是热度响应的输出之一，上报/撤销后必须让其他用户及时看到最新信号。

`getActiveReportSummary()` 本身不缓存，仅供 `submitReport` / `cancelReport` 组装响应使用——热度接口已不经由它，活跃上报计数内联在热度 mega-query（`countHeatCounters`）的标量子查询中，随热度缓存整体命中/逐出。

---

## 评分交互（taginteraction 模块）

### 设计定位

评分（rating）：1-10 量化评价，"这个维度体验如何"。适用于系统定义的标准评分维度（`RatingDimensions.ALL`），与管理员标签相互独立，用于计算综合评分（门店整体质量比较）。

> **历史沿革**：本模块早期还承载"标签点赞"（对 `Venue.tags` 的二值认同信号）与"现场状况"三个众包评分维度（舞伴氛围/客流热度/舞伴年龄层）。2026-08 上线 Reaction 快速反馈系统后，这两类功能均被替代——见「Reaction 快速反馈系统」章节的根因说明。`qwt_tag_interactions.liked` 列作为历史遗留字段保留在库中不再读写（与 `qwt_users.avatar_url` 同处理原则），`TagInteraction.liked` 字段已从实体中移除。

### 数据模型

`qwt_tag_interactions` 表：一个用户对一个舞厅的一个评分维度至多一行（`UNIQUE(userId, venueId, tag)`）。`tag` 字段存储评分维度名称（服务/环境/音响效果/性价比）。

索引：`(venueId, tag)` 覆盖聚合查询，`(venueId, tag, updatedAt)` 覆盖时间窗口查询。

### 评分维度（RatingDimensions）

系统统一定义的标准维度列表，所有舞厅共享，不依赖管理员是否添加了对应标签。新增维度只需修改 `RatingDimensions.ALL` 常量，前端通过 `tags/stats` 接口的 `dimensions` 字段自动同步。

| 维度 | 锚定文案 | 说明 |
|------|----------|------|
| 服务、环境、音响效果、性价比 | 1 最差 / 10 最好 | 主观质量打分，全量均分参与综合评分计算 |

原"现场状况"三维度（舞伴氛围/客流热度/舞伴年龄层）已被 Reaction 快速反馈系统的对应表情替代（👧年轻舞伴多 / 👴舞伴年龄偏成熟 / 🔥人气旺等）——两者语义重叠，是"标签、评分、热度模块信息混杂"问题的直接根因，详见下一章节。

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/venues/{venueId}/tags/stats` | 公开（软鉴权） | 维度评分（含时间窗口）+ 当前用户评分状态 |
| POST | `/venues/{venueId}/tags/score` | 需登录 | body: `{tag, score}`，upsert 语义（首次=打分，再次=修改覆盖） |

### 防刷机制

评分接口设 60 秒冷却期（`SCORE_COOLDOWN_SECONDS`）：同一用户同一维度在冷却期内重复提交返回 1006 错误。基于 `updatedAt` 判定（改分后自动刷新时间戳）。

### 时效性

评分统计支持三个时间窗口：全部 / 近 30 天 / 近 7 天。基于 `updatedAt`（用户最近一次修改评分的时间）过滤，改分后新分数归入近期窗口。聚合实时计算（当前数据规模无需物化视图）。

### 约束

- 评分仅允许 `RatingDimensions.ALL` 中的维度，历史遗留维度（如旧的"舞伴氛围"）不再被 `isValid` 承认，相关历史行不参与任何聚合计算

---

## Reaction 快速反馈系统（venuereaction 模块）

### 设计动机与根因（2026-08 确立）

舞厅场景的用户核心诉求不是"查看传统评论"，而是在不能/不便评论的情况下快速了解：这家店现在值不值得去、舞伴年龄和质量如何、氛围人气如何、服务环境等真实体验。早期实现将这类反馈拆散在三处——描述性标签的"点赞"（`TagInteraction.liked`）、"现场状况"众包评分维度（舞伴氛围/客流热度/舞伴年龄层，1-10 打分）、以及场所热度——三者语义高度重叠但交互形态不一致（点赞 vs 1-10 选择器 vs 被动统计），造成"标签、评分、热度模块信息混杂"，用户需要在多套交互里重复表达同一件事，认知负担重。

**解法**：统一为类似 Telegram Reaction 的表情化标签（emoji + 文字说明），一次点击表达一种态度，替代原"标签点赞"与"现场状况"评分维度：

- 默认只展示 emoji，长按显示文字说明，点击直接 +1（当日再次点击取消）
- 不做"点赞/倒赞"二元对立（不提供 👍👎 成对选项）——采用具体、中性的正负向 Reaction 共存（如 👴 舞伴年龄偏成熟，而非"倒赞：舞伴年龄大"），避免攻击性评价引发商家纠纷；单个语义具体的正向表达（如 👍 值得推荐、🍺 消费合理）允许
- 一个用户对一个场所的一个 Reaction **每天最多一次**（不允许 🔥🔥🔥🔥 刷数据），但允许同时选择多个不同 Reaction

### 每日一记模型（2026-08 核心设计决策，替代旧"toggle 软删 hold 模型"）

**数据模型**：`qwt_venue_reactions` 表 `(id, userId, venueId, reactionCode, reactionDate, createdAt, updatedAt, deleted)`，唯一约束 **`UNIQUE(userId, venueId, reactionCode, reactionDate)`**——同一用户每天只能贡献一次同类型 Reaction。每次点击 = 插入一条 `reactionDate = 今天` 的记录；取消 = **物理删除**当日记录（"取消当天 Reaction"语义，不做软删——按日唯一槽位随日期自然消亡，无软删恢复需求，与 Favorite/StatusReport 的软删模式形成有意差异，原因见下）。

**根因（旧模型的三处缺陷 → 每日一记修复）**：

1. **需求语义违约**：需求要求"每天最多点击一次、当天不可重复增加数量、次日自动恢复可点击状态（可再次 +1）"——Reaction 是"用户近期体验评价"，应允许用户次日重新评价。旧 hold 模型（toggle 软删 + 恢复刷新 createdAt）下用户次日点击是"取消"而非"再次表达"，无法自然完成每日重新评价。
2. **窗口计数本地不可推导 → 双计数 hack**：旧模型下取消可能作用于 createdAt 超窗的旧记录（如持有 40 天后取消），count7d/count30d 的本地 ±1 无法精确推导，迫使前端发明"展示 countAll、排序 count30d"的双计数分离 + 前端只展示 countAll 的妥协。而需求要求列表默认展示近7天、可切换近30天/全部——展示数字必须是所选窗口计数。每日一记模型下取消**只可能作用于当日记录**（今日新增必落在全部窗口内、取消删掉的也必是当日记录），countAll/countToday/count7d/count30d 四个窗口的本地 ±1 **全部精确**，双计数 hack 整体消失，列表窗口切换直接展示窗口计数。
3. **未来扩展被堵死**：周末热门/实时热门/到店权重等扩展都需要"按日记录"（reaction_date 维度）做聚合，旧模型单行无日期维度，历史数据不可回填。每日一记从第一天起就按日天然聚合。

**时间衰减仍然有效**：不做周期性清零——原始记录永久保留，窗口统计实时计算（countToday/count7d/count30d 按 created_at 滚动窗口，锚点"此刻"：今天0点 / now-7d / now-30d，不同于热度模块"截至昨日"的排他上界约定），记录随日期推移自然滑出近期窗口；"全部"窗口承载历史画像层。两层概念（实时层 vs 画像层）的约定不变。

**为何取消用物理删除而非软删**：软删 + 按日唯一约束下，同日"取消后再点"需要恢复旧行（槽位被软删行占用），与"取消即当日贡献移除"语义叠加复杂度；物理删除使按日槽位随日期自然释放，逻辑最简，且本表无历史追溯需求（个人参与历史不对外展示）。与 Favorite/StatusReport 软删模式的区别：后者的唯一槽位是"永久性"的（user-venue 关系），必须靠软删占用防重复；Reaction 的槽位按日自然过期。

### Reaction 字典（ReactionCode，后台维护）

Reaction **不允许用户自由创建**——避免色情/攻击/广告/竞对刷评价。字典是后台维护的 Java 枚举（`ReactionCode`），emoji + label 由后端唯一定义并通过接口下发，前端 Picker 的静态字典（`constants/reactions.ts`）是镜像副本需两端同步。

| 代码 | Emoji | 说明 |
|------|-------|------|
| HOT | 🔥 | 人气旺 |
| GOOD_VIBE | 💃 | 氛围好 |
| GOOD_MUSIC | 🎵 | 音乐棒 |
| RECOMMEND | 👍 | 值得推荐 |
| FAIR_PRICE | 🍺 | 消费合理 |
| YOUNG_PARTNER | 👧 | 年轻舞伴多 |
| OLD_PARTNER | 👴 | 舞伴年龄偏成熟 |
| CLEAN | ✨ | 干净整洁 |
| GOOD_SERVICE | 💁 | 服务贴心 |
| NORMAL | 😐 | 普通 |
| CROWDED | 👥 | 人多拥挤 |
| WAITING | ⏳ | 排队太久 |
| QUIET | 🪑 | 人气冷清 |
| HIGH_COST | 💰 | 消费较高 |
| BAD_ENV | 😕 | 环境一般 |
| SERVICE_ISSUE | 😡 | 服务问题 |

2026-08 扩版（表情越多越好）：初版 9 项 → 16 项，覆盖人气/氛围/音乐/推荐/消费/舞伴/环境/服务/拥挤/排队等舞厅体验维度，按"正向 → 中性 → 负向"的展示序（影响前端 Picker 网格顺序）。

审核安全说明：字典坚持"具体、中性描述"原则——`BAD_ENV` 未采用需求初稿中的 🤮（呕吐表情），因强烈厌恶语义与"不引入攻击性反馈"的设计原则相悖；`RECOMMEND` 的 👍 是**单一正向表达**而非 👍👎 二元组合，允许使用。新增/调整字典条目时需过审核安全过滤（参照 venuestatusreport 模块 `ReportReason` 命名规避敏感词的先例）。

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/venues/{venueId}/reactions/stats` | 公开（软鉴权） | 字典内全部 Reaction 的四窗口统计 + 当前用户"今日已参与"状态，详情页"大家对这里的感受"+"查看更多"用 |
| POST | `/venues/{venueId}/reactions/{code}` | 需登录 | toggle 语义（今日未参与=参与，今日已参与=取消当日），`code` 为路径变量而非请求体（字典固定，路径更简洁） |
| GET | `/venues?...&window=7d/30d/all` | 公开（软鉴权） | 列表接口新增 `window` 参数：控制卡片 Top Reaction 徽标的排序/筛选窗口，默认 `7d`（近7天） |

**toggle 并发**：同日并发重复插入触发唯一约束冲突 → 幂等视为已参与（`DataIntegrityViolationException` 捕获 + `entityManager.clear()`）。前端每 code 一个 in-flight 守卫（见前端 AGENTS.md）已把同端连点串行化，本防御兜底多端竞态。

### 列表页 Top Reaction 徽标（VenueResponse.topReactions，替代原 tagLikeCounts）

`GET /venues`（按 `window` 参数）、`GET /favorites`（固定默认窗口 7d）等复用 `VenueResponseMapper` 的接口在 `VenueResponse.tags` 之外携带 `topReactions: List<ReactionBadge>`（最多 4 个，按所选窗口计数降序，该窗口计数=0 的不展示）。创建新 Reaction 的入口是前端 Picker 表情选择器（长按卡片触发），不是 count=0 的占位 chips——此决策的根因分析见前端 AGENTS.md「Reaction 快速反馈系统 → 设计决策 → 展示与创建职责分离」。

**三窗口计数语义（2026-08 每日一记模型确立）**：`ReactionBadge` 携带 `countAll` / `count7d` / `count30d` 三个窗口计数 + `reactedByMe`。服务端只做"按所选窗口排序/筛选 Top 4"，前端展示数字 = 所选窗口计数，切换窗口仅本地重算（无需为每个窗口重复请求）。排序/展示统一所选窗口（不再是旧模型的"排序 count30d、展示 countAll"双计数分离）——每日一记模型下取消只作用于当日记录，三窗口的本地 ±1 全部精确，乐观更新无需回滚校正窗口计数。

- **例外：含个人参与状态（`reactedByMe`）**——这是对项目既有"列表层不含个人状态"惯例（原 `tagLikeCounts` 的设计）的刻意打破。原因是产品规则明确要求"点击 Emoji：未参与→+1，已参与→取消"必须在列表页直接可用，用户点击前必须知道自己是否已参与，否则会造成"点了却不知道是加还是减"的困惑。此例外**不违反**「缓存内容的强制约束」——聚合计数仍然缓存共享（`VenueReactionAggregateService`），个人参与状态通过**独立的、不缓存的实时批量查询**（`findTodayCodesByUserAndVenueIds`，一次 `IN` 查询覆盖整页场所）获取，两者未被塞进同一个缓存 key
- **个人状态语义**：`reactedByMe` = "今日已参与"（`reactionDate = 今天` 的记录存在）——次日自动恢复可点击状态，与"每日一记"模型一致
- **批量查询**：`VenueReactionService.batchGetBadges(venueIds, currentUserId, window)` 一次 `IN` 查询（`countByVenueIdsGroupByCode`，单条 SQL 用条件 SUM 同时聚合 countAll/count7d/count30d）覆盖聚合计数、一次 `IN` 查询覆盖个人状态（仅登录用户触发），避免逐场所查询的 N+1

toggle 写操作完成后必须同时失效 `VenueReactionAggregateService`（本模块聚合缓存）与 `VenueHeatService`（Reaction 总量是热度公式输入之一），见 `VenueReactionService.toggle()`。

### 聚合缓存架构

`VenueReactionAggregateService` 与 `TagAggregateStatsService`/`VenueHeatService` 同模式：内嵌 Caffeine `LoadingCache<Long, Map<String, long[]>>`（venueId → 每个 Reaction 代码的 `[countAll, countToday, count7d, count30d]`），`refreshAfterWrite(60s)` + `expireAfterWrite(30min)` + 单飞 + 写路径显式 `invalidate`。个人参与状态（`reactedByMe`）永远实时查询、不缓存，与既有的"缓存内容强制约束"完全一致。

### 迁移与数据说明

- `src/main/resources/db/migrate-reaction-daily.sql`：旧 hold 模型 → 每日一记模型的历史迁移（硬删旧软删行、`reaction_date` 回填 `created_at::date`、唯一约束 (user,venue,code) → (user,venue,code,date)）。**历史脚本（已执行），属旧范式遗留**——新约定不再新增此类手动脚本，schema 演进一律走 `ddl-auto: update` 自动完成（见「Schema 演进（自动更新优先）」章节）
- 旧行语义近似：迁移后旧"当前生效"行按 createdAt 日期成为"该日一次点击"，窗口统计语义自洽。

### 与现有模块的关系（保留 / 删除 / 替换）

| 模块 | 处置 |
|------|------|
| 综合评分（RatingDimensions 体验评估 4 维度） | **保留**，用于门店整体质量比较，`taginteraction` 模块继续承载 |
| 场所热度（VenueHeatService） | **保留**，用于门店流量排序；Reaction 近30天总数替代原点赞数作为其中一项输入（查询逻辑不变） |
| 营业状态 / 场所状态上报 | **保留**，用于实时判断是否值得去，与 Reaction 是两套独立信号层 |
| 固定属性标签（Venue.tags，如"禁烟""有舞池""自助存包"） | **保留**，纯展示不可互动；原"点赞"这一互动方式已删除 |
| 标签点赞（`TagInteraction.liked`） | **删除**，替换为 Reaction 快速反馈 |
| "现场状况"评分维度（舞伴氛围/客流热度/舞伴年龄层） | **删除**，替换为对应 Reaction（👧/👴/🔥 等） |

### 后续扩展（P1，未在本次实现范围内）

- 门店画像可视化（基于四窗口数据生成"年轻指数/人气/服务/消费"星级评分展示）
- 热门 Reaction 排序算法优化（当前列表徽标按所选窗口原始计数排序；可演进为"今日×5 + 7天×3 + 30天×1"加权评分以更强调近期活跃度）
- 周末热门 / 实时热门 / 到店用户权重（GPS 到店验证）——每日一记模型已按日聚合，可直接基于 `reaction_date` 实现

---

### 系统默认标签（VenueDefaultsConfig）


**设计动机与根因**：标签系统最初没有"默认"概念——所有标签由管理员手动输入，SQL seed 数据也需手工写入通用标签。这导致：(1) 数据冗余——每个门店重复存储相同标签；(2) 数据不一致——新门店可能忘记添加基础标签；(3) 修改困难——改一个默认标签需更新所有行。

**架构**：系统默认标签是业务规则（非数据），定义在 `application.yaml`（`venue.default.tags`），读时合并，不在 DB 中存储。

**合并规则**：
- DB `qwt_venues.tags` 只存储管理员自定义标签
- 读路径合并：`effectiveTags = defaults ∪ customTags`（去重，默认在前）
- 写路径防御：`VenueService.createVenue()/updateVenue()` 通过 `VenueDefaultsConfig.filterCustomOnly()` 剥离可能误传的默认标签
- 配置键 `venue.default.tags` 在 `application.yaml` 中定义，所有 profile 继承默认值

**注入点**（共 3 处）：
| 类 | 用途 |
|----|------|
| `VenueResponseMapper` | 合并默认标签到 `VenueResponse.tags`，填充 `VenueResponse.defaultTags` |
| `TagAggregateStatsService.computeAggregate()` | 合并默认标签到 venueTags，确保无交互的默认标签以 0 计数出现 |
| `VenueService.createVenue()/updateVenue()` | 防御性剥离传入标签中的默认标签，确保 DB 纯净 |

**缓存影响**：因 `computeAggregate` 的 venueTags 现在包含默认标签，聚合缓存失效周期不变（60s refresh-ahead），无需调整。

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
- **查询实现**：`VenueRepository.findHotVenueIds()` 使用 PostgreSQL 窗口函数（`ROW_NUMBER() OVER (PARTITION BY city ORDER BY score DESC)`）在库内完成城市内排名，避免在 Java 侧逐城市遍历。排序口径为 `sortWeight + 收藏数×20 + 动态数×10`（与列表查询的无坐标变体一致，不含距离项——距离是用户维度，场所热度排名不应因请求者位置变化）。Service 层通过 `VenueLookupService.getHotVenueIds()`（`@Cacheable(CACHE_HOT_VENUE_IDS)`，5min TTL）获取热门 ID 集合，缓存命中时 <1ms，未命中时执行全表窗口函数查询。场所创建/更新时通过 `@CacheEvict(allEntries=true)` 即时失效
- **VenueResponseMapper 三参重载**：`toResponse(Venue, List<ReactionBadge> topReactions, boolean isHot)` ——在已有双参重载基础上追加 `isHot` 参数。Service 层先调用 `venueLookupService.getHotVenueIds()` 获取热门 ID 集合（`Set<Long>`），再在 `result.map()` 中传入 `hotVenueIds.contains(v.getId())`。双参重载默认 `isHot=false`，单参重载亦默认 `isHot=false`（创建/编辑回显场景无需热门标记）

---

## 开发测试数据

`src/main/resources/db/seed-dev.sql` 提供开发环境种子数据（5 个场所、3 个用户、4 条动态、3 条收藏、6 条 Reaction），覆盖已认领 / 未认领、各场所状态、商家 / 平台动态等场景。使用方式：应用以 dev profile 启动一次（自动建表）后，在 Supabase SQL Editor 或 psql 中手动执行。脚本末尾通过 `setval` 重置 IDENTITY 序列，避免后续自增 ID 冲突。

`src/main/resources/db/repair-schema-identity.sql` 是 2026-08-04 迁移事故的**幂等修复脚本**（回填 NULL id 脏行、重建主键、恢复 IDENTITY、序列定位、恢复 NOT NULL 与默认值），也是任何手工建库/迁库后结构不达标时的标准修复入口。见「Schema 完整性与数据库迁移规范」。

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
| 1007 | 无效的评分维度 / Reaction 类型 |
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
- 若字段未来需要独立查询、排序、元数据（如图片描述、上传者），再升级为关联表——新表由 `ddl-auto: update` 自动创建，无需手动 SQL（见「Schema 演进（自动更新优先）」章节）

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

### 并发写入竞态处理（upsert 优先，唯一约束 catch 兜底）

有唯一约束的写入分两类，处理方式不同：

**① 纯幂等插入（不需要已有行状态）→ `ON CONFLICT DO NOTHING` upsert，首选**。单次 DB 往返完成"不存在则插入、存在则忽略"，去重与并发竞态全部由库内唯一约束兜底，无并发窗口：

```java
@Query(value = "INSERT INTO ... VALUES (...) ON CONFLICT ON CONSTRAINT <约束名> DO NOTHING",
       nativeQuery = true)
void upsertXxx(...);
```

实例：`VenueViewService.recordView`（浏览记录按天去重）。早期实现为 check-then-act（先 SELECT 存在性再 INSERT），多一次跨洲往返且 SELECT 与 INSERT 之间存在并发窗口——upsert 同时消除两者。

**② 需要依据已有行状态分支（恢复软删 / toggle / 频率限制）→ find-then-modify + 唯一约束 catch 兜底**。此类场景 SELECT 是"必要读"（要拿到已有行才能决定更新/恢复/切换），不属于冗余往返；check-then-act 的并发窗口由 catch 收口：

```java
try {
    repository.save(entity);
} catch (DataIntegrityViolationException e) {
    // 并发竞态：另一请求已插入，幂等忽略
    log.debug("并发冲突，幂等忽略: ...");
}
```

实例：`FavoriteService.addFavorite`（软删恢复）、`TagInteractionService.score`（冷却检查）、`VenueReactionService.toggle`（软删恢复/切换）、`StatusReportService.submitReport`（软删恢复 + TTL 续期，catch 后必须 `entityManager.clear()`）。若 INSERT 后还需后续操作（如 toggle），冲突时应重新查询已有记录再执行后续逻辑。

---

## 请求耗时日志（慢请求定位）

2026-08 接口慢排查后确立。项目早期请求级埋点为零，"慢"无法归因（后端处理 / 网络传输 / 客户端排队无从区分）。慢请求定位依赖以下两层埋点，前后端日志经同一个 `X-Request-Id` 关联。

### RequestTimingFilter（config/RequestTimingFilter.java）

Servlet Filter（继承 `OncePerRequestFilter`，`@Component` 自动注册 + `@Order(Ordered.HIGHEST_PRECEDENCE)`），统一记录所有请求的端到端处理耗时：

```
INFO  [http] GET /venues/14/tags/stats -> 200 cost=9ms rid=r3-m1abc
WARN  [http] GET /venues/14 -> 200 cost=2412ms rid=r4-m1abd [SLOW]
```

- `cost` 覆盖 Filter → AuthInterceptor → Controller → Service 全链路，即"服务端处理耗时"。前端同 rid 日志的 cost − 后端 cost ≈ 网络传输开销（含 Cloudflare Tunnel）
- `SLOW_THRESHOLD_MS`（当前 1000ms，依据单次跨洲 DB 往返 ~300-500ms 定档）及以上升级为 WARN，便于日志中快速筛出慢请求
- `rid` 读取前端 `X-Request-Id` 请求头（小程序 `services/requestPerf.ts` 生成）；无此头的请求（curl 等）自动生成 `s` 前缀 ID
- 必须保持最高优先级：若其他 Filter 排在其前，计时将漏掉前置处理

### AuthInterceptor 用户缓存计时

软鉴权对 JWT 验签后，用户实体通过**内嵌 Caffeine 缓存**（2min TTL，maxSize=500）查询，不再每个请求都查库。`preHandle` 对 JWT 验签与用户查找分别计时，输出 `[auth] uid=.. jwtVerify=..ms lookup=..ms`。`lookup` 在缓存命中时 <1ms，未命中时 = 完整 DB 往返（300~700ms）。role 取自 DB（经缓存），不取自 JWT payload——保证管理员调整角色后 2 分钟内生效。新增鉴权链路逻辑时保持该计时结构。

### 已知慢请求基线（2026-08 二轮优化后实测）

- 服务器 ↔ Supabase（AWS ap-south-1）单次 DB 往返约 300~500ms——应用层无法改变的单次成本，只能压缩往返次数
- **缓存层**：AuthInterceptor 用户缓存（2min）消除每请求鉴权查库；VenueLookupService 场所缓存（60s，sync 单飞）消除详情页重复场所查询；热门场所 ID 缓存（5min）消除列表窗口函数全表扫描；热度/标签聚合为内嵌 LoadingCache（refresh-ahead，见「查询性能优化」）
- 二轮优化后实测（本地开发机，缓存命中 vs 冷启动）：
  - `GET /venues/{id}/heat`：~3ms（缓存命中）/ ~870ms（冷启动，mega-query+趋势 2~4 往返）vs 一轮优化后冷启动 ~2100ms
  - `GET /venues/{venueId}/tags/stats`：~10ms（聚合缓存被详情请求预热共享）/ ~500ms（冷）
  - `GET /venues/{id}`（详情）：~480ms（venue+聚合缓存命中，仅 detailStats 1 往返）/ ~1200ms（冷）vs 优化前 ~1600ms
  - `POST /venues/{id}/view`：~500ms（upsert 1 往返）vs 优化前 ~800ms
  - `GET /favorites`（收藏）：~1000ms（联查+标签批量 2 往返）vs 优化前 ~1160ms
  - `GET /venues`（列表）：~1200-2400ms（主查询+count+标签批量 3 往返，冷启动含 hotVenueIds 窗口函数）
  - 详情页首屏（前端 onLoad 四请求并发）：≈ max(各请求耗时)，不再串行叠加；聚合缓存单飞使并发请求共享回源
- **剩余根因级优化（待决策）**：DB 迁移至近区（如 ap-southeast-1 / ap-northeast-1），单次往返可降至 60~100ms，所有接口延迟按比例下降。应用层往返压缩已接近形态下限，迁库是下一个数量级的收益
- 定位顺序：先筛 WARN 的 `[SLOW]` 日志确定后端 cost → 与前端 `[http]` 日志同 rid 对比确定网络占比 → 看 `[auth]` 日志确定 lookup 是缓存命中还是 DB 回源

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

- 基础 `application.yaml` 的 `ddl-auto` 为 `update`（2026-08-05 起统一自动演进策略，dev/prod 一致）——新增表/列由启动时自动完成，不手动执行 SQL；规则见「Schema 演进（自动更新优先）」章节
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

## Schema 演进与数据库完整性（2026-08-05 确立自动更新优先）

### Schema 演进策略（默认自动更新，不手动执行 SQL）

**核心决策（2026-08-05）**：schema 演进默认由 Spring Data JPA 的 `ddl-auto: update` 在应用启动时自动完成（新表自动创建、新列自动追加）——**不编写、不执行手动 SQL 迁移脚本**（`application.yaml` 已统一为 update，dev/prod 同策略）。上一版"新增列需手动执行 migrate-*.sql"的约定废止。

**新增列的三条硬规则（保证 update 对已有数据的表自动加列成功）**：

1. **新增 NOT NULL 列必须携带默认值，唯一声明通道是 `@ColumnDefault`**——`@Column(nullable = false) + @ColumnDefault("'XXX'")`（枚举类列；`@ColumnDefault` 的值是原始 SQL 表达式，字符串要带引号如 `"'PENDING'"`）；update 生成 `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT ...`，PostgreSQL 快速默认值不重写表，存量行自动落默认值。**禁止裸 `@Column(nullable = false)` 无默认值**——对已有数据的表加列会直接失败（这是历史 migrate-*.sql 存在的根本原因，如今规则上杜绝）。**禁止在 `columnDefinition` 中携带 DEFAULT/NOT NULL 等与 JPA 元数据重叠的语义**——Hibernate 会把元数据派生的 `default ...` / `not null` / 枚举 `check` 追加到 columnDefinition 原文之后，双声明生成非法 DDL（`... DEFAULT 'X' default 'X' ...` → Postgres "multiple default values specified"）。Java 字段初始化器只负责内存态默认值、**不参与 DDL 生成**，不能替代 @ColumnDefault。`columnDefinition` 仅限方言特有类型片段（如 `jsonb`），禁止写 DEFAULT/NOT NULL
2. **新增可空列直接加列**（`nullable = true` 或缺省），update 无阻塞
3. **实体移除字段 ≠ 列被删除**：update **从不删除实体已移除的列、也不把现有列改为可空**。移除字段时必须保留实体映射兜底（@Deprecated 字段 + Java 默认值，insert 继续写该列避免违反 NOT NULL），**禁止**只移除映射导致 insert 违反遗留 NOT NULL 列（历史 `liked` 事故模式，见下文「实体字段移除」小节）

**索引演进**：新增/修改实体 `@Index` 后 update 自动建索引（对已存在同名索引幂等）。

**DDL 失败即启动失败（2026-08-05 起配置）**：`spring.jpa.properties.hibernate.hbm2ddl.halt_on_error: true`（基础配置已统一，dev/prod 继承）——Hibernate 默认对 schema 执行错误只打 WARN 并**照常启动**，留下"半迁移 schema + 运行期才炸"的隐患（见下事故）；开启后任何 DDL 失败直接终止 bootstrap。

### 事故根因（2026-08-05：columnDefinition 与 @ColumnDefault 双声明）

**现象**：启动时 `ALTER TABLE qwt_venue_feedbacks ADD COLUMN status ...` 报 `ERROR: multiple default values specified for column "status"`，随后同批索引建失败（`column "status" does not exist`）；应用却**照常启动成功**（Hibernate 仅 WARN），反馈模块在残缺 schema 上运行，首次读写即炸。

**根因链（为什么会有这个错误决策）**：

1. **把 `columnDefinition` 当成了承载约束的常规通道**。`columnDefinition` 的语义是"原始 DDL 片段，原样拼接"，是给方言特有类型（如 `jsonb`）的逃生口；团队却用它写 DEFAULT/NOT NULL，与 JPA 元数据（`nullable`、`@ColumnDefault`、`@Enumerated` 的 check 生成）对同一约束形成**双声明**。Hibernate 组装列 DDL 时把元数据派生的 `default ...` 追加到原文之后 → `DEFAULT 'PENDING' default 'PENDING'` → Postgres 直接拒绝。
2. **约定未经真实 Hibernate 版本验证就固化为 AGENTS.md 规则**。旧规则"`columnDefinition` 携带默认值 + 枚举类列再加 `@ColumnDefault`"把两个互斥通道写成"互补"，从未在 update 的 ADD COLUMN 路径上被执行过：`handled` 列早已存在（update 不 alter 存量列），`Venue.status` 只用了 columnDefinition+初始化器（无 @ColumnDefault）恰好没踩雷——**幸存者偏差**让错误约定显得"已被使用验证过"。`status` 是本约定下第一条真正走 ADD COLUMN 路径的新列，一执行即炸。
3. **无 DDL 失败兜底**。SchemaIntegrityChecker 只校验主键机制，Hibernate 默认吞掉 DDL 错误，二者叠加使"schema 未按实体迁移"完全不可见，直到运行期。

**长期防线（本次已落地）**：① 列默认值唯一声明通道 = `@ColumnDefault`，columnDefinition 禁止携带 DEFAULT/NOT NULL（规则见上）；② `halt_on_error: true` 使 DDL 失败即启动失败；③ 本事故后全库 grep 清理了全部同模式字段（`VenueFeedback.handled`、`Venue.status`、`Venue.sortWeight`）。新增列后若担心，可临时用 `show-sql: true` 启动一次观察生成的 ADD COLUMN 是否单默认值。

### 无法避免手动 SQL 的场景（例外清单）

以下场景 update 无法自动完成，**允许且必须**手动执行，除此之外一律自动演进：

| 场景 | 手段 | 说明 |
|------|------|------|
| 跨库/跨环境逻辑迁移 | `pg_dump -Fc` + `pg_restore` | 完整保留 IDENTITY/序列/主键/NOT NULL/默认值/索引/约束；**禁止 GUI 工具（DataGrip/DBeaver/Supabase 控制台）拖拽复制表结构**——系统性丢失 identity/序列/主键 |
| 主键机制损坏修复 | `db/repair-schema-identity.sql`（幂等） | 回填 NULL id、重建主键、恢复 IDENTITY/序列定位/列默认值；由 SchemaIntegrityChecker fail-fast 兜底发现 |
| 彻底删除遗留列 | 一次性手动 `ALTER TABLE ... DROP COLUMN` | 仅当冗余列影响可维护性时执行；**默认做法是保留实体映射兜底，不删列** |
| 数据回填/批量修正 | 一次性手动 UPDATE | 应用层无法表达的历史数据修正（如 2026-08 的 `migrate-reaction-daily.sql` 回填 reaction_date） |

历史迁移脚本（`migrate-reaction-daily.sql` / `migrate-drop-liked-not-null.sql`）是**旧范式遗留**，仅作历史参考，**不再新增此类脚本**——新演进一律走实体列默认值 + update 自动完成。

### 事故根因（2026-08-04 确立，历史背景）

DB 迁近区时用 DataGrip「全选表 → 拖拽到目标库」方式迁移：索引和唯一约束保留了，但 **IDENTITY（序列）、主键、NOT NULL、列默认值全部丢失**——9 张 `qwt_*` 表的 id 列全部退化为可空 bigint。损坏极具欺骗性：

- Hibernate `ddl-auto: validate` 只校验表/列存在、类型、索引、唯一键，**不校验主键/identity/NOT NULL**（见 `AbstractSchemaValidator`），启动期毫无告警
- 读/更新路径不依赖生成主键，一切正常
- 不回读主键的写入（view upsert）静默积累 id=NULL 脏行
- 首个 IDENTITY 插入才爆炸：编辑场所改状态触发状态变迁日志写入 → `AssertionFailure: null identifier`（Hibernate 经 JDBC `getGeneratedKeys()` 回读主键，pgjdbc 附加 `RETURNING *`，但 id 列无 identity，插入成功而主键为 NULL）

同一套库上**一切新增写入**（新建场所、收藏、动态、上报、评分）全部损坏，状态变更只是第一个撞上的。

### SchemaIntegrityChecker（config/SchemaIntegrityChecker.java）

启动时 Schema 完整性检查，**fail-fast**（`ApplicationRunner` 抛异常拒绝启动）：

- 基于 JPA 元模型自动枚举全部实体表（`@Table` 名 + 主键属性名），**零硬编码表名**；新增实体自动纳入检查
- 单次 `pg_catalog` 目录查询（跨洲往返昂贵，禁止逐表查询）校验每张表：主键存在且与实体主键列一致、主键列 NOT NULL、主键列具备 IDENTITY 或序列默认值
- 仅对 PostgreSQL 生效，其他数据库（测试用 H2）跳过
- 主键机制损坏时一切写入必然失败或产生脏数据——拒绝启动优于静默损坏

任何环境（本地/生产）启动服务即完成一次完整性校验。**手工变更过数据库结构后，必须启动一次服务确认检查通过**。

### 实体字段移除 ≠ 列被删除（2026-08-05 更新为兜底映射模式）

**事故**：`TagInteraction` 在"标签点赞 → Reaction"重构中移除 `liked` 字段，javadoc 声称"liked 列已废弃并移除"，但列从未从库表删除——dev（`ddl-auto: update`）只新增列/约束、**从不删除实体已移除的列、也不把现有列改为可空**；prod（`validate`）不校验列级 NOT NULL。结果：列保持 `NOT NULL` 且无默认值，代码侧 insert 不再提供 `liked` → 任何"首次评分"插入违反 NOT NULL，且被 `score()` 的 `catch (DataIntegrityViolationException)` 误当并发竞态吞掉（事务 rollback-only 后 commit 抛 UnexpectedRollbackException，接口 200 + code=5000 表面成功实为失败），真实根因被掩盖到运行期才爆炸。

**规则（强制，2026-08-05 更新）**：

- 实体移除/弱化字段时，**默认保留字段映射兜底**：@Deprecated 标注 + Java 侧设安全默认值（如 `handled` 遗留列、`avatar_url`/`liked` 先例），保证 insert 继续写该列——**禁止**只移除映射（遗留列 NOT NULL 无默认值时 insert 必炸）
- 兜底字段的 javadoc 必须描述真实库表状态（**禁止把"意图移除"写成"已移除"**——注释必须与库表事实一致）
- 仅当冗余列影响可维护性时，才走「无法避免清单」的手动删列（一次性，不新增迁移脚本文件）
- 现有遗留列：`qwt_tag_interactions.liked`（Java 零引用，NOT NULL 已由 `db/migrate-drop-liked-not-null.sql` 取消）、`qwt_users.avatar_url`（Java 零引用，可空）、`qwt_venue_feedbacks.handled`（状态机引入前遗留，实体 @Deprecated 映射兜底）
- `catch (DataIntegrityViolationException)` **只允许吞唯一键并发竞态（SQLState 23505）**，其余完整性错误（NOT NULL/列约束/外键）必须继续抛出——参考 `TagInteractionService.isUniqueViolation()` 的判定写法；吞异常必须带具体 SQLState 判定，禁止整类静默吞掉

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
- 禁止 `ddl-auto: create` 出现在生产配置（生产与 dev 统一为 `update` 自动演进策略，见「Schema 演进」章节）
- 禁止在 yaml 文件中硬编码密码、Token 等敏感信息
- 禁止在 Entity 上使用 `@Data`（会破坏 JPA equals/hashCode 契约）
- 禁止枚举用 `@Enumerated(EnumType.ORDINAL)`（数据库值依赖顺序，易出错）
- 禁止 import `com.fasterxml.jackson.databind.*` 或 `com.fasterxml.jackson.core.*`（Spring Boot 4.x 使用 Jackson 3.x `tools.jackson.*`）
- 禁止在 pom.xml 显式引入 `com.fasterxml.jackson.core:jackson-databind`（由 Spring Boot BOM 管理）
- 禁止表名/索引名省略 `qwt_` 前缀（多项目共享同一 Supabase 数据库）
- 禁止在 `@Column` 中写 MySQL 特有 `columnDefinition`（如 `tinyint`），应省略让 Hibernate 按方言映射
- 禁止在 `@Column` 的 `columnDefinition` 中携带 DEFAULT / NOT NULL（与 `@ColumnDefault` / `nullable` 双声明同一约束，Hibernate 拼接生成非法 DDL，Postgres 报 "multiple default values specified"）——列默认值一律用 `@ColumnDefault` 单一通道声明（见「Schema 演进」章节）
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
- 禁止降低 `RequestTimingFilter` 的优先级或在其中加入业务逻辑——必须保持 `HIGHEST_PRECEDENCE` 且纯观测，否则计时漏掉前置处理或引入额外延迟（见「请求耗时日志」章节）
- 禁止在拦截器中对每个请求发起无缓存的 DB 查询——跨洲往返 300~700ms × 每请求 = 接口延迟翻倍。拦截器中的高频查找必须使用本地缓存（Caffeine，见 `AuthInterceptor` 用户缓存模式）
- 禁止在同一方法上堆叠多个 `@CacheEvict` 注解——Spring Boot 4.x 中 `@CacheEvict` 不可重复（`@Repeatable`），编译报错。必须用 `@Caching(evict = { ... })` 包装（见「查询性能优化 → @CacheEvict 不可重复」）
- 禁止在 fire-and-forget 端点（如 `POST /venues/{id}/view`）中做冗余的场所存在性检查——由调用方（详情页 `GET /venues/{id}`）已校验，fire-and-forget 端点的 DB 查询是纯延迟负担
- 禁止对有唯一约束的幂等写入用 check-then-act（先 SELECT 存在性再 INSERT + catch 冲突）——一律 `INSERT ... ON CONFLICT DO NOTHING` upsert，单次往返且无并发窗口（见 `VenueViewRepository.upsertView`）
- 禁止跨表的多个单值聚合各发独立查询——收敛为一条标量子查询 mega-query（见 `VenueRepository.countHeatCounters`）；接口延迟 ≈ 串行往返 × 300~500ms，往返数是第一设计约束
- 禁止用 `@CacheEvict` 失效 venueHeat / tagStats——二者是服务内嵌 LoadingCache（非 Spring 托管），必须显式调用属主服务 `invalidate(venueId)`；漏掉任何一条写路径的逐出都会造成统计滞后（见「查询性能优化 → 写路径缓存逐出」矩阵）
- 禁止 `@Cacheable` 省略 `sync = true`——非 sync 的并发冷请求会重复回源（thundering herd），且无法获得单飞语义
- 禁止内嵌 LoadingCache 的 loader 方法挂 `@Transactional`/`@Cacheable` 等 AOP 注解——loader 经 `this::` 引用直接调用，绕开代理静默失效；loader 内的查询各自走隐式只读事务（见 `VenueHeatService.computeHeat`）
- 禁止用 GUI 工具（DataGrip/DBeaver/Supabase 控制台）拖拽或导出-导入方式复制表结构迁移数据库——此类工具按普通列类型重建表，系统性丢失 IDENTITY/序列/主键/NOT NULL，且 Hibernate validate 无法发现（见「Schema 完整性与数据库迁移规范」）；逻辑迁移一律 `pg_dump -Fc` + `pg_restore`
- 禁止绕过或降级 `SchemaIntegrityChecker`（如改为仅告警、加开关跳过）——主键机制损坏时一切写入必然失败或产生 id=NULL 脏数据，fail-fast 是唯一正确语义（见「Schema 完整性与数据库迁移规范」）
- 禁止手工建库/迁库后不启动服务验证——启动即触发 Schema 完整性检查，是手工变更结构后的强制验收步骤
- 禁止 Reaction 字典使用二元对立的点赞/倒赞图标（如 👍👎）——采用具体、中性的正负向 Reaction 共存，避免攻击性评价引发商家纠纷（见「Reaction 快速反馈系统」章节）
- 禁止允许用户自由创建 Reaction 代码——字典由后端 `ReactionCode` 枚举唯一维护，新增/调整条目需过审核安全过滤（参照 `ReportReason` 命名规避敏感词的先例）
- 禁止对 Reaction 做周期性清零（如"每周/每月重置计数"）——采用永久保留原始记录 + 多时间窗口（今日/7天/30天/全部）实时统计的时间衰减方案，见「Reaction 快速反馈系统 → 时效性设计」根因说明
- 禁止 Reaction 的四个时间窗口套用热度模块「统计口径：截至昨日」的排他上界约定——Reaction 是实时众包信号，窗口锚点为真实"此刻"，与热度滚动窗口是两套独立的时间语义

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
| 列表页展示的关联统计按 venueId 循环单独查询 | 收集整页 venueId 后一次 `IN (...)` 批量查询（如 `VenueReactionService.batchGetBadges`） |
| 用 venuefeedback 做实时状态上报（误用异步审核做实时信号） | venuefeedback = 异步管理员审核流程；venuestatusreport = 实时 4h TTL 众包信号。两者共存，不可混用（见「场所状态上报」章节） |
| 用户上报后修改 Venue.status | 用户上报是独立信号层，不改 Venue.status；管理员后续可决定是否据此手动更新（见「独立信号层」章节） |
| 拦截器中对每个请求 `findById` 查库（无缓存） | 用 Caffeine 内嵌缓存（2min TTL），缓存命中 <1ms，见 `AuthInterceptor` 用户缓存模式 |
| 在同一方法上写两个 `@CacheEvict` | Spring Boot 4.x 中 `@CacheEvict` 不可重复，编译报错；用 `@Caching(evict = { ... })` 包装 |
| 收藏列表逐个 `findByIdAndDeletedFalse` 查场所（N+1） | 用 `findByIdInAndDeletedFalse(List<Long>)` 批量查询，1 次往返替代 N 次 |
| fire-and-forget 端点做冗余场所存在性检查 | 由调用方（详情页 GET /venues/{id}）已校验，fire-and-forget 端点不做重复 DB 查询 |
| 有唯一约束的幂等写入先 SELECT 再 INSERT | `INSERT ... ON CONFLICT DO NOTHING` upsert 单次往返（见 `upsertView`），check-then-act 多一次往返且有并发窗口 |
| 跨表聚合一个表一条查询串成长链 | 单值聚合收敛为标量子查询 mega-query（见 `countHeatCounters`），多行形态才独立查询 |
| 用 @CacheEvict 失效 venueHeat / tagStats | 内嵌 LoadingCache 不走 Spring 缓存，显式调用 `venueHeatService.invalidate` / `tagAggregateStatsService.invalidate` |
| 新增写操作后忘记逐出聚合缓存 | 对照「查询性能优化 → 写路径缓存逐出」矩阵补 invalidate，缓存新鲜度主保障是写路径显式逐出 |
| `@Cacheable` 不写 sync / 给内嵌缓存 loader 挂 AOP 注解 | `@Cacheable` 一律 `sync = true`（单飞）；loader 经 this:: 直调绕开代理，不挂事务/缓存注解 |
| 用 DataGrip 等 GUI 工具拖拽复制表做数据库迁移 | `pg_dump -Fc` + `pg_restore` 保留 identity/序列/主键；GUI 拖拽系统性丢失这些属性且 Hibernate validate 查不出来（见「Schema 完整性与数据库迁移规范」） |
| 遇到 `AssertionFailure: null identifier` 去改业务代码 | 根因在数据库：id 列丢失 IDENTITY/主键（多为迁移事故）。执行 `db/repair-schema-identity.sql` 修复，不从代码层绕 |
| 带显式 id 导入数据后不重置序列 | `setval` 到 max(id)，否则下一次插入主键冲突（修复脚本已内置；手工导入必须做） |
| 恢复"标签点赞"功能或用 1-10 打分做实时众包体感 | 已被 Reaction 快速反馈系统替代（`venuereaction` 模块），新增此类需求一律走 Reaction toggle，不复用 taginteraction |
| Reaction 计数做"每周/每月重置清零" | 原始记录永久保留，按今日/7天/30天/全部四个真实时间窗口实时统计（时间衰减方案），见「Reaction 快速反馈系统」 |
| Reaction 时间窗口套用「统计口径：截至昨日」 | Reaction 窗口锚点为真实"此刻"（今天0点/7天前/30天前），与热度滚动窗口是两套独立时间语义 |
| 实体删除字段后不同步迁移脚本处理遗留列（javadoc 写"已移除"但列仍在） | `ddl-auto: update` 不删列/不取消 NOT NULL、`validate` 不校验列级 NOT NULL → 遗留 NOT NULL 列在运行期插入时才爆炸且被 DataIntegrityViolation 兜底误吞。实体删字段必须同步 `db/migrate-*.sql` 迁移，注释写真实状态（见「Schema 完整性与数据库迁移规范 → 实体字段移除 ≠ 列被删除」） |
| `catch (DataIntegrityViolationException)` 整类吞掉当"并发幂等" | 只允许吞唯一键竞态（SQLState 23505，见 `TagInteractionService.isUniqueViolation`）；NOT NULL/列约束/外键违规必须上抛，否则真实根因被静默掩盖成 200 + 业务码（2026-08-05 liked 列事故） |

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
