# AGENTS.md — quwuting-service（后端）

> ⚠️ **本文件是主题索引（渐进式披露顶层），不是详细文档库。**
> 详细设计一律按主题拆分在 `docs/agents/` 下，本文件只放项目定位、最小事实与索引表。
> **任何 Agent 修改本文件前，必须先读文末「⚠️ 维护规则」**。

## 项目定位

Spring Boot 4.1 + Java 25 + Spring Data JPA 后端服务，为去舞厅小程序提供舞厅信息查询 REST API（黄页数据展示，无交易/支付逻辑）。连接 Supabase Postgres（外部不稳定环境，韧性为第一优先级）。

## 最小事实（新 Agent 必读）

- **构建/运行**：`./mvnw clean compile`；`./mvnw test -Dspring.profiles.active=dev`（连库并验证 SchemaIntegrityChecker）；`./mvnw clean package -DskipTests`；`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
- **HTTP 语义**：只允许 GET 和 POST（禁 PUT/PATCH/DELETE）；统一 `ApiResponse<T>`——业务错 = 200+code、未登录 = 401、404 不存在、DB 瞬时 = 503+5003、未预期 = 500+5000；兜底异常禁以 200 伪装
- **Schema 演进唯一通道 = Flyway 迁移**（`db/migration/V{n}__*.sql`），`ddl-auto=validate`；新 NOT NULL 列唯一通道 = `@ColumnDefault`；枚举类列禁 CHECK（扩枚举免迁移）
- **连接池韧性（Supabase 不稳定）**：6543 JDBC URL 必含 `prepareThreshold=0&connectTimeout=5&socketTimeout=8&tcpKeepAlive=true`；连接池参数唯一事实源 = `application.yaml`（禁环境 yaml 重复声明）
- **性能第一约束 = 最少 DB 往返**（跨洲 371ms/次）：列表/详情公共数据必须缓存、个人态永不缓存、写路径显式失效——详见 [`29-performance.md`](docs/agents/29-performance.md)
- **舞伴统计**：联系方式解锁只计首次成功获取；每次成功提交邀约均计需求热度。需求明细仅资料管理者/管理员可读，展示资料批量查询且不返回联系方式/openId——详见 [`30-dancer-statistics-integrity.md`](docs/agents/30-dancer-statistics-integrity.md)
- **技术债**：~~生产跑 dev profile~~ 已于 2026-08-30 切 prod + RDS MySQL；**敏感配置一律 gitignored yml（2026-08-31 起弃环境变量）**：本地 application-dev/mysql.yaml、生产服务器 `${APP_DIR}/config/application-prod.yaml`（外部化配置），详见 [`14-deployment-and-schema.md`](docs/agents/14-deployment-and-schema.md)
- 命名：表 `qwt_` 前缀；根包 `org.quwuting.quwutingservice`

## 主题索引（渐进式披露）

> 需要细节时按主题查阅对应文档；**禁止把细节写回本文件**。

| 主题文档 | 内容 | 何时查阅 |
|---------|------|---------|
| [`01-build-and-run.md`](docs/agents/01-build-and-run.md) | 编译/测试/启动命令、开发测试数据（seed-dev.sql） | 构建、跑测试 |
| [`02-package-structure.md`](docs/agents/02-package-structure.md) | 包结构总览（按功能分包、controller/service/mapper/repository 分层） | 找类、新模块落地 |
| [`03-auth-and-user.md`](docs/agents/03-auth-and-user.md) | 登录流程、软鉴权、微信 API 调用规范、角色、用户资料与用户态刷新 | 鉴权/用户相关 |
| [`04-venue-domain.md`](docs/agents/04-venue-domain.md) | 场所数据模型（核心信息/地址/坐标/营业时间/消费信息）、门店认领与管理权限、场所动态、**门店图片同步（高德直链 + 工作台纠错生命周期，2026-08-22）** | venue 域改动 |
| [`05-venue-heat.md`](docs/agents/05-venue-heat.md) | 热度公式与权重、统计口径（实时/截至昨日分家）、各趋势口径、营业稳定性、状态可信度、性能优化、**浏览贡献重构（2026-08-27：来源加权列表0.5/搜索1.5/分享2 + 近7天×2 + ln 压缩，马太效应反馈循环修复）**、**收藏/动态口径收敛（2026-09-01：收藏总数/动态总数存量项退出公式，只计近30天新增——旧双列集合包含重复计 25 分）**、**状态可信度二次修正（2026-09-02：OPEN 分支补时间验证门槛——刚翻正/刚建档门店（本次持续≤7天）即使 0 暂停也只判 MEDIUM，修复"长期停业今日翻正却显示稳定营业"零点莎莎实证；无日志老店兜底 HIGH）** | 热度/统计接口 |
| [`06-listing-and-stats.md`](docs/agents/06-listing-and-stats.md) | 复合评分排序、双查询拆分坑位、标签筛选、城市词表、isHot 热门标记、累计浏览量、**零行为权重守卫（2026-08-27：行为热度=0 门店运营权重不参与排序，HEAT_SCORE 拆 HEAT_BEHAVIOR + CASE 守卫壳）**、**浏览贡献重构（2026-08-27：排序浏览项 = ln(1+来源加权浏览)，VIEW_BEHAVIOR 独立常量三处镜像）**、**关键词检索 v2（2026-09-02：KW_MATCH 六字段 + 门店同步别名 EXISTS、拆词 AND（逐词行集探测交集 → :filterIds 白名单静态 JPQL）、LIKE 字面转义 ESCAPE；RELEVANCE_KEYS 相关度排序（名称前缀>子串>其余，OPEN 组内前置、**停业店照常展示不过滤**，keyword null 恒等退化零变化）；GET /venues/suggest 联想（前缀>中缀>别名；**2026-09-02 晚前端调用链下线，接口保留勿删——未来热门搜索复用**）；VenueListKey 加 kwPrefixPattern、filterIds 路径绕缓存）** | 列表接口 |
| [`07-feedback-and-reporting.md`](docs/agents/07-feedback-and-reporting.md) | 统一用户上报（类型/状态机/防刷/**原子幂等 upsert，2026-08-20**/激励下发/**状态类类型下线与采纳联动兜底，2026-08-20**/**管理端响应补上报者信息 userId+nickname，2026-08-28**）、场所状态上报（公示期/采纳联动/紧急公告/**处置三分级采纳·保留·移除 + 已处理视图，2026-08-28**） | 上报模块 |
| [`08-reaction-and-rating.md`](docs/agents/08-reaction-and-rating.md) | 评分交互（维度/防刷）、Reaction 系统（每日一记模型、**双层字典架构**（2026-08-24：legacy 语义 code + EmojiCatalog 常见表情目录适配（152 项 → 2026-09-03 去噪收敛 134 项）三极性、域内去重、系统文本渲染零图片）、聚合缓存） | 评分/Reaction |
| [`09-dancer-and-points.md`](docs/agents/09-dancer-and-points.md) | 舞伴生态（8 表模型/审核/认可/可见性）、**认可单票换票 + 可配置多选**（2026-08-15：Reaction 风格表情 chip 单票——参与/同票取消/异票原子换票，开关 dancer.recognition.daily.single（V31 默认 true）关闭 = 多选；**崩溃根因修复**：@Modifying 批量删除替代派生删除 + pg_advisory_xact_lock 同键串行化 + afterCommit 缓存失效；RecognizeResponse 扩展 replacedFrom/myTags/tags(四窗口) 绝对快照，详情响应 +myTags；旧 tags 列表兼容；**表情不复用门店反馈字典——2026-08-24 废止**（双域共用 EmojiCatalog 常见表情目录，舞伴仅收 POSITIVE/NEUTRAL、NEGATIVE 不进真人主页；description 补全））、**舞伴收藏**（独立表/幂等接口/收藏列表仅 NORMAL，能力平权）、**舞伴官方认证**（「信息已核验」：V26 审计日志/状态机可回退/编辑触发待复核/撤销必留痕，2026-08-14）、**舞伴统计**（六图趋势/GET stats/V29 浏览埋点/写路径缓存失效，2026-08-14；**2026-08-21 浏览来源新增 VENUE**——门店详情页「同城舞伴」入口进入：`ViewSource` 枚举加 VENUE、趋势 mega-query 加 venue_cnt FILTER、`DancerViewSourceTrendPoint` 加 venue 列（other = 全量减四来源）；门店域自身不产生该来源对其统计无影响；落库链路零改动；**2026-08-26 解锁信息补短视频**——`countDancerUnlockStats` 旧 SQL 缺 DANCER_VIDEO 分支（短视频解锁不入统计），已补视频分支（target_id 经 qwt_dancer_photos kind='VIDEO' 归集）、照片分支同步收窄 kind='PHOTO'；**2026-08-26 解锁记录明细 GET /dancers/{id}/unlocks**——按内容类型返回解锁记录列表（JOIN qwt_users 软删排除 + LEFT JOIN 媒体取 sort_order/duration_seconds + LEFT JOIN 流水取花费 -delta；免费解锁 transaction_id 为 null → 0；解锁时间倒序无分页），`DancerStatsService.unlocks` 实时查询不走统计缓存 + 舞伴存在性/NORMAL 可见性校验（对齐 gifters），`DancerUnlockRecord` DTO（userId/nickname/avatarUrl/targetType/targetLabel/targetDesc/createdAt/cost，targetDesc 后端权威派生：照片 N / 短视频 · m:ss / 联系方式）；**2026-08-29 列表排序 v2 + 统计图「排名热度」**——HOT 主导信号由「近7天认可」换为「近7天联系解锁 ×3」（付费意向主导，认可降平滑项 ×1；根因：免费点赞与成交零相关——懒懒Q 12 票仅 1 次解锁、3 周 53 解锁 0 成交；**排序信号原则**：每个信号须预测用户目标行为，付费意向主导、免费信号平滑）；权重唯一事实源 `DancerHeatWeights`（对齐 VenueHeatWeights 收敛先例）；统计页新增「排名热度」卡（`DancerStatsResponse.heat`，排序口径公开化，formulaText/formulaDetail 后端权威、对齐门店热度页模式）；解锁写路径入列表缓存失效矩阵（`PointsService#invalidateDancerStatsAfterCommit` + invalidateAll）；**2026-08-31 解锁写路径失效矩阵收敛为 `DancerUnlockCacheInvalidator` 单入口**——四条解锁写路径（直连/获批/自动发放/代找替代）统一走 `afterUnlockWrite`（详情族级联 + 列表精失效 + afterCommit）；根因：中转路径曾漏失效列表缓存与直连路径不对称（用户报告「邀约解锁统计图未记录」排查确立），手抄样板漂移收敛为单入口，合约由 DemandRelayServiceTest 锁定）、**城市值一致性契约（2026-08-21 根因修复 V38）**：门店/舞伴城市必须统一标准行政区划名（picker region 输出「南通市」形态）——历史手填「南通」与「南通市」并存导致同城筛选/门店同城舞伴查 0 条；V38 建 `qwt_city_key`（尾部去「市」规范化键，禁 REPLACE 全替换）+ 数据驱动归一存量（映射从 qwt_venues 推导，零硬编码城市名）；`findPublicPage` 城市匹配升级「精确 OR qwt_city_key 相等」、`findPublicCities` 按 qwt_city_key 去重（MAX 优先带市形态，JPQL→nativeQuery）；写路径表单锁死标准形态（dancer-edit city-picker 数据源 /venues/cities），防御层兜底绕过表单的写入、积分系统（账务规则/礼物赠送/合规红线）、**积分解锁公共模块**（门槛/解锁/模糊图；**2026-08-26 联系方式每日首免可配置 V49**——开关 `dancer.contact.daily.free` 默认 false=下线，开启恢复 `hasGatedContactUnlockToday` 首免判定，有门槛一律按门槛扣费，见 16-ops-config）、**创作者收益计划**（激励视频广告/线下结算，2026-08-14） | 舞伴/积分模块 |
| [`10-messaging-and-sharing.md`](docs/agents/10-messaging-and-sharing.md) | 站内信（消息中心）、关注门店营业状态（触发挂点/幂等/**2026-09-01「收藏即关注」耦合：收藏自动关注 + 取消同步取消 + V65 存量回填 + 收藏列表状态角标 statusChanged 批量注入 + read-by-venue 按店批量已读**）、分享追踪 | 通知/分享 |
| [`11-storage.md`](docs/agents/11-storage.md) | 文件存储（前端直传 Supabase、FileCategory、内容校验安全模型） | 上传相关 |
| [`12-api-conventions.md`](docs/agents/12-api-conventions.md) | HTTP API 规范（仅 GET/POST）、统一响应格式、错误码登记表 | 写新接口前 |
| [`13-code-standards.md`](docs/agents/13-code-standards.md) | JPA 实体、多值字段 JSON 列、Repository、DTO、异常处理、请求耗时日志、Jackson 3.x、命名 | 写后端代码前 |
| [`14-deployment-and-schema.md`](docs/agents/14-deployment-and-schema.md) | 配置管理（**敏感值一律 gitignored yml：本地 dev/mysql + 生产外部 config/application-prod.yaml，弃环境变量注入，2026-08-31**）、生产部署、连接池与数据库抖动韧性、Flyway Schema 演进与完整性 | 部署、Schema 变更 |
| [`15-governance.md`](docs/agents/15-governance.md) | 禁止操作、AI 代理常见错误表、验证清单 | 每次修改后、提交前 |
| [`16-ops-config.md`](docs/agents/16-ops-config.md) | 运营配置（feature flag）设施：qwt_ops_config 表、读写接口、管理端入口、键即代码契约 | 新增可配置产品规则时 |
| [`17-group-chats.md`](docs/agents/17-group-chats.md) | 舞友群（V33：微信引流，平台无一键加群 API → 长按识别二维码；scope 三态维度互斥校验；公开分组读 + ADMIN CRUD；qr_code_url 挂 ImageContentValidator；GROUP_QR 存储分类，2026-08-17） | 群聊相关 |
| [`18-venue-photos.md`](docs/agents/18-venue-photos.md) | 门店照片域（V35 `qwt_venue_photos` 独立表；**2026-08-20 深夜收口：仅 ADMIN 上传直发 PUBLIC**——原普通用户 PENDING UGC 通道 + 频控因个人主体无「社交服务」类目被审核驳回而删除；本人视角回显、管理端逐张审核（保留处理存量 PENDING）；读路径批量注入五参重载 + PUBLIC 变化显式缓存失效；updateVenue 忽略 photos 禁全量覆盖） | 门店照片/相册相关 |
| [`28-recruitments.md`](docs/agents/28-recruitments.md) | **门店招工**（2026-08-29，V61 双表 `qwt_recruitments` + `qwt_recruitment_contacts`）：定位=用工信息展示非招聘服务（无投递/报名闭环，个人主体红线）；仅管理员直发；职位受控枚举 + 必挂门店 + 有效期硬过滤 + 风险词发布确认（1010）+ 联系方式免费获取式按需下发幂等留痕（对齐舞伴联系方式纪律）；P0 后端已落地，前端页面待实施 | 动招工相关 |
| [`29-performance.md`](docs/agents/29-performance.md) | **性能优化**（2026-08-30 首页慢根因定位：跨洲 DB 往返 371ms/次 × 列表接口 8~9 次 = 秒级）：缓存分层策略唯一权威——个人态永不进共享缓存 / **用户级缓存（键=userId 短 TTL 30s 不跨用户泄漏，允许）** / 无坐标列表主查询 60s 缓存（`VenueService.venueListCache`，写路径显式失效）/ 角标人数 30s + 最新上报行 30s（只缓存原始行禁缓存相对时间文案）+ 信任权重 60s（`CrowdReportService` 三级缓存）；带坐标查询永不缓存；**2026-08-30 舞伴域：列表缓存精失效（反向索引 dancerId→keys，排序信号写只清该舞伴条目替代全清）+ 收藏列表用户级缓存 30s + getDetail 8 分支 CompletableFuture 并行（专用 4 线程池对齐 Hikari 上限）+ fetchPhotos 照片/视频门槛解锁合并 IN 查询 + 前端 refreshCurrentUser 30s TTL + view 上报本地队列 10s 批量去重串行**；未竟事项=HEAT_SCORE 双算、DB 迁国内、前端分包 | 性能优化/缓存相关 |
| [`30-dancer-statistics-integrity.md`](docs/agents/30-dancer-statistics-integrity.md) | 舞伴解锁统计事实与需求热度下钻：多条实际发放路径经统一失效器收敛；聚合公开、邀约明细受资料管理权限保护且去标识化 | 改解锁/统计/邀约明细时 |
| [`31-resource-access.md`](docs/agents/31-resource-access.md) | 平台全局 ADMIN + 门店/舞伴资源级授权：能力矩阵、有效期、撤销审计、认领迁移与审核安全展示 | 改角色、管理权限、认领或资料维护入口时 |
| [`32-web-auth-and-venue-sync.md`](docs/agents/32-web-auth-and-venue-sync.md) | **Web 管理后台（2026-08-31，独立前端 quwuting-admin-web，独立于小程序生态）**：密码登录（WEB_ADMIN_PASSWORD 环境变量，扫码链路保留待用）+ 门店同步报告（管线上报幂等 upsert、**2026-09-01 快照机制退出：apply 仅状态反转（EXACT/ALIAS 复用 applyBatch，无 snapshotApplied）+ apply-selected 批量 + apply-item 单条（低置信人工放行）+ detail 注入 apply_state.would_reverse + 更新记录 GET reversals（VenueStatusLog changedBy IS NULL）+ 条目匹配依据 match_detail（管线注入，展示「为什么能匹配到」）**；前端决策漏斗 6 视图，「可直接更新」= EXACT/ALIAS 且可反转 + tab 专属批量写库按钮 + 更新记录弹层 + 匹配依据行 + 平台门店对比块（状态=实时批量查询 status-batch，点击看详情）；页面「拉取数据」一键跑管线 = 后端子进程 + 触发者 token 透传 + 状态轮询，替代手动 main.py）+ 手动映射别名（网上门店名→平台门店，管线 --refresh-aliases 拉取）；部署 admin.starseek.online（nginx 静态+反代，弃 CF Tunnel） | 动 web-auth / venue-sync / Web 后台时 |
| [`33-venue-sync-skill.md`](docs/agents/33-venue-sync-skill.md) | **舞讯采集 Skill 数据接口（2026-09-01，WorkBuddy 技能 quwuting-venue-daily-sync 配套）**：对话式门店维护闭环（Agent 采集舞讯 → 比对 → **四类对比表格 + 用户确认**（①可直接更新/②需确认/③平台未维护/④参考）→ 一键录入/状态反转），与 Python 管线互补（管线不建档新店）；新增 `GET /admin/venue-sync/venues/export`（候选门店按量加载：city/status 筛选 + size≤500 + 轻量字段，替代重型 listVenues）+ `POST /admin/venue-sync/venues/batch-create`（批量新增：同城同名归一化幂等 EXISTED、逐条独立事务、statusLog changedBy=null=Agent 来源）；状态更新不新增——Skill 直接复用 POST /admin/venue-daily-openings/batch（DailyOpeningService 权威反转语义）；**数据源匹配字典（2026-09-01）**：skill 侧 `reference/xianbao360-venue-dict.json` 沉淀错别字/异体字/后缀/粘连/已确认包含映射，比对前优先查阅、新案例回写；**批量更新标识（V8）**：状态日志 `qwt_venue_status_logs.change_source` 列，Agent+Skill 落库注入 AGENT_BATCH（管理后台「更新记录」展示「批量更新」标签），管理端人工=ADMIN | 动 Skill 数据接口 / 批量新增门店 / Agent 写库时 |
| [`34-announcements.md`](docs/agents/34-announcements.md) | **全局公告（2026-09-01 设计定稿，管理面在 Web 后台非小程序内）**：双场景统一一套系统（运营公告 MANUAL + 数据更新公告 SYSTEM，差异仅 source）；V7 双表 `qwt_announcements` + `qwt_announcement_reads`（已读回执，未读数 NOT EXISTS 派生）；用户端 4 接口 + 管理端 8 接口（全 GET/POST，`UserContext.requireAdmin()`）；**已拍板四项**：towxml 渲染（**P0：Skyline 兼容性必真机验证**，降级单页 WebView/自研子集）/ 首页公告条 + 我的页双入口 / 数据更新公告自动+手动双通道（venuesync 写库后触发，同日防重，模板进 ops-config）/ 已读回执表；管理后台先做底部导航栏 + 功能菜单（AppLayout：van-tabbar + 更多弹层网格，已落地 2026-09-01；**⚠️ Vant fixed+placeholder 布局约定（2026-09-01 根因修复）：外部 class/scoped attr 落在 placeholder 占位层而非 tabbar 本体，该层禁写 transform/filter（会劫持 fixed 的 containing block 导致钉底+飘出屏），视觉样式一律 :deep() 命中本体，fixed 限宽居中用 left:0+right:0+margin:auto 禁 translateX hack**）；**M1-M5 全部落地（2026-09-01）**：V7 迁移 + announcement 域 + E2E 验证；管理后台 = AppLayout + 列表/编辑页（bytemd 1.22）；小程序端 = 首页公告条 + 我的页入口 + 列表/详情页（towxml 3.3.1 裁剪版 632K，**P0 Skyline 待真机验证**）；**M4 数据更新钩子已接**（batchCreateVenues created>0 / applyBatch reversals>0 → createDataUpdateAnnouncement，**开关 announcement.data_update.enabled 默认 false，需管理后台置 true 才自动发公告**）；门禁全过（后端 test-compile + 启动无循环依赖 / 管理端 build / 小程序 tsc+check:tokens） | 动公告 / 全局通知 / 数据更新公告时 |

---

## ⚠️ 维护规则（强制：渐进式披露）

本文件曾膨胀至 **2200+ 行**（教训），现重构为索引。**所有 Agent（含未来的 AI 代理）更新本文件时必须遵守：**

1. **本文件只承载索引与轻量描述**：项目定位、最小事实速查、主题索引表。任何详细设计、迭代历史、根因分析、代码示例一律写入对应主题文档（`docs/agents/` 下），本文件只保留「链接 + 一行摘要」。
2. **新增/修改细节 → 先定位主题文档**：按索引表找到对应文档，细节写进文档；同步更新索引表摘要（保持一行）。
3. **新主题 → 新建文档并登记**：无现成文档承载的新主题，在 `docs/agents/` 下新建 `NN-主题.md`（序号延续），复制现有文档头部模板（含维护警告），并在索引表登记一行。
4. **文档膨胀 → 拆分子文档**：单个 `docs/agents/` 文档超过 ~300 行时，拆出独立子主题文档，索引表同步。
5. **禁止把历史演进堆进索引**：每轮迭代的「决策过程/踩坑复盘」只允许进主题文档，且尽量压缩为可复用的规则沉淀，不留流水账。
6. **写完后自查**：本文件应保持在 ~100 行以内；索引表每个主题恰好一行；改动前先读对应主题文档，避免重复/冲突。
