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
| 带坐标列表查询（排序含邻近加成 100/(1+km)） | ❌ 永不缓存 | 结果与坐标强相关，缓存共享语义不成立 |
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

## 实测效果（优化后）

列表接口缓存命中时：主查询+count+角标×2+权重 全命中 → 剩 badges/浏览量/照片 3~4 次批量往返（~400~600ms 级）。**注意**：匿名用户 badges 仅 1 次往返（无个人态查询），登录用户 +1。

## 未竟事项（长期方案，数据规模增长后再动）

1. **HEAT_SCORE 双算**：CASE WHEN (HEAT_BEHAVIOR)>0 与求和项各出现一次 HEAT_BEHAVIOR（6+ 标量子查询 ×2）。当前被列表缓存消化（60s 只算一次）；SQL 改写（LATERAL/CTE 单次计算）涉及 4 个 @Query + SqlTest，风险/收益比不划算，暂缓。
2. **DB 迁回国内**（阿里云 RDS/PolarDB 或国内 Supabase）：单次往返 371ms → ~5ms，所有接口质变。架构级改动，长期最值得。
3. **主包分包**（前端 46 页零 subPackages）：冷启动下载体积 ~4MB，属前端侧优化，见前端 AGENTS.md。
