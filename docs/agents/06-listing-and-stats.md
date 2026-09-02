# 列表排序与城市统计

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 列表排序与城市统计

### 复合评分排序与排序方式（2026-08-06 扩展；2026-08-27 零行为守卫）

`GET /venues` 支持可选 `latitude` / `longitude`（用户定位，gcj02），列表按服务端复合评分排序（分页正确性要求排序必须在库内完成）。2026-08-06 起支持 `sort`（`VenueSortMode` 枚举：recommended/distance/heat/newest，默认 recommended）与 `radiusKm`（可选，km，距离半径筛选）：

```
recommended（默认，复合评分）score = 受守卫的运营权重 sortWeight
      + 行为热度（浏览贡献 = ln(1 + 加权浏览)（来源加权：列表0.5/其他1/搜索1.5/分享2，
        近7天×2——2026-08-27 马太效应重构，见下方「浏览贡献重构」小节）
        + 近30天新增收藏×15（2026-09-01 收敛：收藏总数为累计展示、不计入公式——旧双列
          「收藏总数×10 + 新增×15」是集合包含关系，一次收藏重复计 25 分，且存量永续制造马太）
        + 近30天新增动态×5（2026-09-01 由动态总数改窗口，admin 内容存量不参与）
        + 近30天评分数×8 + 近30天正向反馈×3
        + 近30天收到礼物价值×pointsWeight）
      —— <b>2026-09-01 距离加成移除</b>：旧公式含 100/(1+距离km) 邻近加成，用户实证
         "热度 2 的本地店压过热度 17 的外地店"不合理（西部舞厅 4.5km +18 分 vs 今之音乐酒吧
         114km +0.9 分，距离项 > 两家店的活跃热度差 15 分）。距离的唯一影响收敛为
         radiusKm 半径筛选（默认 300km），排序纯看热度：本地近 ≠ 靠前
distance  = 纯距离升序（Haversine），无坐标场所排最后（2026-08-28 起：ORDER BY 距离 ASC NULLS LAST——不再硬排除，城市筛选下无坐标门店可见但沉底），id 兜底 tie-break
heat      = 受守卫的运营权重 + 行为热度（与「热门场所标记」同口径——热度是场所属性，不随请求者位置变化），id 兜底
newest    = created_at DESC, id DESC
```

**零行为权重守卫（2026-08-27，生产实证修复）**：行为热度 = 0 的门店（无任何近30天浏览/收藏/动态/评分/正向反馈/收到礼物的「僵尸门店」）**运营权重不参与排序**——`HEAT_SCORE = CASE WHEN HEAT_BEHAVIOR > 0 THEN sortWeight ELSE 0 END + HEAT_BEHAVIOR`（公式实现见 `VenueRepository.HEAT_BEHAVIOR` / `HEAT_SCORE`）。根因：79 家种子门店被批量赋予 30~50 运营权重，其中 42 家行为热度为 0，仅靠权重挤占真实热门门店排位（生产实证：MT舞酒吧 sortWeight=45 + 行为 76 被抬到列表第 3）。语义与热门标记「行为热度 ≥ 门槛」同构：**权重提升曝光是运营本职，但不伪造「有人气」**。行为热度 > 0 的门店权重照常生效。热门判定（findHotVenueIds）无需本守卫——绝对门槛（行为 ≥ min-heat-score）已兜底零行为门店。

**权重与公式演进**：旧公式（收藏×20 + 动态×10）已于 2026-08-10 V2 权重收敛重构为上述行为项（唯一事实源 = `VenueHeatWeights` + `PointsProperties#heatWeight()`，调整权重必须同步 `HEAT_SCORE` / `findHotVenueIds` / `VenueHeatService.computeHeat` 三处镜像，见后端 AGENTS.md「场所热度」章节）。**2026-09-01 口径收敛**：收藏总数 / 动态总数两项存量指标退出公式（收藏只计近30天新增、动态只计近30天新增）——根因见 `VenueHeatWeights` 注释（集合包含重复计分 + 存量马太，与 2026-08-27 浏览贡献重构方向一致）。

### 浏览贡献重构（2026-08-27，马太效应反馈循环修复）

**问题（根因）**：浏览是行为热度中唯一的「被动信号」——详情页点开即计（门槛极低，误触/惯性/每日回访都算），且旧公式线性 ×1 计入。排序靠前 → 曝光多 → LIST 点入多 → 浏览涨 → 排名更前：位置偏差驱动的正反馈闭环（马太效应）。头部店浏览量被排序位置系统性放大，形成虚假热度优势；收藏/评分/Reaction/动态等主动信号成本高、有上限，不受此放大——两相对比，浏览项的权重失真最严重。

**解法（三件套，缺一不可）**：浏览贡献从「近30天 PV × 1」重构为

```
浏览贡献 = round( ln(1 + Σ( source_weight × time_factor )) )   -- 30 天窗口
source_weight: LIST 0.5 / OTHER 1.0 / SEARCH 1.5 / SHARE 2.0
time_factor:   近7天（含今天）2.0，7~30 天 1.0
```

1. **来源质量加权**：LIST（列表曝光驱动，位置偏差最大，马太闭环核心）降权 0.5；SEARCH（主动搜索进入，强意图）1.5；SHARE（分享卡片进入，口碑传播，最真实的"被推荐"信号）2.0；OTHER（收藏/深链等）维持 1.0 基准。来源判定唯一事实源 = 前端 venue-detail 单一判定点（share_from→SHARE / from=search→SEARCH / 列表快照→LIST / 其余→OTHER）+ `ViewSource` 枚举，落库链路零改动（2026-08-13 已有）；
2. **时效衰减**：近 7 天 ×2、7~30 天 ×1——热度更"当下"，减少"30 天前火过"对当前排名的拖影；
3. **对数压缩 ln(1+x)**：浏览量的边际信息含量递减（0→10 次浏览远比 1000→2000 更能说明热度上升），ln 压缩后头部店不再以线性差距碾压长尾。0 次→0 分，10 次→2 分，100 次→5 分，1000 次→7 分（浏览贡献上限 ≈7，主动信号权重恢复相对主导）。

**热门门槛联动（语义收紧，预期效果）**：`venue.hot.min-heat-score`（默认 70）作用于行为热度，浏览压缩后「≈70 次纯浏览达标」路径关闭——热门 ⟺ 有实质主动信号（≈7 收藏，或 3 收藏+3 评分+3 反馈等组合），"光有人看≠热门"。门槛值保持 70 不动（7 收藏锚点仍成立），上线初期若热门过少（数据稀疏），运营下调 YAML 即可（如 40），见 `VenueHotProperties` javadoc。

**实施落点（三处镜像 + 唯一事实源）**：
- `VenueHeatWeights`：`VIEW_SOURCE_LIST/OTHER/SEARCH/SHARE` + `VIEW_RECENCY_7D_MULTIPLIER`（唯一事实源）；
- `VenueRepository.VIEW_BEHAVIOR`（JPQL 排序，独立常量，HQL 枚举比较用全限定字面量 `org...ViewSource.LIST`、`LN` 为 Hibernate 注册函数、近7天减法带 `day` 后缀）+ `findHotVenueIds`（native，字符串 `'LIST'` 比较 + PG `LN`）+ `countHeatCounters` 新增 `weightedviews30d` 列（热度页输入）；
- `VenueHeatService.computeHeat`（Java，`Math.log1p`）——viewCount30d（原始 PV）仍下发仅供展示，不再线性进入公式。

**窗口差异保持**：排序/热门口径 [今天-30, 今天)（截至昨日，日内稳定）；热度页实时口径 [今天-30, 明天0点)（含今日）——仅权重结构重构，窗口边界不动（2026-08-13 分家沿用）。

**距离定位（2026-09-01 收敛）**：距离**不再参与排序**——旧「邻近加成 100/(1+km)」已移除（用户实证"热度 2 的本地店压过热度 17 的外地店"不合理：西部舞厅 4.5km +18 分 vs 今之音乐酒吧 114km +0.9 分，距离项 > 活跃热度差 15 分），距离的唯一影响 = `radiusKm` 半径筛选。
**产品意图（2026-08-27 确立；2026-09-01 修正排序口径）**：默认口径 = 附近 300km 硬作用域——前端默认传 `radiusKm=300`（`DEFAULT_RADIUS_KM`，有定位时生效；无定位时后端忽略 → 全国列表，前端面板按实际生效口径显示「全部」激活），**圈内排序纯看热度**——"用户跨城 1 小时高铁（≈300km）去可达圈内最火门店、不看全国爆款"的动机模型：300km 圈内谁最火谁靠前（圈内距离不参与排序），跨圈（&gt;300km）门店直接不出现；早期数据稀疏 → 300km 内无店的空态死角由前端空态「查看全国门店」出口（放开半径到 0）兜底（见前端 `07-list-page.md` 默认口径段）。

**radiusKm 语义**：>0 生效（≤0/null 视为不限，Service 层归一）；与排序方式正交，仅叠加在"含坐标"的查询上（距离计算需要请求者位置为圆心）。无坐标请求携带 radiusKm 时忽略（**2026-08-27 起前端默认 radiusKm=300 且无定位时也照传**——后端忽略仅作防御，语义不变）。谓词写法：`AND (:radiusKm IS NULL OR :city IS NOT NULL OR 距离km <= :radiusKm)`——**城市放行（2026-08-28 用户反馈修复）**：显式选择城市（:city 非空）时谓词恒真，无坐标场所（距离表达式 NULL）也能按城市查询出来——城市是用户的**明确作用域**，「附近 300km」只是默认浏览视角（隐式条件，用户无从知晓：苏州全城无坐标数据导入后默认 300km 把整批静默过滤，用户选苏州却看不到苏州门店，体验断裂）；两者冲突时城市优先。未选城市（:city 为空）时维持原口径：有值半径下无坐标场所被排除（"未知距离的场所不承诺在半径内"——附近视角不混入全国无坐标门店）。前端零改动（city 照传即自动放行），空态「查看全国门店」出口逻辑不受影响（emptyRadiusScope 要求 city 为空，见前端 `07-list-page.md`）。

**distance 排序无定位降级**：前端/用户无定位时无法按距离排序，Service 防御性降级为 recommended 查询（而非空列表/报错）——`VenueService.dispatchListQuery` 的 switch 分流矩阵：recommended（有坐标/无坐标两查询）、distance（仅坐标查询 + 无坐标降级）、heat / newest（仅在有坐标且有半径时才进入 WithRadius 变体，其余走无坐标变体）。

### 双查询拆分（Postgres 平台坑位，重要）

排序拆为多个 JPQL 变体（`searchRanked` 带坐标 / `searchRankedNoLocation` 无坐标 / `searchNearest` / `searchHeat(+WithinRadius)` / `searchNewest(+WithinRadius)`），Service 按"坐标有无 × 排序方式 × 半径有无"显式分流（`dispatchListQuery`），**不要合并为"坐标可空的单查询"**：

Postgres 对无类型的 null 绑定参数推断为 `bytea`，JPQL 中 `radians(:latitude)` 在坐标为 null 时报 `function radians(bytea) does not exist`；SQL 层 `cast(? as float8)` 也救不了（`cannot cast type bytea to double precision`）。唯一干净的解法是让数学函数参数永远非 null——拆查询、Service 分流、含距离数学的查询（`searchRanked` / `searchNearest` / `*WithinRadius`）用原生 `double` 形参（编译期排除 null）。筛选条件由 `VenueRepository.LIST_FILTERS` 编译期常量共享，距离表达式由 `DISTANCE_KM` 常量共享、热度分由 `HEAT_SCORE` 常量共享、半径谓词由 `RADIUS_PREDICATE` 常量共享，避免重复。

**JPQL 文本块拼接约束**：`""" + 常量 + """` 的**开定界符必须后跟换行**（Java 文本块语法：开定界符后只允许空白 + 换行），禁止写成 `""" + X + """ DESC` 之类同行拼接（编译报 "illegal text block open delimiter"）——常量的拼接处必须把后续内容折到下一行。

**JPQL 共享片段（HEAT_SCORE 等）的 HQL 语法约束**（2026-08-08 启动失败根因，已修复）：
- **根实体必须用实体名 + Java 属性名**：JPQL/HQL（`@Query` 非 native）里 `FROM` 的根实体写数据库表名（`qwt_venue_views` 等）会在启动期查询校验抛 `UnknownEntityException: Could not resolve root entity 'qwt_venue_views'`；列引用也必须用 camelCase 属性名（`vv.venueId` / `vv.viewDate`），不是 snake_case 列名。nativeQuery 查询（`findHotVenueIds` / `countHeatCounters` / `countDailyTrends` 等）不受此约束，仍写表名——两者混在同一文件，改片段前先确认查询是 JPQL 还是 native。
- **HQL 时间量减法必须带单位后缀**：`CURRENT_DATE - 30` 会被 Hibernate 7 报 `SemanticException: Operand of - is of type 'java.lang.Integer' which is not a temporal amount`，必须写 `CURRENT_DATE - 30 day`。PostgreSQL 原生 SQL 里 `CURRENT_DATE - 30` 合法（日期减整数），nativeQuery 不受影响。
- 启动期失败先看 `Caused by` 链，Spring Data 对每个 repository 的 @Query 在 bean 创建时逐一校验，修好一个可能暴露下一个（本次连续暴露两处：实体名 → 时间量）。

**native SQL 验证（2026-08-08 线上事故教训，重要）**：`nativeQuery=true` 的查询（`findHotVenueIds` / `countHeatCounters` / `countDailyTrends` 等）**不在启动期校验**——Spring Data 对 repository @Query 的启动校验只覆盖 JPQL（HQL 解析），原生 SQL 是首次调用时懒创建、由**数据库在执行期**解析；Hibernate 7 已移除 `hibernate.query.validate_native_queries`，`hibernate.query.startup_check` 只覆盖注册到 SessionFactory 的命名查询，对 Spring Data repository 原生查询无效。叠加 Mockito 单测 mock 掉 repository，**SQL 文本错误（列引用、别名作用域、时区/类型陷阱）必然漏到运行期**（实例：`findHotVenueIds` 三层子查询重写时中间层漏投影 `heat_score`，外层 WHERE 引用不可见列 → 首请求报 `ERROR: column "heat_score" does not exist`，Position 指向外层引用处）。

**长期规则**：
1. **改写/新增 native SQL 后必须对真实数据库执行验证**——自动化载体 = `VenueHotVenueIdsSqlTest`（`@Tag("db")` + `@EnabledIfSystemProperty(run.db.tests=true)`，默认跳过不加载上下文）：`./mvnw test -Drun.db.tests=true`（需配置与启动服务相同的数据库/环境变量）；
2. **多层子查询的列透传契约**：外层 WHERE/ORDER BY 引用的派生列（如 `heat_score`）必须在**每一层中间子查询的投影中出现**——"本层可引用下层列"不等于"外层可引用"，别名作用域逐层收窄，这是本次事故的机械根因；
3. 单元测试继续 mock repository（SQL 正确性不归单测），但**接线契约**（参数流转、布尔传播）必须有单测锁死（见 `VenueLookupServiceTest` / `FavoriteServiceTest`）。

### 标签筛选（tag，2026-08-12 新增「龙女」快捷筛选）

`GET /venues` 新增可选参数 `tag`：仅返回 `tags` 含该标签**子串**的场所。实现 = `VenueRepository.LIST_FILTERS` 追加 `AND (:tag IS NULL OR v.tags LIKE :tag)`（Service 层包装为 `%xx%`，同 keyword 模式），6 个排序变体查询自动共享（`searchRanked` / `searchRankedNoLocation` / `searchNearest` / `searchHeat(+WithinRadius)` / `searchNewest(+WithinRadius)` 均新增 `@Param tag`）；与城市/状态/热门/距离筛选正交可叠加。

**标签语义（行业黑话）**：舞厅行业以标签声明对「龙女」（聋哑人舞伴群体的行业黑话）的接待政策——「龙女可进」/「龙女」= 允许（线上库实测 91 家，含 11 家冗余同带两标签）、「禁龙」= 禁止（2 家，反向标签）。「龙女」chip 勾选 = 只看允许的店（前端传 `tag=龙女`）。

**匹配口径与已知边界**：`tags` 为 JSON 数组字符串列，子串 LIKE 命中「龙女可进」与「龙女」，**不命中**「禁龙」（无"龙女"子串）；JSON 元素引号天然规避跨标签误匹配（「舞女」不含"龙女"子串）。**已知边界**：未来若新增「禁龙女」类反向标签会被本谓词误命中（元素含"龙女"子串）——新增反向标签须同步本谓词（如排除 `NOT LIKE '%禁%'`）或改用精确元素匹配。LIKE 无索引，数据规模数百级无性能压力。

### 城市词表与筛选

城市 / 区县按标准行政区划名（前端 `picker mode="region"` 产出，如"绍兴市"）**精确匹配**，写入与查询共用同一词表。禁止模糊匹配兜底——会掩盖写入端数据质量问题。存量脏数据走一次性清洗 SQL，不改查询逻辑。

### 城市统计接口

`GET /venues/cities` → `List<CityStatsResponse(city, venueCount)>`，按场所数倒序，供前端"热门城市"数据驱动展示。注意路由：字面量 `/venues/cities` 与路径变量 `/venues/{id}` 共存时 Spring 优先匹配字面量，无需特殊处理。

### 热门场所标记（VenueResponse.isHot）

`VenueResponse` 新增 `isHot` 字段（boolean），标记该场所在同城市中属于热门场所。

- **双条件判定（2026-08-08 确立，修复"热度指数 2 也有热门标签"的伪热门缺陷）**：
  1. **城市内相对排名**：按行为热度（`VenueRepository.HEAT_SCORE` 镜像，见「场所热度」章节）在同城市场所中取 top 20%（CEIL 向上取整）。即"热门"首先是相对同城市其他场所而言——避免跨城市基数差异（上海普通场所的收藏量可能 > 小城市最热门场所）；
  2. **绝对行为热度门槛**：**行为热度**（完整热度分扣除运营权重 sortWeight，SQL 内 `heat_score - sort_weight`）≥ `venue.hot.min-heat-score`（配置，唯一事实源 = `VenueHotProperties`，**默认 70** ≈ 近30天 7 次收藏 / 70 次浏览 / 14 条动态）。没有实质用户活跃的场所（纯浏览/冷启动）即使城市内排名第一也不得标记热门。
  - **旧实现缺陷（根因）**：仅相对排名 + `GREATEST(1, CEIL(city_total×0.2))`"至少 1 家/城市"兜底——每城市第一名恒被标记热门，近30天仅 2 次浏览（热度分 2）的冷门店也带热门标签。"热门"退化为"小池塘里最不冷"。兜底已移除，排名与门槛是**与**关系。
  - **门槛作用范围（2026-08-08 用户反馈二次修复）**：作用于**行为热度部分**（不含运营权重 sortWeight）。sortWeight 仍参与城市内排名（top 20%）与列表排序——运营推广提升曝光属其本职；但**不得伪造热门资格**：历史实现把门槛放在含 sortWeight 的完整分上，运营加权门店（sortWeight=68 等）即使行为热度仅 2（近30天 2 次浏览）也被抬过门槛，出现"详情页热度指数 2 却有热门标签"的自相矛盾（生产实证：南充市 venue 90，sortWeight 20 + 行为 2 = 22 ≥ 20 命中）。门槛移到行为部分后：热门 ⟺ 行为热度 ≥ 门槛，与详情页热度 chip 的核心行为项口径一致。满意度偏移（评分纠偏小项，需 ≥3 评价人才参与计算）不参与热门判定——热门回答"去的人多不多"（行为热度），满意度回答"口碑好不好"（热度指数展示），两语义解耦。
- **查询实现**：`VenueRepository.findHotVenueIds()` 使用 PostgreSQL 窗口函数（`ROW_NUMBER() OVER (PARTITION BY city ORDER BY heat_score DESC, id)`）在库内完成城市内排名，避免在 Java 侧逐城市遍历。三层子查询结构：最内层 `scored` 一次性计算热度分与 `sort_weight`（公式唯一出现点，避免 SQL 内重复书写导致镜像漂移）→ 中间层窗口排名（**必须同时投影 `heat_score` 与 `sort_weight`**——外层门槛 `heat_score - sort_weight >= :minHotScore` 引用两派生列，漏投影即报 `column ... does not exist`，见「列透传契约」）→ 最外层施加"排名 ≤ top20% 且 行为热度 ≥ 门槛"双条件。排序口径为 `sortWeight + 近30天浏览×1 + 收藏×10 + 新增收藏×15 + 动态×5 + 评分×8 + 正向反馈×3`（与 `HEAT_SCORE` 一致，不含距离项——距离是用户维度，场所热度排名不应因请求者位置变化）。Service 层通过 `VenueLookupService.getHotVenueIds()`（`@Cacheable(CACHE_HOT_VENUE_IDS)`，5min TTL，门槛参数经此注入 SQL——禁止在 SQL/调用方硬编码）获取热门 ID 集合，缓存命中时 <1ms，未命中时执行全表窗口函数查询。场所创建/更新/动态发布时通过 `@CacheEvict(allEntries=true)` 即时失效（收藏增删**不**逐出——5min TTL 的滞后是接受的权衡，见「写路径缓存逐出」矩阵）
- **消费方（2026-08-08 修复收藏列表缺热门标签；同日新增「热门」快捷筛选）**：
  - 城市列表 `VenueService.listVenues`：`result.map()` 中传 `hotVenueIds.contains(v.getId())`；同时**支持 `hot=true` 筛选参数**（2026-08-08 新增，供前端「热门」快捷标签）——`hotOnly=true` 时经 `LIST_FILTERS` 的 `AND (:hotOnly = false OR v.id IN :hotIds)` 谓词按同一集合过滤，与城市/状态/距离/排序**正交可叠加**；热门集合在列表查询前一次获取（5min 缓存），筛选参数与 isHot 标记双职责复用；
  - 收藏列表 `FavoriteService.getFavoriteVenues`：**同口径下发**——历史缺陷：误用 `VenueResponseMapper` 双参重载（默认 `isHot=false`），热门舞厅在"全部城市"正常展示、收藏列表却不展示。修复后与城市列表一样经 `getHotVenueIds()` 取集合后走三参重载（收藏 Tab 无筛选栏，不提供 hot 参数）；
  - 其余场景（创建/编辑表单回显、详情页基础响应）isHot 无展示语义，恒 false。
- **热门筛选的查询实现**：谓词挂在共享 `LIST_FILTERS` 片段（全部 7 个列表排序变体共用，含 count 查询自动继承——分页 totalElements 正确）；`hotOnly=false` 时短路恒真（默认口径=不做隐式过滤），`hotOnly=true` 且集合为空（无热门场所）时 `IN 空集` 恒假返回空页而非报错。JPQL 语法层由 `VenueListQueryHqlSyntaxTest`（ANTLR 语法解析，普通 `mvn test` 执行）守卫；参数绑定语义层由 `VenueHotVenueIdsSqlTest` 的 DB 门禁用例覆盖。
- **VenueResponseMapper 三参重载**：`toResponse(Venue, List<ReactionBadge> topReactions, boolean isHot)` ——**任何渲染 venue-card 卡片的列表场景必须走本重载**；双参/单参重载默认 `isHot=false`，仅限"热门标记无展示语义"的场景（javadoc 已显式标注，防止再次踩双参重载的静默默认值陷阱）。

### 累计浏览量（VenueResponse.viewCount，2026-08-12）

`VenueResponse` 新增 `viewCount` 字段（long，全量历史口径），驱动列表卡片底部「👁 浏览数」不起眼展示（前端 `formatViewCount` 格式化 10/100/1.2K/10K）。

- **口径**：`qwt_venue_views` 全量行数（按天按来源去重 PV，含匿名；V21 起唯一键含 source，多渠道各自计数）——与热度页 `viewCount30d`（`VenueHeatService` / `VenueRepository.HEAT_SCORE`）**同源同口径的全量版**，仅去掉 30 天窗口。不加累计计数器列、不加迁移：视图表本身即全量事实源。
- **查询实现**：`VenueViewRepository.countByVenueIds(List<Long>)`（`IN :ids + GROUP BY venue_id` 一次覆盖整页，返回 `(venueId, count)` 二元组；空集合防御——`IN ()` 语法错误，参照 `batchGetBadges` 判空模式）+ 单店 `countByVenueId`（详情页基础响应用，命中 `(venue_id, view_date)` 索引毫秒级）。
- **消费方（2026-08-12 新增，语义同 isHot 教训——禁止消费场景传默认 0）**：
  - 城市列表 `VenueService.listVenues`：批量查后走 Mapper **四参重载**传真实值；
  - 收藏列表 `FavoriteService.getFavoriteVenues`：同口径批量查后传真实值（**历史缺陷预防**：同 isHot 双参重载静默陷阱——三参重载默认 `viewCount=0`，收藏列表若漏传将"城市列表有浏览数、收藏列表恒 0"）；
  - 详情 `VenueService.getVenueDetail`：单店 COUNT 传真实值（viewCount 是事实字段，详情 base 响应同样带真实值，避免"同一字段两种语义"）；
  - 其余场景（创建/编辑表单回显）恒 0，无展示语义。
- **VenueResponseMapper 四参重载**：`toResponse(Venue, List<ReactionBadge>, boolean isHot, long viewCount)` ——卡片展示场景（列表/收藏/详情）的**唯一正确入口**；三参/双参/单参重载默认 `viewCount=0`，仅限无展示语义场景（javadoc 已显式标注）。

### 关键词检索（keyword v2，2026-09-02；前端文档 = quwuting/docs/agents/35-venue-search.md）

`GET /venues` 的 `keyword` 参数由 v1 的「name/address/description 三字段整串 LIKE」升级为
**六字段 + 门店同步别名的拆词 AND 检索**（前端搜索增强配套，用户侧口径见前端 35 文档）：

- **命中字段（`VenueRepository.KW_MATCH` 常量）**：name / address / description + city /
  district（按区/城市找店）+ tags（特征词，JSON 数组字符串子串匹配，同 tag 筛选口径）
  + `EXISTS(VenueSyncAlias sa WHERE sa.venueId = v.id AND sa.deleted = false AND
  sa.sourceName LIKE :keyword)`（舞讯/圈子称呼映射 → 平台门店，跨 venuesync 包实体子查询，
  零新表——映射沉淀见 quwuting-service/docs/agents/33-venue-sync-skill.md）。
- **ESCAPE 配套**：KW_MATCH / RELEVANCE_KEYS / suggestByName / findIdsByKeyword 的全部
  keyword LIKE 带 `ESCAPE '!'`（**2026-09-02 启动期 HQL 校验实证：ESCAPE 不能用反斜杠**
  ——HQL 语义层要求 escape 字面量恰为单字符且 MySQL 对 backslash 有字符串字面转义语义，
  双歧义；'!' 在 MySQL/JPQL 均无歧义）。Service 层 `escapeLikeLiteral` 把用户输入中
  `!`/`%`/`_` 预转义（! → !!、% → !%、_ → !_）→ **字面子串匹配**（防单字符 `%` 触发
  全表通配）。改此链路需重启校验（Spring Data 启动期 validateQuery 即暴露语义错误，
  已由本次启动失败实证覆盖）。
- **拆词 AND（`VenueService.splitSearchTerms` + `intersectKeywordIds`）**：分隔符
  = 空格/全角空格/逗号/顿号/斜杠（`KEYWORD_SPLIT_REGEX`），上限 4 词（`MAX_KEYWORD_TOKENS`）、
  原始输入截长 60（`MAX_KEYWORD_LENGTH`）。单词 → 整串子串 pattern + `kwPrefix`
  （前缀 pattern，驱动相关度排序）；多词 → 逐词 `findIdsByKeyword`（%词% 命中六字段 +
  别名）行集探测求交集 → `:filterIds` 白名单回灌主查询（`LIST_FILTERS` 新谓词
  `AND (:filterIds IS NULL OR v.id IN :filterIds)`，同 hotIds ID 白名单模式，静态 JPQL
  零动态 SQL）；交集空 → `new PageImpl<>(empty)` 短路返回不发主查询。
- **搜索相关度排序（`VenueRepository.RELEVANCE_KEYS`，仅 RECOMMENDED 两个变体
  searchRanked / searchRankedNoLocation）**：ORDER BY 前置键 ①名称前缀命中(0) > 名称
  子串(1) > 其余字段/别名命中(2)；② 组内 OPEN(0) 前置；再 HEAT_SCORE DESC、id DESC。
  **keyword 为 null 时两键恒等 → 排序退化为纯热度（行为零变化，单查询复用零扩散）**。
  **口径红线：停业/暂停门店照常展示**——OPEN 仅组内排序加权、永不参与过滤（搜索只承诺
  匹配不承诺状态；前端在搜索态隐藏排序按钮，强制 recommended = 相关度，见前端 35 文档）。
  `searchRanked` / `searchRankedNoLocation` 签名新增 `kwPrefix` 参数（其余 4 变体不加——
  Spring Data 不引用参数会报错，未用参数不得入签名）。
- **联想建议（`GET /venues/suggest`，`VenueController` + `VenueService.listVenueSuggestions`
  + `suggestByName`）**：联想键 = 完整单串（含分隔符返回空——组合搜索走列表接口）；
  匹配 = name 前缀 > name 中缀 > 别名中缀，OPEN 前置、id DESC 兜底；limit 默认 6 /
  收敛 8（`MAX_SUGGEST_SIZE`）。路由与 `GET /venues/{id}` 无冲突（精确路径优先）。
  返回轻量投影 `VenueSuggestResponse{id,name,city,district,status}`（无重型组装）。
- **无坐标缓存交互**：60s `venueListCache` 只服务单串 keyword（filterIds == null）——
  `VenueListKey` 新增 `kwPrefixPattern`（与 keywordPattern 同源同变，ORDER BY 与结果
  一致）；多词白名单结果与请求参数强耦合不入缓存，恒实时查询（`dispatchListQuery`
  filterIds != null 分支绕过 cache）。
- **兼容**：keyword 空/不传 → 全部谓词短路，行为与 v1 完全一致（老客户端零感知）；
  6 方法签名加参已全量适配（dispatch / loader / VenueHotVenueIdsSqlTest）。

---

