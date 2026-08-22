# 场所域（数据模型 / 门店认领与管理 / 场所动态）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 场所数据模型（venue 模块）

### 核心信息与地址

`qwt_venues` 承载场所基础信息：名称、营业状态（`status`，`VenueStatus` 枚举）、城市/区县（标准行政区划名，与列表筛选共用同一词表精确匹配）、地址、坐标（`longitude`/`latitude`，导航用）、相册（`photos` JSON 数组字符串列）、简介、联系方式、标签（`tags` JSON 数组字符串列，仅存管理员自定义标签，`VenueDefaultsConfig` 合并系统默认标签）。

### 坐标采集与批量补全（2026-08-11 新增）

**坐标系约定**：全链路 gcj02（火星坐标）——`wx.chooseLocation` 采集、`wx.openLocation` 展示、后端地理编码回写均为 gcj02，混用 wgs84/bd09 会产生数百米偏移（前端见 `utils/geo.ts` 注释）。腾讯位置服务/高德地理编码输出即 gcj02；百度输出 bd09 需转换，故服务商限定腾讯/高德。

**采集通道**：
1. 新建/编辑门店：前端 `wx.chooseLocation` 人工地图选点（主通道）；
2. 存量批量补齐：管理端「一键补齐」——`POST /admin/venues/geocode/backfill`（仅 ADMIN），内部串行调用腾讯位置服务地理编码（`GeocodeService`，个人版约 5QPS → 逐条 250ms 限速），幂等（只处理 latitude/longitude 为空的场所，可反复重试），失败项计入报告不影响其他项。待补数量入口：`GET /admin/venues/geocode/missing-count`。
3. 一次性运维脚本：`scripts/backfill_geocode.py`（读库→调 API→回写，`--dry-run` 预检 / `--city` 单城市 / `--limit` 限量，环境变量 `QWT_DB_URL/QWT_DB_USER/QWT_DB_PASSWORD/QQMAP_KEY`）。

**强制约定**：
- 地理编码 key 只放后端配置（`app.geocode.key`，生产经 `QQMAP_KEY` 环境变量注入），**禁止落前端/入库/进 git**（合规要求）；
- 地址拼接优先用 `address` 字段（可能已含省市区，避免 city+district+address 重复拼接）；
- 回写前坐标粗校验中国境内区间（lat 18~54 / lng 73~135），越界拒绝写入并计入失败报告；
- 批量回写**禁持跨批大事务**：`GeocodeService.backfillAll()` 非事务，逐条 `save` 各自提交（连接池仅 5 连接 + Supabase 抖动为已知外部条件，见「连接池与数据库抖动韧性」）；

### 门店图片同步（2026-08-21 新增，高德 place/text）

**背景**：存量门店缺主图（`image_url` 为空，历史占位图 `picsum.photos` 已清空）。管理端「一键同步门店图片」调用高德 Web 服务 `place/text` 关键词搜索（**必须 `extensions=all` 才返回 `photos[]`**，2026-08-21 实测 base 不含），取图直接写入 `image_url`。

**图片策略（用户决策）**：`image_url` 直接存高德官方图床 URL（`store.is.autonavi.com`），**不下载到 Supabase Storage**（省存储 + 高德 CDN 直出）。候选排序：① 官方图床优先；② 无官方图回退 `photos[]` 第一条（可能为 `aos-comment.amap.com` 用户评论图——含 EXIF 隐私风险，有图兜底，管理端人工可判断）；③ photos 为空 → 失败「高德未收录照片」。

**多图落点（2026-08-22 修复）**：`image_url` 是单值主图字段（varchar 500），高德 `photos[]` 多图写入独立相册表 `qwt_venue_photos`（status=PUBLIC、createdBy=0 存量导入、**重置式导入**——每店先物理删除 created_by=0 旧记录再插入最新匹配结果，错配图随重跑自愈，见 `VenuePhotoRepository#deleteImportedByVenue`；复用 `VenueService.syncGalleryPhotos` 自带缓存失效）——V35 起 `venue.photos` JSON 列废弃，详情/列表轮播读 `qwt_venue_photos`（PUBLIC），同步后详情页自动多图轮播。**幂等口径 = 缺主图 OR 无公开相册**（`findMissingImages` NOT EXISTS 子查询），已有主图的存量门店重跑本轮时若主图仍在匹配结果中则保留，否则重写（错配自愈）。

**名称匹配防错配（2026-08-22 修复「梦幻酒馆」混入「梦幻网咖」图）**：全量同步（名称模式）**只取目标 POI 的照片**，不再收集高德返回全部候选——`searchPoi` 遍历 pois 按名称归一化打分（`normalize` 全角转半角 + 去空白；`nameScore`：完全相等 100、互为子串且短串 ≥2 字按长度占比（下限 60）、其余 0），最高分 ≥ `NAME_MATCH_THRESHOLD`(60) 才采纳；低于阈值记失败「名称未匹配（最近候选：xx）」，管理员可补精确地址重试。**重试 = 地址模式**（overwrite=true）：用户已核实地址，跳过名称校验直接取高德结果第一个 POI。真实案例：搜索「梦幻酒馆」返回 34 个相似名 POI，仅「梦幻酒馆(暂停营业)」得分 60 被采纳，其余（梦幻网咖/梦幻宾馆/梦幻电竞馆…）全部 0 分拒绝。

**接口**（仅 ADMIN，`AdminVenuePhotoSyncController` / `AmapVenuePhotoSyncService`）：
- `GET /admin/venues/photo-sync/missing-count` — 缺图数量（`findMissingImages` 口径：deleted=false 且 imageUrl NULL/空串 OR 无公开相册）
- `POST /admin/venues/photo-sync/run` — **异步**触发全量同步，返回 `{started:bool}`（false = 已有同步进行中，防并发重复触发）
- `GET /admin/venues/photo-sync/progress` — 实时进度轮询（`SyncProgress`：running/total/processed/updated/failed/skipped/currentName/items 逐条结果）
- `POST /admin/venues/photo-sync/retry` — 单店重匹配（body `{venueId, address?}`，同步返回单条 `SyncItem`；**address 可选**：空 = 名称模式强制重匹配（成功项复核后重取），非空 = 地址模式（完整地址检索取第一个 POI，覆盖「店名与高德登记不一致/搜不到」场景））
- `POST /admin/venues/photo-sync/clear` — 清除门店图片（body `{venueId}`；主图 image_url 置空 + 物理删高德导入相册 + 缓存失效，回到无图态可重新同步——人工判定错配后回退）
- `GET /admin/venues/photo-sync/list` — 门店图片状态分页（`hasImage` 主图有无 / `city` / `keyword` 名称模糊筛选；数据源 = **DB 现状**（qwt_venues + qwt_venue_photos 子查询聚合，`VenueRepository#findPhotoStatusPage`）非同步内存快照——服务重启不丢、可筛选分页，兼作成功项纠错入口）

**工作台与纠错生命周期（2026-08-22 拆独立页，遵循「列表+明细+写操作 → 独立页」架构约定）**：
- 管理端入口 = `pages/admin-photo-sync`（上报管理页仅留一行入口条）；页面 = 同步控制（缺图数/一键同步/进度轮询/完成 toast 含近似匹配复核提示）+ 状态筛选（全部/有图/无图）+ 城市/名称筛选 + 分页列表 + 详情弹层（主图+相册预览 / 重新匹配（可带地址）/ 清除图片）；
- **成功 ≠ 100% 正确**：`SyncItem` 携带 matchedName/confidence（100=精确，60~99=近似需复核）/poiId/city——近似匹配置信度驱动人工复核；有图门店（成功项）同样有纠错入口（详情弹层重匹配/清除）。

**强制约定**：
- 高德 key 只放后端配置（`app.amap.key`，生产经 `AMAP_KEY` 环境变量注入），禁止落前端（与 geocode 同合规策略）；
- 异步执行：单线程 `ExecutorService` + `AtomicBoolean` running 防并发 + `AtomicReference<SyncProgress>` 内存进度（**单实例部署前提**，systemd 单进程可靠）；前端每 1.2s 轮询 progress，页面隐藏停轮询（`pageLifetimes.hide`）；
- 幂等：只处理缺图门店，可反复触发补扫失败项；单店失败不影响其他项；
- 限速：高德个人版 QPS≈3 → 逐条 `Thread.sleep(400ms)`；

### 营业时间（时段列表，2026-08-08 由固定列改造）

**数据形状**：`business_hours`（`varchar(1000)`）JSON 数组字符串列，与 tickets/partnerFees 同模式：

```json
[{"name":"午场","open":"13:30","close":"17:30"},{"name":"晚场","open":"18:30","close":"01:00"}]
```

**根因（为什么改造）**：旧建模用 4 个固定列（`afternoon_open/afternoon_close/evening_open/evening_close`）表达营业时间，把"1 个舞厅 → N 个场次"的业务维度硬编码成 2 个固定场次——schema 跟随表单 UI 形状（下午场/晚场两行）反推而非领域模型；任何新场次（早场/午茶场/深夜场）都要改表结构，时段名被烧进列名无法自定义，LocalTime 单列也没有跨天结束（18:30-01:00）的显式契约。同一实体里 tickets/partnerFees 已确立"变长结构化列表 = JSON 数组字符串列 + 强类型 DTO"模式（无独立查询需求时用 JSON 列，需要独立查询/排序/元数据再升级关联表，见「图片上传」章节同款约定），营业时间属同类数据却走了固定列，属设计不一致。

**契约**：
1. 条目 = `BusinessHoursEntry` record（`name` 可空、`open`/`close` 必填，`@Valid` 级联校验；`@JsonFormat(pattern="HH:mm")` 统一序列化格式，JSON 列与 API 输出均为 `"13:30"` 无秒）；
2. **跨天语义**：`close < open` 表示结束于次日凌晨（如晚场 18:30-01:00），原样存取、展示端原样呈现，不引入 endNextDay 布尔（行业通行约定，数据自解释）；
3. 最多 10 条（`@Size(max=10)`，与 tickets/partnerFees 对齐）；
4. 读取端反序列化失败/空列返回空列表（`VenueResponseMapper` 统一 `deserializeList`），不做显性报错；
5. **展示派生在前端（2026-08-09 确立，后端零改动）**：徽标「未到营业时间」由前端 `utils/venueStatus.ts` 按 `status × 当前时刻 × businessHours` 派生（OPEN 且当前不在任何时段时展示派生态 NOT_OPEN_YET）——`statusDisplay` 恒为存储态展示名（分享标题等稳定事实消费方），后端禁止把时间判定烧进 `statusDisplay` 或新增 DB 枚举（会破坏筛选/审计语义且引入服务器时区假设），详见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「营业状态展示派生」。

**迁移**：`V5__venue_business_hours.sql`——加可空新列 → 存量回填（非空时段按「下午场/晚场」命名组装，顺序与旧展示一致；双场皆空保持 NULL）→ 删 4 旧列 → DO 块防御性校验（残缺时段 WARNING）。已有库 baseline 跳过 V1、空库 V1+V5 顺序执行，两条路径终态一致。

### 消费信息（tickets / partnerFees）

门票规则（`tickets`，`varchar(2000)`）与舞伴费用阶梯（`partnerFees`，`varchar(1000)`）均为 JSON 数组字符串列，DTO 序列化/反序列化（`TicketEntry`/`PartnerFeeEntry`）。舞厅无"人均消费"概念，门票形态多样（固定票/免票/时段免票）用规则列表表达；舞伴计费存在按时长阶梯（5分钟30元）与按连曲（3曲30元）两种模式，`unit` 枚举扩展即支持新计费形态。Service 层 `validateTickets` 校验 FIXED 类型必须带票价（注解无法表达条件必填）。

---


---
## 门店认领与管理权限

### 数据模型

`Venue.claimedBy`（`Long`，可空）：认领人用户 ID，引用 `qwt_users.id`，`null` 表示未被认领。认领后该用户获得门店管理权（发布动态、编辑信息等），与平台管理员共享管理入口可见性。

### 认领申请流程（2026-08-11 新增，venueclaim 模块）

认领 = 门店工作人员申请成为管理方，**平台管理员审核通过后置 `Venue.claimedBy`**——
不直接开放自助认领（审核制防止冒领）：

- 申请表 `qwt_venue_claims`：venue_id / user_id（必填，认领必须登录）/ 申请材料
  （real_name 必填、contact_phone 必填、contact_wechat 选填、license_urls JSON 数组
  选填、note 选填）/ status（PENDING / APPROVED / REJECTED / WITHDRAWN）/ handled_by /
  handled_at / handle_note
- **状态机**：PENDING → APPROVED（置 claimedBy，申请人获得管理权）/ REJECTED（可再申请）；
  PENDING → WITHDRAWN（申请人撤回）。终态固定
- **防重复（A1：只能一人认领，先到先得）**：V12 部分唯一索引
  `(user_id, venue_id) WHERE status='PENDING'`（同 V2/V8 上报去重模式）+ 提交时应用层
  catch 23505 幂等返回 + 审核通过时**再次**校验门店未被认领（并发竞态兜底）
- **缓存失效（关键）**：审核通过置 `claimed_by` 后必须失效 venue 实体缓存
  （`CACHE_VENUE`，key = venueId，显式 `CacheManager.evict`——approveClaim 的 key 依赖
  事务内查询结果，无法用 `@CacheEvict` SpEL 表达）。否则 60s TTL 内详情接口读到旧
  claimedBy，认领人 `canManage` 仍为 false。**同时必须失效详情公共部分缓存**
  （`VenueService.invalidateDetailPublic(venueId)`，2026-08-13 新增）——claimed 快照
  属于公共部分，不失效则「认领舞厅」菜单项禁用态滞后最长 30s（refresh-ahead 周期）
- **隐私（D1）**：申请材料仅存工单表，不写 `qwt_users`；用户侧响应不暴露材料，
  仅管理端响应完整返回
- 接口：`POST /venues/{venueId}/claims`（提交）、`GET /venues/claims/mine`（我的认领）、
  `POST /venues/claims/{claimId}/withdraw`（撤回）、`GET /admin/venue-claims`（管理端列表）、
  `POST /admin/venue-claims/{id}/approve` / `{id}/reject`（审核）

### 权限判定规则（canManage）

详情接口 `GET /venues/{id}` 返回 `VenueDetailResponse(venue, canManage, postCount, hasMyStatusReport, statusUpdatedAt, claimed, myClaimStatus)`，其中：

- `canManage` 由后端基于软鉴权上下文计算：
  1. 平台管理员（`UserRole.ADMIN`）→ 对所有门店为 `true`
  2. 门店认领人（`claimedBy` 等于当前用户 ID）→ 对该门店为 `true`
  3. 匿名用户 / 其他用户 → 恒为 `false`
- `claimed`（2026-08-11 新增）：门店是否已被认领（`claimedBy` 非空），全局归属事实，
  驱动前端「认领舞厅」菜单项禁用态
- `myClaimStatus`（2026-08-11 新增）：当前用户对该门店的认领申请状态（未登录恒 null），
  驱动「认领舞厅」菜单项"审核中"禁用态

`canManage` 仅驱动前端管理入口的**展示**，安全边界在后端各写操作接口的角色校验。

### 管理写操作权限校验（requireManageOrAdmin）

所有管理写接口（场所更新、动态发布等）统一调用 `UserContext.requireManageOrAdmin(venue.getClaimedBy())`：先 `requireAuth()` 确保已登录，再判定 ADMIN 角色或 claimedBy 匹配，否则抛 1003。此方法是管理写操作的标准权限入口，新增管理接口时必须使用。

### 场所更新接口

`POST /venues/{id}/update`（管理员或认领人）：全量覆盖可编辑字段，请求体复用 `CreateVenueRequest`（字段相同）。`claimedBy` 不在此接口变更（认领流程另行约定）。`status` / `sortWeight` 为 null 时保留原值不覆盖。

### 详情接口与列表接口的分工

- `GET /venues`（列表）→ `Page<VenueResponse>`：不含权限与统计字段，保持轻量
- `GET /venues/{id}`（详情）→ `VenueDetailResponse`：组合 `VenueResponse` + `canManage` + `postCount` + `hasMyStatusReport` + `statusUpdatedAt`（营业状态字段最近一次变更时间，取自 `qwt_venue_status_logs` 最新一条 `createdAt`——`VenueStatusLogRepository.findLatestStatusChangeTime`；语义区别于 `VenueResponse.updatedAt`，后者任意字段编辑都刷新）

record 不支持继承，组合结构（`VenueDetailResponse(VenueResponse venue, ...)`）是详情扩展的标准模式，后续新增详情专属字段一律追加到此 record。

**详情公共部分缓存（2026-08-13，最少往返约束落地）**：详情接口的「公共部分」
（`VenueResponse base` + `statusUpdatedAt` + `claimed`）与请求用户无关、30s 内不变，
由 `VenueService` 内嵌 Caffeine `LoadingCache` 缓存（`venueDetailPublicCache`，
refresh-ahead 30s / 硬过期 10min / 单飞，与 venueHeat / tagStats 同族语义）：
- **语义边界**：用户相关字段（`canManage` 内存计算零查询、`hasMyStatusReport` 与
  `postCount` 经 `findDetailStats` 合并单查询、`myClaimStatus` 仅登录时查）**永远实时，
  禁止收进公共缓存**——公共部分只缓存"任何人视角一致"的全局事实
- **往返数**：缓存命中 + 匿名 = 1 次 DB 往返（detailStats）；登录命中 = 2 次
  （detailStats + claim）；冷启动最多 4~5 次，回源后其余请求单飞共享
- **写路径失效（必须同步）**：场所编辑（`updateVenue`）、采纳暂停/恢复
  （`markSuspendedByReport` / `reopenByReport`）、认领审批（`VenueClaimService.approve`）
  都必须显式调用 `invalidateDetailPublic(venueId)`——内嵌 LoadingCache 不走
  Spring CacheManager，`@CacheEvict` 无法表达（与 `venueHeatService.invalidate` 同模式）
- **base.topReactions 滞后容忍**：base 内嵌默认窗口徽标快照，Reaction toggle 写路径只
  失效聚合缓存、不失效公共缓存——30s 内徽标短暂滞后可接受（详情页 Reaction UI 主流程
  走 `/reactions/stats` 独立接口，base.topReactions 仅承载列表快照/兜底展示）

---


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

