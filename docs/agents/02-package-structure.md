# 包结构

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

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

dancer/         ← 舞伴生态体系（独立业务域，2026-08-06 新增，见「舞伴生态体系」章节）
  controller/   ← DancerController（/dancers）、MyDancerController（/users/me）、AdminDancerController（/admin/dancers）
  service/      ← DancerService（认可/标签/可见性/审核状态流转+站内信通知）、DancerAggregateService（内嵌 refresh-ahead 认可统计缓存）
  repository/   ← DancerRepository / DancerVenueRepository / DancerRecognitionRepository / DancerRecognitionTagRepository
  entity/       ← Dancer（qwt_dancers）、DancerVenue（qwt_dancer_venues）、DancerRecognition（qwt_dancer_recognitions）、DancerRecognitionTag（qwt_dancer_recognition_tags）
  dto/
    request/    ← CreateDancerRequest / RecognizeDancerRequest / UpdateDancerStatusRequest（含可选 reason）
    response/   ← DancerSummaryResponse / DancerDetailResponse / DancerTagStat / DancerVenueInfo / DancerRecognitionStats / RecognizeResponse / MyDancerRecognitionResponse / AdminDancerResponse
  enums/        ← DancerStatus（含 REJECTED）/ DancerVenueRelation
  DancerTagCode ← 舞伴标签字典（后台维护，前端镜像 constants/dancer-tags.ts）

message/        ← 站内信（消息中心，2026-08-08 新增，见「站内信（消息中心）」章节）
  controller/   ← MessageController（GET /users/me/messages, /unread-count, POST /{id}/read, /read-all）
  service/      ← MessageService（create 供业务模块调用 / list / unreadCount / markOneRead / markAllRead）
  entity/       ← Message（qwt_messages：userId + type + title + content + relatedType/relatedId + readAt）
  repository/   ← MessageRepository
  dto/
    response/   ← MessageResponse
  enums/        ← MessageType（DANCER_REVIEW / DANCER_STATUS）

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
  enums/        ← FeedbackType（CLOSED_DOWN / SUSPENDED / RESUMED / INACCURATE / MISSING_INFO / PRICE / OTHER）
                  ReportStatus（PENDING / ADOPTED / ADOPTED_NO_REWARD / RESOLVED / DISMISSED 状态机）

venuestatusreport/  ← 场所状态众包上报模块（实时暂停信号，公示期 2 天）
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
  FileCategory       ← 文件分类枚举（VENUE_COVER / VENUE_PHOTO / VENUE_QR / USER_AVATAR / DANCER_PHOTO / DANCER_AVATAR / VENUE_CLAIM_LICENSE）
  UploadTokenResponse ← 凭证 DTO（projectUrl / anonKey / bucket / uploadPath / publicUrl）
  ImageContentValidator ← 图片内容校验器（2026-08-12 恶意文件防线：业务提交时下载 URL 验 magic bytes / 尺寸 / 大小，Caffeine 缓存）
```

每个功能模块内部遵循分层：`controller → service → repository → entity`。  
禁止 controller 直接调用 repository，禁止 entity 依赖任何上层包。  
跨模块复用通过 `mapper/` 组件或 service 层注入实现。

---

