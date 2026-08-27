# 场所热度与多维统计

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 场所热度（多维度统计）

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/venues/{id}/heat` | 公开 | 综合热度指数 + 分项统计 |
| POST | `/venues/{id}/view` | 软鉴权 | 记录详情页浏览（fire-and-forget） |

### 热度公式

```
heatScore = max(0, viewCount30d × 1
          + favoriteCount × 10
          + newFavoriteCount30d × 15
          + postCount × 5
          + ratingCount30d × 8
          + positiveReactionCount30d × 3（仅 Polarity.POSITIVE 的 code，见「Reaction 快速反馈系统」章节）
          + pointsReceived30d × app.points.heat-weight（2026-08-10 V2 新增：近30天收到积分，
            权重运营可校准——初始 2，V2 三阶段校准机制见「积分系统」章节）
          + (satisfactionScore − 6) × 20（无评分时为 0，6 分为中性基准）)
```

**权重收敛（2026-08-10 V2 重构，根治三处镜像漂移）**：非配置化权重常量收敛到
`config/VenueHeatWeights`（一处定义、三处引用：`VenueHeatService` 常量引用 +
`VenueRepository.HEAT_SCORE` 字符串拼接 + `findHotVenueIds` 字符串拼接）；
积分权重是运营可调参数走 `PointsProperties.heatWeight()`（JPQL 参数 `:pointsWeight`
注入，SQL 侧不硬编码）。**调整权重只改一处即可生效**（公式文案由后端下发自动同步）。
SQL 侧镜像一致性由 `VenueHeatServiceTest` 公式测试 + 本 AGENTS.md 约束维持。

**2026-08 缺陷修复确立的语义**（详情页热度专项）：
1. **Reaction 分极性**：仅正向 Reaction（人气旺/氛围好/音乐棒等）计入热度；负向（服务问题/排队太久等）**不计入公式**，以 `negativeReactionCount30d` 单独下发，前端展示"负面反馈 N 条·不计入热度指数"——修复"被吐槽的店热度反而更高"的语义硬伤。中性（普通）也不计入。极性定义在 `ReactionCode.Polarity`（唯一事实源），**code 列表唯一入口 = `ReactionCode.positiveCodeNames()` / `negativeCodeNames()`**——热度计算、趋势聚合、列表排序 SQL 镜像全部经此取列表，禁止各调用方自行遍历枚举再各自 filter（新增/调整极性遗漏某处即产生口径漂移）。
2. **满意度中性偏移**：满意度贡献 = `(score − 6) × 20`，6 分（及格线）为中性基准，高于 6 加分、低于 6 扣分——低分店热度真实下降，口碑差不再靠收藏/浏览撑高。
3. **非负收敛（2026-08-08）**：满意度负偏移可能把总分拉负，`heatScore` 恒 `max(0, 计算值)`——热度指数语义非负（前端详情页 chip 以 `heatScore > 0` 为"有数据"判据，负分会导致详情页隐藏、热度页显示负数的两端展示矛盾）。公式文案对 clamp 场景标注「按0计」。
4. **公式文案后端下发**：`VenueHeatResponse.formulaText/formulaDetail` 由后端生成（权重唯一事实源），前端直接渲染、**禁止硬编码权重**（历史上前端 computeHeatFormula 硬编码 ×1/×10/×15/×5/×8/×3/×20，权重调整后展示即失真——已删除）。

**列表排序/热门标记的口径（2026-08-08 统一，修复双口径分叉）**：
- 列表「热度最高」排序（`VenueRepository.searchHeat*`）、推荐排序的热度项（`searchRanked*`）、热门场所标记（`findHotVenueIds`）全部使用 `VenueRepository.HEAT_SCORE` 片段 = **行为热度镜像公式**（sortWeight + 近30天浏览×1 + 收藏×10 + 新增收藏×15 + 动态×5 + 评分×8 + 正向反馈×3 **+ 近30天收到积分×:pointsWeight（2026-08-10 V2）**，窗口在 SQL 内取 `CURRENT_DATE` 锚定「截至昨日」——**排序口径保持截至昨日**（与热度页 `VenueHeatService` 的 2026-08-13 实时口径不同：排序是稳定比较基准，实时化会让同日不同时刻排名漂移，且与 VenueHeatService 的"同源不同窗口"状态一致——热度指数计算与排名不要求逐位一致，见「场所热度」章节）。
- **零行为权重守卫（2026-08-27）**：`HEAT_SCORE` 拆为 `HEAT_BEHAVIOR`（行为热度，不含权重）+ 守卫壳——行为热度 = 0 的门店运营权重不参与排序（`CASE WHEN HEAT_BEHAVIOR > 0 THEN sortWeight ELSE 0 END + HEAT_BEHAVIOR`）。生产实证：79 家种子门店被批量赋予 30~50 权重、42 家零行为，仅靠权重挤占真实热门排位（MT舞酒吧 权重45+行为76 被抬到第3）。热门判定无需守卫——行为门槛（≥70）已兜底。详见 06-listing-and-stats.md「零行为权重守卫」。
- **满意度偏移不进排序**：排序看"行为热度"（可 SQL 镜像、非负、稳定），口碑（±80 微调）在热度页综合呈现——语义划分：排序热度 = 行为热度，展示热度 = 行为热度 + 口碑偏移。
- **约束（2026-08-10 V2 权重收敛）**：`HEAT_SCORE` 与 `findHotVenueIds` 是 SQL 双镜像；全部非配置化权重经 `VenueHeatWeights` 常量拼接、积分权重经 `:pointsWeight` 参数注入（配置唯一事实源 `app.points.heat-weight`）——**调整权重只改一处**（常量或配置），镜像一致性由 `VenueHeatServiceTest` 公式测试 + 代码注释互指维持。
- **约束**：`HEAT_SCORE` 与 `findHotVenueIds` 是 SQL 双镜像，权重调整必须三处同步（VenueHeatService 常量 + HEAT_SCORE + findHotVenueIds），由本 AGENTS.md 约束；SQL 侧无法引用 Java 常量，镜像一致性靠 `VenueHeatServiceTest` 公式测试 + 代码注释互指维持。

### 数据采集层

**浏览记录（`qwt_venue_views`）**：已登录用户按 `(venueId, userId, viewDate, source)` 联合唯一索引去重（同一天同一来源仅一条；**V21 起含 source，多渠道独立计数**——2026-08-13 晚产品决策"搜索/列表是不同流量，搜索进入必计 SEARCH"）；匿名用户 `userId=null`，无法按身份去重，2026-08 起叠加 **60s 简单频控**（`VenueViewService` 内嵌 Caffeine，key = `venueId:客户端IP`，X-Forwarded-For 第一个地址，取不到时降级为固定 key 的场所级防抖）——压制脚本连点/自动刷新放大 PV。频控尽力而为，多 IP 分布式刷无法拦截；已登录用户由 upsert 按来源去重（一天内单一来源最多 1 条、全来源合计最多 4 条），无需频控。前端进入详情页时 fire-and-forget 调用 `POST /venues/{id}/view`，失败静默。

**浏览来源（`qwt_venue_views.source`，2026-08-13 新增「浏览来源」统计图；2026-08-13 晚新增 `SEARCH`）**：`source` 列（varchar(16) 非空，默认 `'OTHER'`，V18 迁移）承载来源枚举 `ViewSource`：`LIST`=列表页进入、`SHARE`=分享卡片打开、`SEARCH`=列表页搜索结果进入（2026-08-13 晚，来源图第三折线）、`OTHER`=其他（收藏/深链/历史兜底）。枚举类列不加 CHECK 约束（项目约定），**新增枚举值无需迁移**（V18 历史文件不改动，避免 Flyway checksum 失配）。语义约定：
- **按来源按天去重（V21，2026-08-13 晚产品决策）**：唯一键 = `(venueId, userId, viewDate, source)`——同一用户同一天经不同来源进入各自计数、互不覆盖，**搜索进入必计 SEARCH**（用户明确："搜索和列表进去的是不一样的流量"）。防刷上限仍在：单一来源一天一条、全来源合计最多 4 条/人/天；匿名不去重（60s 频控兜底）；
- **口径自洽**：viewcount = 行数 = list + share + search + other 恒成立（每行有且仅有一个来源）；UV（`COUNT(DISTINCT user_id)`）不受影响；
- **兼容旧客户端**：`POST /venues/{id}/view` 的 body `{source}` 可空，`VenueController` 对 null/非法值兜底 `OTHER`（`VenueViewService.normalizeSource` 第二道防线）——旧版本不传 source 也正常工作；
- **历史局限**：V18 上线前的存量行全部 `OTHER`，来源图数据自上线日起积累，前端文档已注明；
- **写路径缓存失效（2026-08-13 修复根因「浏览来源-搜索结果折线恒 0」）**：浏览数是热度响应（viewTrend / viewSourceTrend / viewCount30d）的输入，`VenueViewService.recordView` 真实插入（upsert 受影响行数 > 0）后必须失效 `venueHeatService` 缓存——此前缺失导致详情页 onLoad 并发发起 fetchHeat 与 recordVenueView 时，缓存先填充"无新记录"版本，热度页在 60s refresh 窗口内看不到刚记录的来源。实现遵循「失效时机约束」：afterCommit 注册（见下「写路径缓存逐出」矩阵）；受影响行数 = 0（同一来源当天已存在，DO NOTHING）时统计不变，不失效（与 FavoriteService「幂等无写入分支不逐出」同约定）；
- **ON CONFLICT 兼容性（V21 潜在历史根因）**：upsert 冲突目标必须用列清单推断（`ON CONFLICT (venue_id, user_id, view_date, source)`），禁止 `ON CONFLICT ON CONSTRAINT`——V1 基线创建的是 CREATE UNIQUE INDEX（唯一索引，非约束），ON CONSTRAINT 只匹配约束、不匹配索引，生产库若保持索引形态则浏览写入每次抛错且被 fire-and-forget 静默吞掉（浏览折线全 0 的潜在根因，与缓存失效缺失叠加）。

**状态变迁日志（`qwt_venue_status_logs`）**：每次 `Venue.status` 字段变更时由 `VenueService` 自动写入（含创建时的初始记录 `fromStatus=null`）。记录 `fromStatus`、`toStatus`、`changedBy`、`createdAt`。用于统计"近 N 天暂停营业次数"和"当前状态持续天数"。

### 满意度计算

综合满意度 = 各维度（`RatingDimensions.ALL`）评分的等权均分，优先取近 30 天窗口数据，无近期数据时回退全量。评价总人数 < 3 时返回 `null`（前端展示"暂无足够评价"）。

**窗口口径（2026-08 确立）**：满意度窗口与 `ratingCount30d` 统一按 `created_at`（评分创建时间）统计，而非 `updated_at`——改分不把记录拉回窗口，防"定期改分让计数/满意度常青"的刷分漏洞（历史实现用 updated_at，用户反复改分即可让该行一直在窗口内）。注意：详情页评分 Tab 的三窗口展示（`TagAggregateStatsService.aggregateScoresMultiWindowByTag`）仍用 updated_at，那是"评分展示"的时效语义，与热度统计口径不同，勿混用。

### 统计口径：实时（2026-07-31 确立「截至昨日」；2026-08-13 改为实时）

`GET /venues/{id}/heat` 的所有滚动窗口指标（近30天浏览/收藏/动态/评价/Reaction、近30天趋势序列）统一以**请求时刻 now**为排他上界，**含今日已发生的数据**（用户需求：统计图实时）：

```java
LocalDate today = LocalDate.now();
LocalDateTime now = LocalDateTime.now();
LocalDate statsAsOfDate = today;                              // 展示给前端的"数据截至"日期（=今天）
LocalDateTime windowSince = today.atStartOfDay().minusDays(WINDOW_DAYS); // 窗口起点 = 今天 0 点 - 30 天
LocalDateTime windowUntil = now;                              // 排他上界 = 请求时刻（实时）
LocalDate viewUntil = today.plusDays(1);                      // views 按 DATE 列过滤，上界 = 明天 0 点（覆盖今日全天）
```

**历史根因（截至昨日口径）**：当天数据是"过了一半的一天"，混入窗口会让最新一天系统性偏低、误读为"在下滑"；且同日多次请求结果漂移、缺乏可复现性（2026-07-31 确立）。

**2026-08-13 实时化权衡（用户需求 > 原约定）**：实时口径的代价 = 今日数据为"未走完的一天" + 同日多次请求结果随请求时刻漂移。由前端 banner「数据实时更新 · 含今日」显性承担口径说明（原「数据统计截至 X（不含今日）」文案删除）。

**规则**：
- 涉及"近 N 天"窗口统计的 Repository 查询方法必须同时接收 `since` 和 `until` 两个排他边界参数（`[since, until)`），不能只传 `since` 靠调用方自然到"现在"
- `until` 统一为请求时刻 `now`，由 `VenueHeatService.getHeat` 一处计算后传给所有子查询，禁止各查询各自计算；views 的 `until` 为 `today.plusDays(1)`（DATE 列口径）
- **例外**：`VenueStatusLogRepository.countSuspensionsAndLatestTime` 的 `latestcreatedat`（当前状态持续天数的依据）代表"当前状态"这一实时事实，不是滚动窗口聚合，不受该上界约束，保持全量 `MAX`
- `VenueHeatResponse.statsAsOfDate`（`yyyy-MM-dd`，=今天）随接口返回，语义为"统计截止日期（含今日实时）"；前端 banner 用固定文案「数据实时更新 · 含今日」，不再展示日期

### 趋势（favoriteTrend / unfavoriteTrend / viewTrend / viewSourceTrend / reactionTrend，2026-08-08 重构 / 2026-08-13 浏览来源 / 2026-08-13 取消收藏）

`GET /venues/{id}/heat` 附带五组近 30 天每日时间序列（含今日，骨架 31 天），供前端趋势图（收藏/浏览/浏览来源/反馈/收到礼物价值）渲染：

- `favoriteTrend: List<FavoriteTrendPoint(date, count)>`——每日新增收藏数（按 `created_at` 分组，`deleted=false`）
- `unfavoriteTrend: List<FavoriteTrendPoint(date, count)>`——每日取消收藏数（2026-08-13 V19 新增，按 `qwt_favorites.unfavorited_at` 分组，与 `favoriteTrend` 同骨架同窗口）。**口径**：`unfavorited_at` = 取消动作时刻（`FavoriteService` 唯一写方：取消收藏写入 now、重新收藏清空为 NULL），计"取消动作"而非"当前状态"；与新增收藏并排呈现后"新增 − 取消 = 净变化"可被趋势图验证（顶部收藏总数 = 历史新增 − 历史取消的理论恒等式）。历史取消动作无时间戳可回溯，数据自 V19 上线日起积累（已知局限）
- `viewTrend: List<FavoriteTrendPoint(date, count)>`——每日浏览数（含匿名，与 `viewCount30d` 同源同口径；结构与收藏趋势相同，复用 `FavoriteTrendPoint`）
- `viewSourceTrend: List<ViewSourceTrendPoint(date, list, share, search, other)>`——每日浏览来源分列（2026-08-13 新增「浏览来源」折线图数据源；2026-08-13 晚新增 `search`=列表页搜索结果进入，第三折线）：`list`=列表进入、`share`=分享打开、`search`=搜索结果进入、`other`=其他；**`list + share + search + other = 当日 viewTrend` 同源可交叉验证**；`other` 由 SQL 减法派生（全量 − list − share − search，省一次扫描），前端图上只画 list/share/search 三条折线
- `reactionTrend: List<ReactionTrendPoint(date, positive, negative)>`——每日正/负向反馈分列（分极性语义直接服务 2026-08 确立的「负向不计入热度」规则，正负并排呈现让用户一瞥看出口碑走势）

**窗口 30 天（2026-08-08 由 14 天扩展；2026-08-13 实时化含今日）**：与其余滚动指标一致。根因：前端时间范围刷选控件（略缩图）需要足够长的全量窗口才有"缩放"意义——全量 = 趋势窗口，默认选中最近 14 天，用户可放大到全量或缩小到 7 天；14 天全量无法表达"拉近看 7 天"。常量 `VenueHeatService.TREND_WINDOW_DAYS`（= `WINDOW_DAYS`，数值不变，语义 = "近30天 + 今日"）。

**统一取数（趋势 mega-query）**：全部序列由 `VenueRepository.countDailyTrends` **一条 SQL** 返回——`generate_series` 生成连续日期骨架（服务端补零天然达成，替代 Java 侧逐日填充），favorites（含取消收藏 unfavorited_at）/ views / view-sources（LIST/SHARE/SEARCH 三个来源子查询）/ reactions（正负向各自子查询）八个 GROUP BY 子查询 LEFT JOIN 到骨架。根因：各趋势图一条查询会把热度接口往返从 2~4 次膨胀到 5~7 次（见「查询性能优化」第三轮）。2026-08-13 来源分列以两个子查询 JOIN 进同一骨架——不新增往返；2026-08-13 晚 SEARCH 分列追加第三个子查询、取消收藏（V19）追加第四个子查询，仍不新增往返。投影 `DailyTrendRow` 对应新增 `getViewlistcount()` / `getViewsharecount()` / `getViewsearchcount()` / `getUnfavcount()`。

**时区链缺陷（2026-08-08 实机复现：统计图全空但互动卡片有数）**：`generate_series(date, date, interval)` 的 date 参数被 PG 解析到 **timestamptz 重载**（datetime 类别 preferred type）——骨架列是带时区的时刻，与源表 DATE 列比较时 PG 按 session timezone 提升 DATE，骨架又受 session/JVM 时区链影响，非 UTC 时区下 LEFT JOIN **恒失配**（计数全 0，且骨架日期整体偏移一天）。**标准修复：骨架显式 `CAST(:sinceDate AS timestamp)` 走 timestamp 无时区重载 + 整体 `::date` 收口为纯 date 比较域**——与 session/JVM 时区完全无关（UTC / Asia/Shanghai / America/Los_Angeles 三时区实测窗口与计数一致）。**长期规则：涉及 generate_series 日期骨架的 SQL，参数必须显式 `CAST(... AS timestamp)`（禁裸 `:param::cast`——Hibernate 会把 `::` 吞进参数名报 No parameter named），输出统一 `::date`，禁依赖 PG 隐式重载解析；勿用 `date = timestamptz` 跨类型比较。**

**规则**：
- 窗口骨架为 `[今天-30, 今天]`（31 个连续日期点，含今日），views 过滤 `[today-30, tomorrow)`、其余源表过滤 `[windowSince, now)`
- 序列恒为 31 个连续日期点（generate_series 骨架保证），前端无需处理"缺失日期"分支
- 与 `newFavoriteCount30d` 等 30 天窗口总数同源但独立查询，不做互相推导——两者语义不同（求和统计 vs 按天时间序列），保持查询职责单一
- **口径差异（收藏趋势 vs 顶部收藏数）**：`favoriteCount` = 全量历史累计收藏（`deleted=false` 全量 COUNT），`favoriteTrend` = 近30天新增窗口——顶部有值但近30天无新增时趋势恒 0 属正常口径（2026-08-13 用户反馈根因；前端空图恒渲染 + 提示行承接，勿把两口径强行对齐）
- 旧 `FavoriteRepository.countDailyFavoritesSince` 已删除（唯一调用方迁至趋势 mega-query）

**收藏/取消收藏写操作频控（2026-08-13 防刷）**：`FavoriteService` 对**真实状态切换写入**（首次收藏 / 恢复收藏 / 取消收藏）做 60s 窗口阈值频控（内存 Caffeine，key = `user:venue`，窗口内放行 `TOGGLE_RATE_LIMIT_PER_WINDOW=3` 次，超出幂等忽略）。根因：「新增收藏」只在首次收藏时计（restore 不新增行、created_at 不变），天然防膨胀；但「取消收藏」每次真实取消都写 `unfavorited_at` 新时刻——恶意"收藏→取消"循环会把取消折线刷高（新增 +1、取消 +N 口径不对称）。频控后循环被压制成窗口内最多 3 次真实写入（正常用户 1 分钟 toggle 极少超 3 次，覆盖"收藏→取消→再收藏"），取消折线最多每分钟 +2，与真实操作语义一致。**幂等路径（已收藏再收藏等无写入）不计数不频控**——正常误点无害。与既有频控同族（VenueViewService 匿名浏览 60s、feedback 60s）；前端 `venue-detail.onToggleFavorite` 另有 in-flight 守卫防连点（请求完成前忽略重复点击）。

### 营业稳定性

- "暂停营业次数" = 近 30 天内（截至昨日）`toStatus = SUSPENDED` 的状态变迁记录数
- "当前状态持续天数" = 最近一条状态日志的 `createdAt` 距今天数——这是实时事实，不受"截至昨日"窗口约束（见上节）
- `SUSPENDED`（暂停营业）语义为被迫关门（警察检查等），与 `CLOSED`（休息中，正常未到营业时间）不同

### 状态可信度（StatusConfidence）

`VenueHeatResponse` 返回 `statusConfidence` 等级 + **`statusConfidenceText` 结论文案 + `statusConfidenceRuleDetail` 判定依据**（2026-08-08 新增），向用户传达"当前状态信息有多可信"。枚举值：`HIGH` / `MEDIUM` / `LOW`。**判定逻辑与文案的唯一事实源在 `VenueHeatService`，前端只渲染**（与热度公式文案 `formulaText/formulaDetail` 同模式，规则调整免发前端）。

**三维矩阵（2026-08-08 根因修复：旧二维矩阵缺"状态类型"维度）**——稳定性（suspensionCount30d）× 当前状态持续天数（currentStatusDays）× **当前状态类型（营业中 vs 非营业）**：

**营业中（OPEN）**（保留原二维矩阵语义）：

| | currentStatusDays 短（≤ 7 天） | currentStatusDays 长（> 7 天） |
|------|------|------|
| **稳定**（suspensionCount30d == 0） | HIGH | HIGH |
| **不稳定**（suspensionCount30d > 0） | MEDIUM | LOW |

**非营业（已停业 CEASED / 暂停营业 SUSPENDED / 装修中 RENOVATING / 休息中 CLOSED）**：

| | currentStatusDays 短（≤ 7 天） | currentStatusDays 长（> 7 天） |
|------|------|------|
| **任意暂停次数** | MEDIUM（建议确认） | HIGH（状态可信） |

**根因**：旧二维矩阵隐含假设"门店营业中"——"稳定门店无论多久没改状态，营业中就是可信的（不更新≠不准确）"只对 OPEN 成立。已停业门店近 30 天暂停 0 次是常态（暂停 = `toStatus = SUSPENDED` 变迁，停业门店不会产生），旧矩阵因此把"长期停业"（本应是最强的停业证据）判为 HIGH 且被前端硬编码成"稳定营业"——"已停业却显示稳定营业"（寻梦缘123 生产实证）。修复要点：
- **非营业分支不依赖暂停次数**（该指标对非营业门店无区分力），决定可信度的是"该状态已稳定持续多久"（长期未被纠正/反向信号 = 被时间验证，最可信）与"有无反向实时信号"
- **文案按状态类型分治**：OPEN+HIGH=「稳定营业」，非营业+HIGH=「状态可信」，非营业+MEDIUM=「建议确认」——等级与文案同处生成，杜绝语义错配

**活跃上报覆盖规则**：当 `VenueHeatService` 获取到 `activeCount > 0`（近 4 小时有用户报告暂停）时，`computeStatusConfidence` 直接返回 `LOW`（文案「数据可能过时」+ "近 4 小时有 N 人报告暂停营业"），跳过上述矩阵，**对营业中与非营业状态一视同仁**——众包实时信号优先级高于历史矩阵。根因：矩阵基于历史记录（管理员维护的 `Venue.status` 变迁），无法反映"此刻正在发生"的事件；用户现场上报正是为了弥补这一滞后。

### 查询性能优化（三轮：条件聚合 → 跨表合并 → 趋势合并 + refresh-ahead 缓存）

根因：服务器在阿里云 ECS，数据库在 Supabase（AWS ap-south-1），单次 DB 往返约 300-500ms，**接口延迟 ≈ 串行往返次数 × 单次往返**。应用层优化的唯一抓手是压缩每个接口的串行 DB 往返数；根本解是 DB 迁近区（迁移前所有新接口设计必须以"最少往返"为第一约束）。

**第一轮（同表条件聚合）**：同一张表的多个 COUNT/aggregation 用 `SUM(CASE WHEN...)` 合并为单条 SQL。**第二轮（跨表合并 + 编排优化）**：跨表单值聚合收敛为标量子查询 mega-query、两步查询合并为联查/upsert、聚合缓存 refresh-ahead。**第三轮（趋势合并，2026-08-08）**：多行时间序列（收藏/浏览/正负向 Reaction）合并为一条 generate_series 骨架查询（`countDailyTrends`）。当前各接口冷启动往返数：

| 接口 | 往返构成 | 冷启动 | 缓存命中 |
|------|---------|--------|---------|
| `GET /venues/{id}/heat` | mega-query(1) + 趋势 mega-query(1) + 满意度(0~2，raters<3 跳过) | ~1s | ~3ms |
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
- `VenueRepository.countDailyTrends`：**趋势 mega-query（第三轮，2026-08-08）**——收藏/浏览/正负向 Reaction 四组按天时间序列合并为一条 `generate_series` 骨架 + 四个 GROUP BY 子查询 LEFT JOIN 的 SELECT。根因：各趋势图一条查询会把热度接口往返从 2~4 次膨胀到 5~7 次（趋势是"多行形态"，不参与单值标量合并，但可四条序列合并为一条多行查询）

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
| toggle（VenueReactionService） | `venueReactionAggregateService.invalidate` + `venueHeatService.invalidate`（**2026-08-08 起经 `TransactionSynchronization.afterCommit` 延后到事务提交后执行**，见下「失效时机约束」） |
| addFavorite / removeFavorite（FavoriteService） | `venueHeatService.invalidate`（收藏数是热度输入；幂等无写入分支不逐出） |
| recordView（VenueViewService，2026-08-13 新增） | `venueHeatService.invalidate`（浏览数是热度输入 viewTrend/viewSourceTrend/viewCount30d；**仅真实插入 affected>0 时注册**，同来源冲突 DO NOTHING 不逐出；经 afterCommit 延后到事务提交后，见「失效时机约束」） |
| submitReport / cancelReport（StatusReportService） | `venueHeatService.invalidate`（活跃报告数是热度输出） |
| createPost（VenuePostService） | `venueHeatService.invalidate` + `@CacheEvict(hotVenueIds, allEntries)`（动态数参与热度与热门排序） |
| updateVenue（VenueService） | `@Caching` 逐出 venueCache + hotVenueIds + `tagAggregateStatsService.invalidate`（tags 是聚合组装依据）+ `venueHeatService.invalidate`（status/状态日志是热度输出） |
| createVenue（VenueService） | `@CacheEvict(hotVenueIds, allEntries)`（新场所无缓存存量） |

**失效时机约束（2026-08-08 确立，长期规则）**：`invalidate` 必须在**事务提交后**执行——事务内提交前失效存在竞态窗口：另一线程读到 cache miss → 回源重算 → 读不到本事务未提交数据 → 缓存陈旧值（`refreshAfterWrite(60s)` 内持续返回）。标准做法：`@Transactional` 方法内用 `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { afterCommit() { ...invalidate } })`。**示范实现：`VenueReactionService.toggle`（2026-08-08 已落地）；其余写路径（TagInteraction / Favorite / StatusReport / VenuePost / Dancer）仍为提交前失效，存在同款窄竞态——新代码必须按 afterCommit 模式写，存量模块改造列入待办**。

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

