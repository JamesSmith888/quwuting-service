# 29 · 性能优化（跨洲 DB 往返是第一约束）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

## 根因（2026-08-30 实测定位）

**架构底座**：ECS（阿里云 114.55.0.14）→ Supabase PostgreSQL（`aws-0-ap-northeast-1` 东京 pooler 6543），**单次 DB 往返实测 371ms**（`curl` 连接 258ms + 传输）。这是所有接口的物理成本，不可消除——性能优化的**第一约束 = 最少 DB 往返**。

**实测基线**（服务器本机直连 8080，不含外网链路）：

| 接口 | 耗时 | 往返数 |
|------|------|--------|
| GET /venues?size=20（默认页） | 1077ms | 8~9 次 |
| GET /venues?size=5（带坐标重拉） | 1283ms | 8~9 次 |
| GET /venues/cities | 321ms | 1 次（无缓存） |
| GET /ops-config | 221ms | 1 次（无缓存） |

**列表接口 8~9 次往返构成**：主查询（HEAT_SCORE 全表每行 6+ 标量子查询 ×2 处出现）+ 分页 count + batchGetBadges + 个人参与态（仅登录）+ 浏览量 + 照片 + 角标人数 + 最新上报行（含信任权重 7 表聚合）。

**根因定性**：不是 SQL 慢（库内执行毫秒级），而是**把「低频变化的公共数据」与「个性化数据」全部塞进每次请求的串行跨洲往返**。公共部分本可缓存复用，却被当作个性化数据每次重算。

## 缓存分层策略（唯一权威，新增缓存前必读）

按「数据是否与请求用户相关」与「变化频率」二分：

| 数据形态 | 是否可缓存 | 实现 |
|---------|-----------|------|
| 无坐标列表主查询结果（全国/城市公共视图） | ✅ 可缓存（纯公共） | `VenueService.venueListCache`，TTL 60s，写路径显式失效 |
| 带坐标列表查询（结果受 radiusKm 半径筛选约束；2026-09-01 起排序不含邻近加成） | ❌ 永不缓存 | 结果集与请求者位置相关（不同位置 300km 半径内的门店集合不同），缓存共享语义不成立 |
| Reaction 聚合计数（徽标 count） | ✅ 公共可缓存（既有） | VenueReactionService 聚合缓存 |
| Reaction 个人参与态（reactedByMe） | ❌ 永不缓存 | 个人状态永远实时查询（跨用户泄漏红线） |
| 门店热度角标「N人报过」人数 | ✅ 公共低频 | `CrowdReportService.badgeCountsCache`，TTL 30s |
| 门店热度「最新上报」行 | ✅ 公共低频 | `latestReportsCache`，TTL 30s，**只缓存 userId+createdAt，文案渲染时实时算相对时间** |
| 上报者信任权重（trustWeights） | ✅ 用户历史行为事实 | `trustWeightsCache`，TTL 60s，批量回源含零贡献用户默认 1.0 |
| 详情接口公共部分 | ✅ 公共低频 | `venueDetailPublicCache`，refresh-ahead 30s + 硬过期 10min |

**铁律**：
1. **个人态永不缓存**（reactedByMe / canManage / myClaimStatus）——缓存粒度落在实体/公共值对象，个人态字段在缓存外实时组装。
2. **写路径显式失效**：内嵌 Caffeine 不走 Spring CacheManager，写方法显式调用 `invalidateXxx`（与 `invalidateDetailPublic` 同模式）；热度累积（reaction/favorite/view）变化靠 TTL 自然过期兜底，不穷举失效。
3. **相对时间文案禁缓存渲染结果**（「N 分钟前」缓存期会失真）——缓存原始行，渲染时实时计算。
4. **缓存键 = 影响结果的全部参数**（不可变 record 作键）；配置值（positiveCodes/pointsWeight）不进键；全局集合（hotIds 5min 缓存）由更短 TTL 自然兜底。

## 列表缓存（VenueService.venueListCache）

- 键 `VenueListKey(sortMode, city, district, status, keywordPattern, tagPattern, hotOnly, page, size)`；
- **仅 hasCoords=false 分支走缓存**（`dispatchListQuery` 分流）；带坐标分支恒实时；
- 缓存粒度 `Page<Venue>` 实体（纯公共）；badges/浏览量/照片/角标仍在缓存外批量实时组装（badges 内含个人态契约）；
- LoadingCache 单飞：热参数组合（默认全国推荐）多用户共享同一份结果；
- TTL 60s + 9 个写路径统一 `invalidateVenueListCache()`（create/update/照片增删审/状态变更/恢复）。

## 角标与信任权重缓存（CrowdReportService）

- `badgeCountsCache`（venueId→人数，30s）：badgeTextsByVenue 逐店命中 + miss 批量回源；
- `latestReportsCache`（venueId→LatestReport(userId, createdAt)，30s）：latestTextsByVenue 同模式，文案渲染时实时 `ageTextFor`；
- `trustWeightsCache`（userId→权重，60s）：trustWeights 先查缓存、miss 一次 `aggregatesFor` 批量补齐并回填（**含零贡献用户默认 1.0，防"查了但无记录"反复 miss**）；
- 上报写路径 `submit` 后 `invalidateVenueCrowdCaches(venueId)`（单店失效；权重与单店上报无关不失效）。

## 辅助接口缓存（P1-2，2026-08-30）

首页冷启动必拉的轻量接口同样无缓存（每次进首页重查跨洲往返），补缓存后首页并发请求全部命中：

- **cities（GET /venues/cities，实测 321ms）**：`CacheConfig.CACHE_CITY_STATS` 5min TTL（maxSize 1 全局集合）——城市列表仅门店新增/编辑才变化；`VenueService.listCityStats` 加 `@Cacheable(sync=true)`；createVenue/updateVenue 注解 `allEntries` 逐出。
- **ops-config（GET /ops-config，实测 221ms）**：`OpsConfigService.allValuesCache` 60s TTL 单飞（key="all" 单值集合）——配置低频变化；`setValue` 写路径同时失效单键缓存与全量缓存，管理端改动即时生效。

**长期方案（系统性防复发）**：任何「全局单值/低频集合」读模型（首页冷启动必拉）一律挂短 TTL 缓存 + 写路径逐出，禁止裸查库进首页请求链。

## 实测效果（优化后）

列表接口缓存命中时：主查询+count+角标×2+权重 全命中 → 剩 badges/浏览量/照片 3~4 次批量往返（~400~600ms 级）。**注意**：匿名用户 badges 仅 1 次往返（无个人态查询），登录用户 +1。

## 舞伴域性能优化（2026-08-30，列表/详情进入性能）

### 根因（三层叠加，非偶发抖动）

生产慢日志坐实：近 12h 列表主接口 19 次、详情 60+ 次超 1s（1.1~2.8s）。

1. **详情接口串行往返过多**：getDetail 个人态 + 公共态共 8~9 次互不依赖查询全部串行执行，跨洲单次 371ms × 串行链 = 秒级；
2. **列表缓存被高频失效打穿**：`DancerListCacheService` 旧实现一律 `invalidateAll()` 全清——认可 toggle 12h 34 次，每次都清空全部城市×分页×排序条目，列表缓存命中率实际很低（缓存名存实亡）；
3. **收藏列表无后端缓存**：`listFavorites` 每次 ~10 次 DB 往返，实测 1.5~2.9s；前端 `refreshCurrentUser` 每次 onShow 拉网络、`recordDancerView` 每次进详情立即 POST，与首屏关键请求抢占通道与连接池。

### 缓存失效分级（DancerListCacheService + DancerService）

失效分两级，调用点按写语义归类：

- **精失效 `invalidateByDancerId(dancerId)`**：只清该舞伴所在缓存条目。适用 = 排序信号写（认可 toggle、收藏 add/remove 的 30 天收藏 tie-break、联系解锁、照片增删审——只影响该舞伴热度分）。实现 = 反向索引：`dancerKeys`（dancerId → Set<缓存key>）+ `keyDancerIds`（key → Set<dancerId>）双向维护，cache `removalListener` 同步清理；get 成功后 `registerIndex` 登记；未命中零操作。
- **全清 `invalidateAll()`**：仅成员资格写（新建/编辑城市昵称/服务类别增删/状态流转 HIDDEN↔NORMAL/认证流转）——只有这些改变"某舞伴是否/如何在列表出现"。
- 批量 `invalidateByDancerIds(Collection<Long>)`：预留对称 API。

**事务兜底约定**：内联失效（写事务内）+ `afterCommit`/`afterCompletion` 兜底（防并发读者缓存旧值 / 事务回滚污染缓存）；同一写路径内列表+详情双失效时，列表侧兜底由精失效方法统一注册，详情侧单独注册，避免重复注册。

**2026-08-31 解锁写路径矩阵收敛**：解锁记录写路径共四条（直连 `PointsService#unlock` /
邀约获批 / 24h 自动发放 / 代找替代，后三条在 `DemandRelayService`），「解锁写入 → 失效
详情族（级联统计）+ 列表精失效」的 afterCommit 样板曾散落两处手抄并发生漂移（中转路径
漏失效列表缓存，HOT 排序在 60s refresh 兜底前读到旧分）。现收敛为单入口
`DancerUnlockCacheInvalidator#afterUnlockWrite(dancerId)`（矩阵唯一事实源：详情族级联
`DancerDetailCacheService.invalidate` + 列表精失效 `DancerListCacheService.invalidateByDancerId`，
事务内注册 afterCommit、无事务立即失效；幂等跳过不调用）。四条写路径统一走协调器，
新增写路径只调用一个方法，矩阵内容改一处全局生效——防同类漂移的长期方案。

### 用户级缓存（DancerFavoritesCacheService，对「个人态永不缓存」的语义细化）

`listFavorites` 接入 `favoritesCacheService.get(userId, this::assembleFavorites)`：Caffeine `Cache<Long, List<DancerSummaryResponse>>`，**键 = userId**，30s TTL + 500 容量，Caffeine 单飞防击穿；loader 由 DancerService 注入（组装逻辑单一权威在 `DancerService#buildSummaries`）。

> **铁律细化**：「个人态永不缓存」指**永不进用户无关的共享缓存**（键不区分用户即跨用户泄漏红线）；**按 userId 隔离的短 TTL 缓存（30s + 容量上限）不跨用户泄漏，允许**。写路径（收藏 add/remove）显式 `invalidate(userId)`。

### 详情并行化（getDetail，最大收益项）

- 8 个互不依赖只读查询全部并行：`fetchMyTodayState`（认可+标签链式查询聚合为单分支）、`fetchFavoriteState`、`contactUnlocked`（showAllPhotos 时 `completedFuture(true)` 直通）、profileTags 字典解析、lastAlbumUpdatedAt、detailCacheService.get、fetchPhotos、recentDemandSummary；
- 专用线程池 `detailAsyncExecutor`：**固定 4 线程 daemon**（对齐 Hikari 池上限 5——主线程事务持 1 连接，并行分支至多占 4，不排队不饥饿）；已完成 Future 直通免占线程；
- `awaitDetail`：join + `CompletionException` 解包，`BusinessException` 按原错误码传播；
- **红线：并行任务内只做仓储/服务层只读调用（各自独立事务/会话），严禁触碰主线程会话的懒加载实体**——dancer 仅按已加载标量字段消费；
- fetchPhotos 查询合并（4 次 → 2 次）：`Map<PointsGateTargetType, Collection<Long>>` 合并照片/视频门槛与解锁查询，`pointsService.gateCosts/unlockedIds` 多类型批量 IN 查询（语义约束 = 同一 targetId 不得出现在多个 targetType 下，媒体 id 全局唯一天然满足）。

### 前端编排（auth.ts + dancer.ts）

- **`refreshCurrentUser` 30s TTL**（`USER_REFRESH_TTL_MS`）：TTL 窗口内跳过网络请求；`saveCredentials/clearCredentials` 复位（登录/登出强制下次刷新）——对齐前端既有 favorites 30s TTL 契约；
- **`recordDancerView` 本地队列批量**：enqueue 去重（同窗口同舞伴只保留首次来源）→ 10s 定时 flush（模块级懒启动，队列清空停止）+ 阈值 10 提前 flush → 逐条经 serial-queue 串行发送（至多 1 个在途）；契约不变（入队即返回 fire-and-forget）。浏览统计走后端 60s refresh 缓存兜底，10s 延迟可接受；进程被杀丢队 = 统计少量损失，符合既有语义。

### 预期收益

- 详情：串行 8~9 往返 → 并行 1~2 往返链路（~371ms×2 + 组装）→ 进 0.8s 内；
- 列表：缓存命中率回升（精失效不再被高频认可写打穿）+ 收藏列表命中 30s 缓存 → 进 1.5s 内。

## 未竟事项（长期方案，数据规模增长后再动）

1. **HEAT_SCORE 双算**：CASE WHEN (HEAT_BEHAVIOR)>0 与求和项各出现一次 HEAT_BEHAVIOR（6+ 标量子查询 ×2）。当前被列表缓存消化（60s 只算一次）；SQL 改写（LATERAL/CTE 单次计算）涉及 4 个 @Query + SqlTest，风险/收益比不划算，暂缓。
2. **DB 迁回国内**（阿里云 RDS/PolarDB 或国内 Supabase）：单次往返 371ms → ~5ms，所有接口质变。架构级改动，长期最值得。
3. **主包分包**（前端 46 页零 subPackages）：冷启动下载体积 ~4MB，属前端侧优化，见前端 AGENTS.md。
