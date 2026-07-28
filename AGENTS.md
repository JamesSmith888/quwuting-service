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

taginteraction/ ← 标签交互模块（点赞 + 维度评分）
  controller/   ← TagInteractionController（GET /venues/{id}/tags/stats, POST .../like, POST .../score）
  service/      ← TagInteractionService（toggle 点赞、upsert 评分、聚合统计、防刷冷却）
  entity/       ← TagInteraction 实体（qwt_tag_interactions）
  repository/   ← TagInteractionRepository（含 GROUP BY 聚合查询）
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

### 营业稳定性

- "暂停营业次数" = 近 30 天内 `toStatus = SUSPENDED` 的状态变迁记录数
- "当前状态持续天数" = 最近一条状态日志的 `createdAt` 距今天数
- `SUSPENDED`（暂停营业）语义为被迫关门（警察检查等），与 `CLOSED`（休息中，正常未到营业时间）不同

### 查询性能优化（条件聚合 + 本地缓存）

热度接口和标签统计接口的 DB 往返次数已通过**条件聚合**大幅压缩：

- `GET /venues/{id}/heat`：原 14 次串行 DB 往返 → 7 次（浏览 PV+UV 合并、收藏 total+30d 合并、动态 total+30d 合并、评价 rating+like+raters 合并、状态暂停+最新时间合并）
- `GET /venues/{venueId}/tags/stats`：原 7 次 → 4 次（评分全量+30d+7d 三窗口合并为一条 `AVG(CASE WHEN...)` 查询、用户点赞+评分合并为一条）

根因：服务器在阿里云 ECS，数据库在 Supabase（AWS ap-south-1），单次 DB 往返约 100-200ms，查询次数 × 延迟 = 接口总延迟。压缩查询次数是最直接有效的优化手段。

**本地缓存（Caffeine，60s TTL）**：`VenueHeatService.getHeat` 和 `TagInteractionService.getTagStats` 加了 `@Cacheable`。热度/标签统计是聚合数据，60 秒内的精度偏差对用户无感知，但可避免同一场所短时间内的重复计算（多用户同时浏览、同一用户切换 Tab 等场景）。缓存配置在 `config/CacheConfig`，maxSize=500，TTL=60s。

**新增查询时的约定**：当一个 Service 方法需要同一张表的多个 COUNT/aggregation 时，优先用 `SUM(CASE WHEN condition THEN 1 ELSE 0 END)` 合并为单条 SQL，不拆多次往返。JPQL 不支持 `COUNT(DISTINCT CASE WHEN...)` 时，使用 `@Query(nativeQuery = true)` 并注明原因。

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
| 1006 | 操作过于频繁（评分防刷冷却期内） |
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
- **JVM 必须配置内存上限**。通过 `JAVA_TOOL_OPTIONS`（环境变量文件）传入 `-Xmx` / `-XX:MaxMetaspaceSize` / `-XX:+ExitOnOutOfMemoryError`，禁止使用无约束的默认值。
- **必须通过 systemd 管理进程**（自动重启、资源硬限制、日志归集）。systemd unit 由 `deploy/deploy.sh` 自动探测 JAVA_HOME 后内联生成（避免硬编码 `/usr/bin/java` 导致 status=203/EXEC）。

**部署流程（一键脚本）**：

```bash
# 首次部署（服务器上执行）
# 1. 确保仓库已 clone 到 /root/quwuting-service
git clone https://github.com/JamesSmith888/quwuting-service.git /root/quwuting-service

# 2. 复制环境变量范本并填入真实凭据
sudo cp deploy/quwuting-service.env.example /etc/quwuting-service.env
sudo chmod 600 /etc/quwuting-service.env
sudo vi /etc/quwuting-service.env   # 填入 DB/JWT/Supabase 真实值

# 3. 一键部署（打包 + 创建用户 + 写 systemd unit + 启动）
sudo bash deploy/deploy.sh

# 后续升级（服务器上执行）
git pull
sudo bash deploy/deploy.sh --no-user --no-unit   # 仅重新打包+重启
```

脚本支持 `--no-user`（跳过用户创建）、`--no-unit`（跳过 unit 重写）、`--no-package`（跳过 Maven 打包）。

**必需环境变量**（存放于 `/etc/quwuting-service.env`，任一缺失则启动即失败）：

| 变量 | 说明 |
|------|------|
| `JAVA_TOOL_OPTIONS` | JVM 参数（env.example 已预设合理值：`-Xmx512m -Xms256m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError`） |
| `DB_URL` | JDBC 连接串（6543 端口必须含 `prepareThreshold=0`） |
| `DB_USERNAME` | 数据库用户名 |
| `DB_PASSWORD` | 数据库密码 |
| `WECHAT_SECRET` | 微信小程序 AppSecret |
| `JWT_SECRET` | JWT HS256 签名密钥（≥ 32 字符） |
| `SUPABASE_PROJECT_URL` | Supabase Storage 项目 URL |
| `SUPABASE_ANON_KEY` | Supabase Storage 公开密钥 |

可选：`SUPABASE_STORAGE_BUCKET`（默认 `qwt-public`）。

**deploy.sh 关键机制说明**：

| 机制 | 实现 | 作用 |
|------|------|------|
| JAVA_HOME 自动探测 | `readlink -f $(which java)` | 避免硬编码 `/usr/bin/java`（tarball JDK 无此路径，导致 status=203/EXEC） |
| 专用系统用户 | `appuser`（nologin shell） | 进程隔离，最小权限运行 |
| `Restart=always` + `RestartSec=10s` | systemd 守护 | OOM Kill / 未捕获异常后 10s 自愈 |
| `StartLimitBurst=5` / `StartLimitIntervalSec=120` | 2 分钟内最多重启 5 次 | 防止配置错误导致无限重启循环 |
| `MemoryMax=950M` / `MemoryHigh=800M` | cgroup 硬/软限制 | JVM 逃逸时兜底（仍 < 物理 2GB 一半），OS 不被拖死 |
| `-XX:+ExitOnOutOfMemoryError` | JAVA_TOOL_OPTIONS | JVM 内 OOM 立刻退出，让 systemd 拉起而非僵死 |
| `-XX:+HeapDumpOnOutOfMemoryError` | JAVA_TOOL_OPTIONS | 留 hprof 到 `/var/log/quwuting-service/`，事后分析 |

**HikariCP 连接池**（`application-prod.yaml`）：maximumPoolSize=5, minimumIdle=2。低流量小程序 + 高延迟 DB（~150ms/往返）场景下 5 连接足够，减少内存占用。leak-detection-threshold=30s 用于排查连接泄漏。

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
- 禁止生产 `java -jar` 启动时省略 JVM 内存参数（必须通过 env 文件的 `JAVA_TOOL_OPTIONS` 至少含 `-Xmx`，由 `deploy/deploy.sh` 统一管理）

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
| JPQL 数学函数传可空坐标参数（`radians(:latitude)` + null） | PG 将 null 参数推断为 bytea 直接报错；拆成带坐标 / 无坐标两个查询，Service 分流，坐标形参用原生 `double` |
| 城市筛选用 LIKE 模糊匹配"兼容"非标准名 | 精确匹配 + 写入端统一 region picker 标准名；脏数据走一次性清洗 SQL，不在查询端容错 |

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
