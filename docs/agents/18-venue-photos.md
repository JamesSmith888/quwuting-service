# 门店照片域（qwt_venue_photos 独立表 + UGC 先审后发）

> 2026-08-20 确立。前端交互/根因链见前端 `docs/agents/19-venue-photos.md`；本文档只讲后端实现细节。
> 设计模式对齐舞伴照片域（`qwt_dancer_photos`，V7）：独立表 + PENDING 先审后发 + sortOrder 上传序 + 管理端逐张审核。

## 背景（为什么弃用 venue.photos JSON 列）

旧 `Venue.photos`（JSON 数组列）只能经"创建/编辑门店"整表提交时全量覆盖（写权限 = 认领人/管理员），普通到店用户（最大照片贡献来源）零通道；又因无逐张审核闸门不敢开放 UGC → 门店相册基本无照片（根因链 R1/R2/R3，见前端文档）。V35 起照片升级为独立资产域，读路径整体切换新表，JSON 列废弃不再写入。

## Schema（V35）

`qwt_venue_photos`：`id / created_at / updated_at / deleted / venue_id / url(500) / status varchar(20) DEFAULT 'PENDING' / created_by / sort_order DEFAULT 0`，索引 `(venue_id)`、`(venue_id,status)`、`(status,created_at)`。

- 与舞伴照片差异：无 `blur_url`（门店照片无收费解锁）；写者开放任意登录用户。
- 存量迁移：`jsonb_array_elements_text(venue.photos) WITH ORDINALITY` 一次性灌入（`status=PUBLIC`、`created_by=COALESCE(claimed_by,0)`、数组序为 sort_order）；仅迁移"数组开头"合法 JSON 兜底脏数据；JSON 列残留保留可回滚。
- 新写路径禁止再写 JSON 列（`createVenue` 的 photos 转存新表直发 PUBLIC；`updateVenue` **忽略 photos**——JSON 列全量覆盖会误删他人 UGC 照片）。

## 权限与状态分派

| 写者 | 上传状态 | 校验 |
|------|---------|------|
| canManage（认领人/管理员） | PUBLIC 直发 | 可信写者；不受频控 |
| 普通登录用户 | PENDING | UGC 频控：同 `user:venue` 60s 窗口 ≤3 次（Caffeine 内存单机近似，同 FavoriteService toggle 频控模式）；单次 ≤9 张（`MAX_PHOTOS_PER_UPLOAD`，与前端 image-upload maxCount 对齐） |

删除：上传者本人（仅自己的 PENDING/REJECTED）/ canManage（任意含 PUBLIC）/ ADMIN。软删。

## 接口与实现要点

- `GET /venues/{id}/photos`：本人视角回显（`listVenuePhotos` → `fetchVenuePhotos`：PUBLIC 全部 + 本人/管理方的 PENDING/REJECTED），编辑表单照片区回显/删除用。
- `POST /venues/{id}/photos`：`addVenuePhotos`——URL 逐个 `ImageContentValidator`（08-12 安全约定：图片 URL 落库字段必须挂载内容级校验）+ `TextSanitizer`；sortOrder = 当前 max +1 追加。
- `POST /venues/{id}/photos/{photoId}/remove`：`removeVenuePhoto`（POST 动作路径符合「只允许 GET 和 POST」）。
- `GET /admin/venues/photos`：`listAdminPhotos`——LEFT JOIN qwt_venues 取门店名（软删回退"门店已删除"）、LEFT JOIN qwt_users 取上传者昵称（存量导入/用户软删回退"未知用户"）；status 可选过滤，上传时间倒序。
- `POST /admin/venues/photos/{photoId}/status`：`updateVenuePhotoStatus`——仅 PENDING 可审、目标状态相同幂等返回；reason 可选仅审计日志；**审核结果不新增站内信**（上传者管理入口可见 REJECTED 后自行删除重传，同舞伴照片规则）。
- **读路径批量注入**：`loadPublicPhotosByVenueIds(venueIds)` 一次 IN 查询返回 `Map<venueId, List<url>>`（sortOrder 升序）；`VenueResponseMapper.toResponse` 五参重载注入（null 回退 JSON 列仅存量兼容）。消费方：`VenueService.listVenues`（整页批量）、`computeVenueDetailPublic`（详情 base）、`createVenue/updateVenue`（单店返回）、`FavoriteService.getFavoriteVenues`（收藏列表批量）。
- **缓存失效**：照片写路径 PUBLIC 变化时 `evictVenueEntityCache(venueId)`（CacheManager 手动 evict——key 依赖查询结果，@CacheEvict 无法表达，同 VenueClaimService 先例）+ `invalidateDetailPublic(venueId)`（详情公共缓存）。

## 约定

- 公开消费（`VenueResponse.photos`）只含 PUBLIC；PENDING/REJECTED 永不对外。
- 上传/审核/删除全部经 VenueService（与缓存失效/权限同域，避免循环依赖——VenueService 注入 venuePhotoRepository + cacheManager）。
