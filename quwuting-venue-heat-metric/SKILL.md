---
name: quwuting-venue-heat-metric
description: 去舞厅（quwuting）门店热度/排序指标的提案评估与落地工作流。当需要新增或调整热度公式项、排序权重、热度页统计维度，或评估某个用户行为（分享/门店地址复制/发起导航/页面停留等）能否作为热度依据时使用。覆盖：四问判据筛除伪指标 → 四套 SQL 镜像同步 → 零迁移优先 → native SQL 真库验证 → 验证四件套与文档同步。
agent_created: true
---

# 门店热度/排序指标：提案评估与落地

## 一、先过四问判据（任一不过 → 不进排序层）

用户提的候选指标，先逐条筛。**四问不过就不进 `HEAT_BEHAVIOR`/`HEAT_SCORE`**，最多做展示字段。

| # | 问题 | 不过的后果（均有生产实证） |
|---|---|---|
| 1 | 与既有项**条件独立**吗？ | 只是加权放大器。09-01 收藏「总数×10 + 新增×15」是集合包含关系，一次收藏双计 25 分 |
| 2 | **单位**是什么？有 `(user, venue, day, type)` 唯一键吗？ | 必被刷，且运营自己会刷（crowd report 因此拍板零积分） |
| 3 | 随**曝光量线性增长**吗？ | 复现马太闭环（08-27 线性 PV 的教训：排序→曝光→浏览→排序） |
| 4 | 是**门店需求**信号，还是**产品体验/信息完备度**信号？ | 把产品做得差当成店火 |

**2026-09-19 浏览项改人数口径（现行，勿再按 ln 压缩提案）**：
`浏览贡献 = 近30天去重浏览人数×0.30 + 近7天去重浏览人数×0.40 + ln(1+近30天匿名浏览行数)×1.00`。
旧口径 `ln(1+Σ(来源权重×时效因子))` 实测封顶仅 **6.4 分**（< 1 次收藏的 8 分）——把"有多少人
来看"和"被点开多少次"混在一起压，结果是**排序由最稀疏的信源主导**（332 家「有人看、0 收藏」
的门店在排序中无法体现；111 家有收藏的门店里 89 家恰好 1 人 = λ≈1 泊松噪声）。
**来源权重（LIST 0.5/SEARCH 1.5/SHARE 2.0）随之退出公式**（来源仍采集、趋势图照常展示）。
改后：抖舞 6.3→59、丽莎 6.4→56.4、约翰 4.9→34；热门集合 4→5 家。
**真实收益是抗扰动性**（1 次收藏 8 分不再能改排序，需 ≈20 UV 等效），**不是换榜**。
**未解决**：只改浏览口径不治游戏面（约翰仍 143 第 2，收藏 64 分主导）→ 仍需「人气基数门槛」。

**已裁决的历史结论（勿重复提案）**：
- 分享门店：`ViewSource.SHARE ×2.0`（分享卡片被打开）已在奖励传播；再把 `qwt_venue_shares` 的 SHARE 动作计分 = 发起端 + 接收端**双计**。若要用，必须按 `channel` 分权（TIMELINE 偏推荐 / MENU 偏邀约）并先扣除重复计量。
- 发起导航：`wx.openLocation` 跳出小程序到微信内置地图，**拿不到"是否真的导航/到达"回调**，只能记"点了按钮"，含大量"看看离多远然后放弃"的动作。
- 复制地址/名称：**出口行为**，语义不可判（推荐/质疑/存档），且与"信息不完备"正相关——计分 = 奖励产品缺陷。
- 页面停留：衡量页面体验而非门店需求；小程序伪停留（前后台切换/锁屏/长挂后台）不可采。
- **取消收藏不需要单独进公式**：`favrecent` 带 `f.deleted = false`，而取消走软删 → 自动移出计数 = 自动 −8，净额效应已实现（引入即双计）。

## 二、落地改动清单（四套镜像必须同步）

调整公式项时，权重常量唯一事实源 = `config/VenueHeatWeights`，但**公式本体有四套镜像**：

1. `VenueHeatWeights` — 权重常量与论证注释
2. `VenueRepository.HEAT_BEHAVIOR` — **JPQL/HQL**（列表排序），列用**实体名 + Java 属性名**，枚举用全限定字面量，时间减法带 `day` 单位后缀
3. `VenueRepository.findHotVenueIds` — **native MySQL**（热门判定），用表名，日期用 `DATE_SUB(CURRENT_DATE, INTERVAL n DAY)`
4. `VenueHeatService.computeHeat` — Java 实现 + `formulaText`/`formulaDetail` 文案（前端禁硬编码权重）
   另：`countHeatCounters`（native MySQL）是这些计数器的**取数源**，加新输入项要同步加子查询

**JPQL 硬约束**：无 FROM 派生表能力 → "按用户分组后计数"（如跨天复访）**无法进排序**，进排序须把全部列表主查询 native 化重写（收益/风险比不成立，见 `HEAT_SCORE` 2026-09-02 双算评估结论）。此类指标只能做**展示字段**（`viewCount30d`/`favoriteCount`/`postCount` 既有范式：下发仅供展示、不计入公式）。

**零迁移优先**：新指标优先用已有列/表表达（复访用 `qwt_venue_views(user_id, view_date)`、取消收藏用 V19 `unfavorited_at`、现场人气复用 `GET /venues/{id}/crowd-reports/summary`）——零迁移 = 零 Flyway 风险。确需迁移时走 **`db/migration-mysql`**（先 `ls` 查最大编号；PG `db/migration` 是遗留轨道勿复用）。

## 三、验证（缺一不可）

```bash
# 后端编译
cd quwuting-service && JAVA_HOME=~/.sdkman/candidates/java/25.0.4-oracle \
  ./mvnw -s settings-central.xml -q test-compile

# 前端四件套（tsc + 11 项 check）
cd quwuting && npm run verify

# .js 镜像（精确模式，禁全量重生成）
npm run mirror:js -- pages/venue-heat/venue-heat.ts types/venue.ts
```

**native SQL 必须过真库**（`VenueHeatServiceTest` 是 Mockito 单测，不验 SQL；Spring Data 原生查询只在执行期由 DB 校验）。最小只读验证（凭据见 `quwuting-rds` skill）：

```bash
MYSQL=/usr/local/opt/mysql@8.0/bin/mysql
$MYSQL -h <外网地址> -P 3306 -u qwt_app -p'<密码>' qwt_mysql -t --connect-timeout=15 -e "
SELECT
  (SELECT COUNT(DISTINCT vv.user_id) FROM qwt_venue_views vv
    WHERE vv.venue_id = v.id AND vv.view_date >= DATE_SUB(CURRENT_DATE, INTERVAL 30 DAY)
      AND vv.view_date < DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY)) AS uv30d,
  <新指标子查询> AS new_metric
FROM qwt_venues v WHERE v.deleted = false ORDER BY uv30d DESC LIMIT 6;"
```

**改 native SQL 前先跑两道静态关（2026-09-19 接口 500 的教训，务必照做）**：

1. **括号配平**：改完先自己数一遍（`(` 与 `)` 净差必须 0）。实证事故——给 `findHotVenueIds` 的浏览子查询追加排除谓词时，新写的 `AND (vv.user_id IS NULL OR vv.user_id NOT IN :excludedUserIds)` **吃掉了原本闭合 `(SELECT ...)` 与 `LN(...)` 的两个右括号**，补写时只补回一个 ⇒ 少一个 `)` ⇒ 首页/列表接口整体 500。已加静态门禁 `VenueListQueryHqlSyntaxTest#allQuerySqlTextsHaveBalancedParentheses`（反射读全部 `@Query` 的 value/countQuery，剔字面量后做配平断言，不连库、对 JPQL 与 native 一视同仁）——**改完 SQL 必跑它**。
2. **2. JPQL/HQL 离线解析校验（不连库，补 contextLoads 跑不了时的校验缺口，2026-09-19 建立）**：
本地 `QuwutingServiceApplicationTests`（裸 `@SpringBootTest`）因数据源配置 gitignored **恒失败**
（报 `Failed to determine a suitable driver class`，发生在 flyway/datasource 阶段、早于 HQL 解析）
⇒ **JPQL 改动在本机没有任何启动期校验**。用 jshell 起一个**不连库**的 SessionFactory 即可解析：

```bash
cd quwuting-service
JAVA_HOME=~/.sdkman/candidates/java/25.0.4-oracle \
  ./mvnw -s settings-central.xml -o -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
~/.sdkman/candidates/java/25.0.4-oracle/bin/jshell --class-path "target/classes:$(cat /tmp/cp.txt)" -q <<'EOF'
Configuration cfg = new Configuration();
cfg.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
cfg.setProperty("hibernate.temp.use_jdbc_metadata_defaults", "false");
cfg.addAnnotatedClass(...Venue.class); /* 片段引用到的实体都加上 */
SessionFactory sf = cfg.buildSessionFactory();
sf.createQuery("SELECT v FROM Venue v ORDER BY " + VenueRepository.HEAT_BEHAVIOR);  // 抛错=语法有问题
System.out.println("PARSE-OK-BEHAVIOR");
/exit
EOF
```
要点：① 片段里的别名 `v` 要靠外层 `FROM Venue v` 提供，故包成完整查询再拼片段；
② 实体包路径别猜（`VenuePost` 在 `venuepost.entity`、`TagInteraction` 在 `taginteraction.entity`，
与 `VenueView` 的 `venue.entity` 不同）；③ jshell `-q` 会给每行加 `jshell> ` 前缀，
**别用 `grep -v "^jshell>"` 过滤输出**（会把 System.out 一起滤掉，看起来像"没报错也没成功"）。

**导出验证脚本不要用「按 `+` 切分」解析 Java 字符串拼接**：SQL 文本里本身有 `+`（如 `LN(1 + (SELECT`），粗暴切分会**静默产出错文本**，让你"验证通过"了却什么都没验到（本次正是因此漏掉缺失括号、把 500 放了过去）。正确做法：状态机扫描 Java 表达式，只在**字符串字面量之外**识别标识符并展开常量；文本块起始位置本身就在字面量内，初始状态要设为「在文本内」。

**真库验证的两个替代路径**（DB 门禁测试 `-Drun.db.tests=true` 是 `@SpringBootTest` 全上下文，**对生产库不要跑**——`@Scheduled` 会真实写库）：

1. 手工拼等价 SQL 对 RDS 只读执行（把 `:param` 换成值；**集合参数注意 Spring 会自动补括号**：`IN :positiveCodes` → `IN ('A','B')`），并**至少跑两组参数**（排除集合为空 vs 有名单）看数值是否合理；
2. 本地起 8080 服务打接口冒烟——本次事故正是用户这样做才发现的，**改完立刻本地起一次**比事后读日志便宜得多。

**顺带做数据合理性检查**：新指标与既有指标应满足天然约束（如复访 ≤ UV）、且有区分度（实测 12%~25%）。全 0 或恒等于 UV 都说明口径写错了。

## 四、统计图/统计信息的退位判据

热度页统计图按「**回答哪一类问题**」分层，不按数据好不好看：

- **舞友决策信号**（这家值不值得去）→ 优先位：实时人气（此刻有没有人）、满意度、营业稳定性、反馈趋势
- **流量结构诊断**（用户从哪进来）→ 诊断层：浏览来源四折线图。对"去哪家"零帮助，且"列表进入多"易被误读为店火（实为排序位置的产物）→ **退位候选**
- **被动量级**（被点开多少次）→ 弱信号：PV 受位置偏差污染且含匿名不可去重，08-27 已不进公式 → 让位给"人"的度量（UV/复访）

退位时保留数据与接口（2026-08-28 删礼物价值图先例：删图、数值保留在互动卡、舞伴域保留），并在注释里写明可回退。

## 五、已知游戏面（2026-09-19「约翰（歌友会）」实证，主体已落地）

**核查配方**（任何"某店突然冲到前列"先走这五步，全部只读）：

1. 门店画像：`qwt_venues`（类型 / 建档时间 / `sort_weight` / 有无坐标 / 是否认领）
2. 分项拆解：浏览 ln + 收藏×8 + 反馈×3 + 评分×8。**正向 code 白名单必须用全量（legacy 8 + `EmojiCatalog` 的 51 个 `EMOJI_*`）**——只用 legacy 8 项会低估其他门店、把被查门店"看"得更高
3. 贡献者身份：`JOIN qwt_users` 看注册批次 / role（内部批次特征 = id 连续、注册日集中、默认昵称 `微信用户`）
4. 时间戳聚集：同 `(venue, code)` 下 `created_at` 秒级间隔（实证：两对账号间隔 9 秒）
5. 横向对比：用「热度 ÷ 30天浏览行数」找异常——真实高流量店的比值远低于按钮驱动店

**已确证的结构游戏面**（是结构缺陷，不是动机判定）：

- **反馈「每日一票」被 30 天求和**（✅ 已改去重人数）：唯一键 `(user_id, venue_id, reaction_code, reaction_date)` → 同一人每天可重新投，公式按**行数**累加 = 把"打卡天数"当"人气"。产品语义（当日体验评价、取消 = 硬删当日行）与公式语义（月度求和）错配；单人对单店 30 天理论 90 分、**无人数门槛**。
- **反馈无浏览前置**（❌ 未修，待产品裁决）：列表卡片长按可经页面根 `reaction-picker` 直接投票，**无需进详情页** → 08-27 为破马太闭环把 LIST 浏览降权 0.5，闭环却原样留在按钮上（回报还更高）。实证：夜之缘小酒馆 14 个投票人中 6 人对该店 0 浏览。
- **评分按条目数而非人数**（✅ 已改去重人数）：唯一键 `(user_id, venue_id, tag)` + `RatingDimensions.ALL` 4 维 → 单人一次评价最多 4×8 = 32 分（库中尚有 6 维 = 48 分历史行）。
- **反馈写入无频控**（✅ 已补 12 次/60s；命中后必须读回真实参与态）：`VenueReactionService.toggle` 无 Caffeine 限流（浏览/收藏都有 60s 频控）——三条写路径里唯一裸奔的一条。
- **内部账号未排除**（✅ 已修）：实证 14 个 id ≤ 20 账号（含 ADMIN）贡献全网 **76% 反馈行 / 49% 收藏行** —— 公式最高权重输入的供给方是平台自己人。
- **量纲错配**（未改数值）：主动信号线性、被动浏览 ln 压缩（上限 ≈9）→ 1 次收藏（8 分）≥ 任何门店整月浏览贡献；15 人 6 天的点击（154 分）> 522 次浏览（6.3 分）。
- **城市级门店曝光放大**（✅ 已修，见下方第 4 点）：`SONG_CLUB` 无坐标 → `RADIUS_PREDICATE` 无条件放行（`CITY_ONLY_HQL_IN_LIST`）→ 无锡歌友会出现于全国任意用户默认列表首位。

**历史同根事故**（**每次只调数值、未改结构**）：08-27 浏览线性→ln；09-07 收藏 15→8（火舞山 1 收藏登顶）；09-19 约翰（反馈×天数 + 评分×维度 + 内部账号）。再遇同类事故，先问一句"这次是不是又在给它调数值"。

**2026-09-19 已落地的七条（改数之前必读）**：

1. **主动信号 = 去重人数**（`COUNT(DISTINCT user_id)`）：收藏 / 评分 / 正向反馈改人数口径，每人每店上限回到「收藏 8 + 打分 8 + 反馈 3」。四套镜像同改（`HEAT_BEHAVIOR` / `findHotVenueIds` / `countHeatCounters` / `VenueHeatService` 文案，见 §二）。**字段名不改**（接口契约稳定），文案与类型注释改「N 人」。
2. **内部账号排除**：集合 = 运营配置 `heat.excluded.user.ids`（V31 迁移建键，**默认空 = 不排除任何人**）∪ 哨兵 `-1`；原 `role=ADMIN` 自动排除已按用户决策摘除（见第 7 条）（**恒非空**，否则 `NOT IN ()` 语法错误）；供给方 `HeatAccountExclusionService`（30s 缓存）；经 `:excludedUserIds` 参数注入三处 SQL。**只作用于公式输入**，展示字段（PV/UV/收藏总数/评价总人数/负向反馈）保持原始事实——口径分叉有意，勿「顺手统一」。浏览表 `user_id` 可空 ⇒ 谓词必须写 `(userId IS NULL OR userId NOT IN ...)`，漏掉会让匿名浏览全灭。
3. **判断「只改量纲够不够」必做量化模拟**：约翰 S0 158.9（第 1）→ S1 仅改人数 113.9（**仍第 1**，收藏 ×8 仍主导）→ S2 再排除内部账号 **21.9（中游）**、抖舞 62.3 登顶。**单做一条 = 没解决问题。**
4. **城市级门店可见性**：`SONG_CLUB` 由「无条件放行」改「**所在城市在参考点 300km 内**」。判据挂在 `LIST_FILTERS`（**不是** `RADIUS_PREDICATE`——无坐标分支不含半径谓词，挂那里等于「不开定位就能看到全国歌友会」）；参考点→城市集合由新增 `CityCentroidService` 供给（城市质心**从已有门店坐标派生**，不引入外部地理数据集；**不得**往歌友会塞坐标——写路径主动清空，「存了再藏」不如不存）。两个显式意图出口不受约束：`:keyword IS NOT NULL`（按名搜索）/ `:venueType IS NOT NULL`（按类型筛）。缓存键无需扩（无坐标分支下 `nearbyCities` 是 `city` 的纯函数）。

5. **展示口径跟随时必须"同一屏同口径"**（2026-09-19 补）：改了公式口径，**同屏的趋势图必须跟着改** ——
   ① 全部趋势序列排除内部账号（否则顶部「收藏人数 1」而折线画出内部账号的 7 次收藏，直接打脸）；
   ② 反馈趋势改「当日去重人数」（反馈唯一键含 `reaction_date`，按条数时同一人一周投 6 次 = 6 根柱子）；
   ③ 收藏趋势不用改聚合（唯一键保证逐日条数 ≡ 逐日人数）。
   **不跟的三处（语义不同，别顺手统一）**：详情页 Reaction 徽标计数（= 表情被点多少下）、列表页 chip 计数、热度页「负面反馈 N 条」。
   **已知边界**：趋势按天去重 ⇒ 多天求和 ≠ 顶部 30 天去重人数（人日），前端 note 必须写明。
   **副作用**：内部账号占全网 76% 反馈行 ⇒ 排除后多数门店反馈趋势图接近全空（真相暴露，不是缺陷）。

6. **给某类门店加"空间/邻近约束"时，先确认该功能态会不会传参照物**（2026-09-19 二次修正）：
   歌友会可见性初版把「无坐标 + 无城市」兜底写成「只剩哨兵 ⇒ 不进列表」，上线即被实测推翻——
   **站内热度 / 最新收录是「全网探索排序」，前端 `scopeFree` 时刻意连坐标都不传**
   （为了让公共查询命中后端 `venueListCache` 无坐标视图缓存），于是一个**正常功能态**触发了
   "位置完全未知"分支，歌友会整类消失。而同一请求里普通门店按「全国」展示：
   **同一个列表只藏一个品类 = 标签撒谎**（违反「标签恒等于结果」纪律）。
   ⇒ 修正为 `cityScopeLimited=false` 时不限制：**可见性只在"有参照物"时才收缩**。
   ⇒ 通用判据：**参照物缺失的兜底不能是"藏起来"**（那是对单一品类的歧视性隐藏）；
   要么整体受约束，要么整体不受约束。

**排查"某店突然消失/突然第一"的通用顺序**（本次两个现象都按此定位）：
① 先算**口径**（真库把该店的分项拆开，看它到底多少分、排第几）→ 回答"还第一吗"；
② 再算**可见性**（把列表谓词逐条单独跑一遍，看是哪条把它滤掉）→ 回答"为什么看不到"；
③ 最后看**请求态**（这个排序/场景到底传了哪些参数——`scopeFree`、缓存分支常有意不传坐标/半径）。
**别跳步**：直接猜"是不是可见性谓词"会漏掉"其实只是分数没降下来"这类答案。

7. **排除集合当前为空 = 不排除任何人（2026-09-19 用户决策「先不要做任何排除」）**：
   ADMIN 自动排除分支已摘除（`HeatAccountExclusionService` 不再查 `role=ADMIN`），
   只保留运营配置名单 `heat.excluded.user.ids` 作为唯一通道（默认空）。
   恢复 = `loadExcludedUserIds()` 里加回一行 `findIdsByRoleAndDeletedFalse(UserRole.ADMIN)`
   （该方法保留未删，注释里写了恢复方式）。
   **⚠️ 不排除时的效果边界（勿当成已修复）**：只做去重人数口径，约翰 158.9 → **113.9 仍是
   全国第 1**，抖舞 150.3 → 111.3 —— **相对次序不变**。因为"人数"只收紧了「同一人重复点击」，
   没收紧「这个人是谁」（约翰 15 个反馈人里 13 个是内部账号）。
   ⇒ 想让按钮驱动的店掉下来，只有两条路：**填名单**，或 **P1 结构改造**（人气基数门槛
   `主动信号 ≤ a + b·ln(1+UV30d)` / 主动信号 ln 压缩）—— 后者不依赖人肉名单。

**仍未裁决（勿自行落地）**：反馈前置校验、主动信号压缩 + 人气基数门槛、同源聚集折扣、新号减折、榜单分层（人气榜 / 口碑榜）。完整论证与量化模拟见工作区 `quwuting-heat-gaming-analysis-2026-09-19.md`。

**验证注意（重要）**：`VenueHotVenueIdsSqlTest` 等 DB 门禁测试是 `@SpringBootTest` **全上下文**——**对生产库不要跑**（`@Scheduled` 任务会真实写生产）。native SQL 改动改为「手工拼等价 SQL 对 RDS 只读执行」验证：本轮即用此法核对 ① 去重人数口径（哨兵组 vs 排除名单组两组数值）② 歌友会可见性四场景（仅哨兵 / 含城市 / 有关键词 / 有类型筛）。

## 六、纪律

- **生产禁动**：只落本地，不部署 / 不 push / 不重启。RDS 只读查询可用于验证。
- 文档同步：后端 `docs/agents/05-venue-heat.md`（公式/口径/复核结论）+ 前端 `AGENTS.md` 11 号索引行。
- **核查先于断言**：提"某信号已采未用"之前，先读该信号的 SQL 口径——软删/窗口条件常已隐含期望语义（本次"取消收藏"即因此修正）。
