# 35 — 运营数据看板（Dashboard，2026-09-06）

> 维护警告：本文档记录「运营大盘」能力的口径契约（按日统计接口 + admin-web Dashboard
> 页 + 噪音识别）。新增/修改统计口径先更新本文档；AGENTS.md 索引表保持一行摘要。
> 口径全部来自 2026-09-06 生产库真实画像分析（本机 /tmp 分析导出已清理，结论沉淀于此）。

## 背景与定位

微信后台只能看「访问量」口径（含未登录游客、含审核/巡检流量），而数据库才有
「登录建号 + 真实行为」的完整事实。运营长期缺少一张「真实盘子」的图：
- 累计用户数：微信后台 672 vs DB 注册 295——差量 = 游客浏览（venue_views 中
  `user_id IS NULL` 1017 条 / dancer_views 1059 条），微信统计含游客、DB 只记登录建号。
- 活跃失真：**打卡 = 登录自动触发**（app.ts onLaunch → autoCheckIn），「打卡+积分」
  只代表当天打开过，不代表真实使用。看活跃必须区分「打开（打卡口径）」与「真实互动」。
- 噪音流量：2026-09-03 起「打卡型注册」（注册后从未真实互动）占比骤升 60%+
  （9-4 注册 36 人中 24 人），且深夜 23:07-23:48 均匀注册 16 人、全天每小时 1 人——
  非真人作息，疑似微信审核/自动巡检（9-1 舞伴驳回、近期反复提审期间尤为明显）。

**设计判断**：管理后台补一块「运营数据看板」，把三线趋势（注册 / 打开 / 真实互动）
+ 噪音占比一次讲清。管理面在 **Web 管理后台（quwuting-admin-web）Dashboard 页**，
小程序端不加（小程序管理页只服务单条运营动作，大盘属 Web 后台职责）。

## 决策记录（2026-09-06 用户拍板 P0）

| 决策点 | 结论 |
|--------|------|
| P0 落地范围 | **30 天趋势图（注册/打开/真实互动三线）+ 打卡型噪音占比**，先看清真实盘子 |
| 图表载体 | admin-web 新增 Dashboard 页（echarts 5.5 按需注册），登录后默认落地页 |
| 后端形态 | `GET /admin/users/daily-stats?days=30`（钳制 7~90），一次 DB 往返回全部序列 |
| 噪音识别口径 | 「当日注册且注册后从未在任何互动表出现」= 打卡型噪音；占比 = noisy/registered |

### 追加决策（2026-09-15，根因修复 + 留存分析）

| 决策点 | 结论 |
|--------|------|
| 口径载体 | 下沉为编译期常量 `UserStatsSql`（用户范围谓词 + 活跃事实集 + 痕迹全集），消费方只引用不重写；门禁 `UserStatsSqlMirrorTest` |
| 「活跃」定义 | **唯一** = 用户主动行为事实集（12 表）；登录自动打卡只是「打开」序列，**禁止**作为活跃/留存指标 |
| 「最近露面」 | 四源 MAX（含自动打卡）保留，但语义与命名改为「最近露面」，只服务用户列表排序/展示，不得占用「活跃」之名 |
| 事实口径 | 动作发生过（行存在）取代「当前仍有效」（不筛 `deleted` / `unfavorited_at`）——保证历史不被事后改写 |
| 留存实现 | 新增 `GET /admin/users/retention`，走现表回溯（无需日级快照），未到期格返回 null |
| 图表载体 | admin-web 新增 `/retention` 页（自包含加载），Dashboard 趋势卡头 + 设置页登记入口 |

## 口径定义（单一权威 = `UserBehaviorEvent` 目录 + `UserStatsSql`，2026-09-15 起；禁止散落再定义）

> **代码载体 = `user/repository/UserBehaviorEvent.java`（行为事件目录，声明处）+
> `UserStatsSql.java`（日级事实集）/ `UserBehaviorSql.java`（事件级事实集）**。
> 后两者是**目录的生成物**（门禁逐字校验），本节与它们是同一份契约的两面：
> 加/删行为表 → **只改目录** → 跑门禁 → 按失败信息替换字面量 → 同步本表。
> 任何统计仓库不得再逐字抄写谓词 / 事实集。

| 序列 | 定义 | 数据源 |
|------|------|--------|
| 真实用户（全部分母） | `UserStatsSql.USER_SCOPE`：`deleted=false` + `role='USER'` + open_id 非 `test_` 前缀 + `wechat_review=false` | qwt_users |
| 注册数 | 当日 `created_at` 落当日的真实用户 | qwt_users |
| 打开数（打卡） | 当日 `qwt_daily_checkins` 去重用户（**登录后自动触发**，只代表打开过） | qwt_daily_checkins |
| 有效活跃数 | 当日出现在 `UserStatsSql.ACTIVE_FACT_UNION` 的去重用户（12 表**用户主动行为**） | 12 表 UNION ALL |
| 打卡型噪音 | 当日注册的真实用户中「注册后从未有任何痕迹」的人数（`TRACE_FACT_UNION` NOT EXISTS，含被动痕迹） | 痕迹全集 NOT EXISTS |
| 事件级活跃事实 | `UserBehaviorSql.ACTIVE_EVENT_FACT_UNION`（同一 12 表，多带 `event_type`/`event_time`）——活跃时段 / 活跃天数 / 行为宽度 / 类型分布 | 12 表 UNION ALL |
| 事件级全档事实 | `UserBehaviorSql.EVENT_FACT_UNION`（18 个事件，含打开/协作/被动，口径由 `Nature` 标注）——轨迹与类型分布的完整性来源 | 18 表 UNION ALL |
| 轨迹事实 | `UserBehaviorSql.EVENT_DETAIL_UNION`（全档 + `ref_id` 关联对象 + `detail_text` 明细，**禁带敏感列**） | 18 表 UNION ALL |

**事实口径 = 动作发生过（行存在），不是「当前仍有效」**（2026-09-15 修正）：事实集内不再
筛 `deleted=false` / `unfavorited_at IS NULL`。理由有二：①取消收藏会写
`qwt_favorites.deleted=true` + `unfavorited_at`，按状态口径过滤则用户今天取消收藏会让他
8 月的互动凭空消失、批次留存被事后改写（事件不可变是留存分析的前提）；②
`qwt_venue_status_reports` 被采纳即置 `deleted=true`，按状态口径过滤会把「唯一动作是被采纳的
上报」的老用户误判成审核流量。

**刻意排除（扩展位，纳入需立项评审 + 同步本节）**：消费账本 `qwt_spend_entries` 与快讯表态
`qwt_bulletin_reactions` / 快讯浏览 `qwt_bulletin_views` 属独立子域；站内信 / 状态上报回执 /
招工联系 / 公告已读属被动或弱意图信号，只在噪音判定的「有痕迹」里使用（`PASSIVE_TRACE_FACT_UNION`）。

## 口径单一事实源（2026-09-15 根因修复）

**表象**：同屏出现自相矛盾的两个数字——趋势图注明「『打开』= 登录自动打卡（仅代表当天打开）」
「不代表真实使用」，而顶卡「近 7 日活跃」用的正是「四源 MAX（资料更新/积分流水/邀约/**每日打卡**）」
⇒ 顶卡实义是「近 7 日打开过的号」，把审核/巡检/打卡型噪音全算成活跃；同时「累计注册」含
ADMIN 运营号与 `test_` 开发号，与趋势线（剔 ADMIN/test_）不同分母，用户对不上账；
「真实互动」序列又只剔审核号未剔 ADMIN/test_。

**根因（不是某一行 SQL 写错）**：缺少「用户统计口径」的**所有权与命名契约**——同一名词「活跃」
存在两套互不知晓的定义却在同一页面并列展示；口径以**文本抄写**形式散落在 3 个仓库
（`UserDailyStatsRepository` 2 处、`SpendStatsRepository` 5 处、`UserRepository` 1 处自拼），
任何一处修订都会漂移，**单点修补必然复发**。

**修复（结构性）**：
1. 口径下沉为编译期常量 `UserStatsSql`（用户范围谓词 + 活跃事实集 + 痕迹全集），消费方只能引用；
   现有 4 个统计仓库、公告触达分母全部改走常量（SQL 逐字等价或按上述修正收敛）。
2. 零依赖门禁 `UserStatsSqlMirrorTest`（8 条）：锁定 12 张主动行为表 / 4 张被动痕迹表成员完整、
   **活跃事实集不得含打卡与状态条件**、`countActiveUsers` 不得回退成 `GREATEST(四源)`、
   留存查询不得触碰打卡、各统计 @Query 必须引用常量。
3. **命名契约（强制）**：「活跃」在管理端统计中**专指** `ACTIVE_FACT_UNION`（用户主动行为）；
   `lastActiveAt` / `LAST_ACTIVE_DESC` 的语义是「**最近露面**」（四源 MAX，含自动打卡），
   只用于用户列表的排序与「最后露面」展示，**不得**作为活跃/留存指标，展示文案写「最近露面」
   （`UserSortMode` 枚举名因已对外保留）。

**口径修正带来的数字变化（预期，非故障）**：顶卡「累计注册/今日新增」略降（剔除 ADMIN/test_ 号，
约 10 个账号）；「近 7 日活跃」明显下降（不再把自动打卡算作活跃）；公告触达率上升（分母同样
变小）。三处都以本节的统一口径为准。

## 后端实现（quwuting-service）

- `user/repository/UserStatsSql.java`：**口径单一事实源**（用户范围谓词 + 活跃事实集 + 痕迹全集）
- `user/repository/UserDailyStatsRepository.java`：`countDailyStats(sinceDay)` MySQL 8 方言
  mega-query（WITH RECURSIVE 骨架补零 + 4 组 LEFT JOIN，含噪音 NOT EXISTS 子查询），
  全部引用 `UserStatsSql` 常量。**勿在 PG 环境执行**（本地联调需连 application-mysql.yaml 的 RDS）。
- `user/repository/UserRetentionRepository.java`：留存统计仓（批次规模 / 批次×偏移 / 逐日新老拆分 /
  近 7 日汇总，4 条同窗口聚合），口径同上。
- `user/service/AdminDailyStatsService.java`：窗口钳制 7~90，LocalDate 现算 since，空值兜底 0。
- `user/service/AdminUserRetentionService.java`：留存纯算术派生（比率不下发、未到期置 null）。
- `user/dto/response/AdminDailyStatItem.java` / `AdminUserRetentionResponse.java`。
- `user/controller/AdminUserController.java`：`GET /admin/users/daily-stats?days=30` +
  `GET /admin/users/retention?days=30`，均 `UserContext.requireAdmin()`。
- 门禁：`src/test/.../UserStatsSqlMirrorTest`（零依赖，`./mvnw -Dtest=UserStatsSqlMirrorTest test`）。

## 前端实现（quwuting-admin-web）

- `services/dailyStats.ts`：`getDailyStats(days)` + `getUserStats()`（顶部大盘卡）。
- `services/userRetention.ts`：`getUserRetention(days)` + 矩阵列定义/格式 helper
  （`RETENTION_OFFSETS` / `cohortCell` / `retentionRateText`；未到期渲染「—」）。
- `views/DashboardView.vue`：notice-bar 口径说明（2026-09-15 更新为「统一分母 + 打开 ≠ 活跃」）
  + 4 顶卡（累计注册/今日新增/近7日活跃(主动行为)/管理员）+ 30 天三线趋势（echarts 按需：
  Line+Bar，注册蓝 378ADD / 打开灰虚线 B4B2A9 / 有效活跃红 E24B4A）+ 噪音占比柱
  （≥60% 红 / ≥40% 橙 / 其余蓝）+ 噪音文字注记（今日占比 + 30 天峰值日，提示对照提审日期）
  + 趋势卡头「留存分析 →」下钻入口。
- `views/RetentionView.vue`（2026-09-15）：留存一屏——顶卡 4 枚（有效用户 / 近7日活跃 /
  近7日回访(含占活跃比) / 次日留存）+ 活跃趋势（老用户·新增堆叠柱 + 老用户占比线）+
  留存曲线（D1 起，未到期不出点）+ 批次留存矩阵（最新批次在前；只渲染有到期值的档位列，
  未到期「—」与 0% 在视觉上明确区分）。
- `router/index.ts`：`/dashboard` + `/retention` 路由，`/` 与登录成功 redirect 到 dashboard。
- `views/MoreView.vue`：MODULES 登记「留存分析」（新增模块只登记此处，不进 tabbar）。
- `layouts/AppLayout.vue`：TABS = 数据看板 / 公告管理 / 更多；`tabKeyOf` 只认 tabbar 占有的
  路由（`/retention` 属「更多」模块，不抢高亮）。
- **禁假数据红线**：全部走 `/admin/users/daily-stats` + `/admin/users/stats` +
  `/admin/users/retention` 真实接口（dev:mock 无这些页的 mock，联真实后端用 `npm run dev`）。

## 用户留存分析（2026-09-15）

**定位**：回答运营真正的问题——「**老用户还在不在**」。大盘只有日总量三线，新人与存量混在
一条线上：早期注册量大时「看着在涨」，而存量流失被新增掩盖（本文档「数据现状快照」记录的
「8-31 注册 53 → 后续真实互动仅 8」就藏在库里看不出来）。

- **接口**：`GET /admin/users/retention?days=30`（钳制 7~90，仅 ADMIN；挂在 `/admin/**` 下，
  零反代改动）。一次 HTTP 往返返回 summary + daily + curve + cohorts 四块
  （4 条同参同窗口聚合，见 `UserRetentionRepository`；拆分而不做 mega-query 的理由见其类注释）。
- **口径**：活跃 = `UserStatsSql.ACTIVE_FACT_UNION`（**不含登录自动打卡**）；有效用户 =
  `USER_SCOPE`；批次 = 注册日；**Dk 留存 = 注册后第 k 天当天有活跃**（经典当日留存，
  非「k 天及以后」）；注册当日不计入留存（D0 恒 1，无信息量）。
- **「未到期」是 null 不是 0**（本能力最重要的口径决策）：`cohortDay + k > today` 的格子返回
  `null`，前端渲染「—」。把未到期当 0% 会让最近几个批次在图上人为「崩盘」，是留存报表最经典
  的错误决策来源。留存曲线的分母同样只累计**已到期**批次（`retained / base` 加权），
  无已到期批次的偏移**不出点**（宁缺勿造 0）。
- **比率不下发**：服务端只给原始计数与「是否存在该观测」，留存率/占比由前端派生
  （与 `SpendUsagePanel` 渗透率先例一致）。
- **批次矩阵档位** = D1/D3/D7/D14/D30（`RETENTION_OFFSETS`，前后端对齐）；窗口 ≤30 天时
  D30 通常整列未到期，前端按数据自动隐藏该列。
- **可交叉验算**：`daily` 逐日老用户活跃的跨日去重上限 ≥ `summary.returningUsers7d`；
  `summary.activeUsers7d` 与看板顶卡「近7日活跃」同口径同窗口（服务端现算的 7 天）。

### 口径自证 + 空值协议（2026-09-15 二轮修复）

两件事都是「不可见约定 → 可见证据 / 显式协议」的收敛：

1. **口径自证（账号盘子漏斗）**：`GET /admin/users/retention` 增 `scopeAudit`
   （`totalAccounts` / `opsExcluded` / `reviewExcluded`，**三项互斥**：审核号中属运营/开发号的
   归入 `opsExcluded`）⇒ 页面上直接渲染一行可当场验算的等式
   `全部账号 = 有效用户 + 运营/开发号 + 微信审核号`。
   新增 `UserRetentionRepository.sumScopeAudit()`（**刻意不引用 `USER_SCOPE`**，它统计的正是被
   排除的那部分），谓词引用新增常量 `UserStatsSql.OPS_ACCOUNT_PREDICATE` /
   `REVIEW_ACCOUNT_PREDICATE`（与 `USER_SCOPE` 是同一套规则的正反两面），门禁
   `UserStatsSqlMirrorTest#scopeAuditStaysComplementOfUserScope` 锁定不漂移。
   **判据**：「已剔除管理员与审核号」以前只写在注释里，运营无法自证；现在它是页面上的一行加法。
2. **空值必须显式序列化（NaN% 根因）**：全局配置
   `spring.jackson.default-property-inclusion: non_null` 会把 **null 字段整个从 JSON 删掉**，
   而「未到期 = null」是留存响应唯一的空值语义 ⇒ 前端读到 `undefined`，`undefined / size * 100`
   = `NaN`，矩阵显示「NaN%」（且与「0% 无人回访」无法区分）。修复两侧同时做：
   - 后端：`AdminUserRetentionResponse.CohortItem` 标 `@JsonInclude(Include.ALWAYS)`，
     让 null 成为**协议的一部分**（该注解与类注释里的警告**勿删**）；
   - 前端：`userRetention.ts#cohortCell` 把「非有限数值」一律归一到 `null`（未到期），
     `retentionRateText` 再加一道非有限守卫 ⇒ 即使后端未升级，也只会显示「—」，绝不出现 NaN%。
   - **推广判据**：本仓任何 DTO 里带 `null` 语义的字段（「无此观测」≠「值为 0」）都必须如此处理；
     仅用 JS 假值判断（`if (!x)`）的旧页面能容忍字段缺失，但**严格比较 `=== null` 必然踩这个坑**。

## 数据现状快照（2026-09-06 分析，供对照验证）

- 累计 DB 注册 295（剔 10 噪音号 ≈ 285）；微信累计 672（含游客，口径不同勿直接比）。
- 真实互动 DAU：8-25 峰值 35 → 9-4 仅 21 / 9-5 12（剔除噪音号口径）。
- 打卡型噪音：8-31 前 <10%，9-3 起 60%+（9-4 24/36、9-5 16/23）。
- 留存极差：8-31 注册 53 → 后续真实互动仅 8；9-1 注册 27 → 0。微信「新增日留存 0%」与 DB 吻合。
- 早期流量主靠 uid2（last night's stars）自分享卡片（share_from=2 占分享打开绝对多数），
  真实裂变少；8-31 后游客浏览骤降（<22/日）。

## 微信审核账号标记（2026-09-09 V17，统计去噪落地）

上节「噪音号进用户管理标记」规划的落地实现。**语义 = 统计去噪不是处罚**：
不删除账号、不影响小程序端任何功能，只从管理端统计口径排除。

- **标记载体**：`qwt_users.wechat_review BOOLEAN NOT NULL DEFAULT FALSE`
  （V17 迁移，含存量名单预标记：id 2/4/6/10/13/14 = 用户点名的
  TO / last night's stars + 生产审计「上报>2」的审核号（34/17/13/3/3 条，
  「照片有误」型为主）；id=51 可爱大宝经用户确认豁免）。
- **管理端操作**：`POST /admin/users/{id}/wechat-review`，body `{"marked": true|false}`，
  幂等（AdminUserService.setWechatReview）；admin-web 用户详情页「运营标记」卡
  切换 + 用户列表行「微信审核」warning tag。
- **统计口径排除范围**（全部走 `wechat_review=false` 过滤）：
  1. 用户统计条（`/admin/users/stats` 四项：总数/今日新增/管理员/近7日活跃）；
  2. 大盘按日趋势四序列（注册/打开/互动在子查询过滤；打卡 NOT IN 回查用户表）；
  3. 公告触达分母（AnnouncementService.stats 的 totalUsers/readRate）。
  用户列表/详情本身**不排除**（保留可见性才能手动管理标记）。
- **上报明细列表**（/admin/reports 等）保留原样——历史留痕，只动聚合统计。

## 计时器 & 计时账本使用统计（2026-09-14）

新功能使用盘子的管理面落地。**设计判断：计时器的云端痕迹唯一 = 结算自动入账**
（`qwt_spend_entries.source='DANCE'`，计时明细本体只落客户端 storage，
qwt_dance_records_v1），账目表即使用事实表——**禁为统计新建第二套数据源/上报通道**。

- **接口**：`GET /admin/spend/usage-stats?days=30`（钳制 7~90，仅 ADMIN，
  `UserContext.requireAdmin()`；挂在 `/admin/**` 白名单前缀下，零反代改动）。
  一次往返返回 summary + daily + byCategory + byVenue 四块。
- **口径（与大盘完全同族）**：全部聚合 `JOIN qwt_users` 过滤
  `deleted=false AND role='USER' AND open_id NOT LIKE 'test\_%' AND wechat_review=false`
  ——剔除 ADMIN 运营号 / test_ 开发联调号 / 微信审核账号；软删账目不入任何计数。
  支出分类分布走 `direction='EXPENSE'`（同小程序统计页「消费分析」口径，
  GUEST 收入向不入图，收入在汇总行体现）；门店 TOP = `venue_id IS NOT NULL`
  按账目笔数降序（金额进 tooltip），venue_name 快照取 MAX 规避多快照分裂。
- **MySQL 8 方言**（WITH RECURSIVE 骨架补零，PG 环境勿执行，同上节）。
- **后端文件**：`spend/repository/SpendStatsRepository.java`（口径唯一权威，
  独立只读仓库，参考 UserDailyStatsRepository 先例）+
  `spend/service/AdminSpendStatsService.java` + `spend/controller/AdminSpendStatsController.java`
  + `spend/dto/response/AdminSpendUsageStatsResponse.java`。
- **前端文件（admin-web）**：`services/spendStats.ts`（零派生只搬运）+
  `components/SpendUsagePanel.vue`（自包含面板：顶卡 4 枚 = 记账用户/近7日活跃/
  计时场次/计时用户 + 趋势图「计时结算 vs 手动补记堆叠柱 + 活跃记账用户线」+
  支出分类分布 + 门店 TOP；echarts 按需注册同 Dashboard），
  挂载于 `DashboardView.vue`——**独立加载、不挂在大盘 v-else 内**
  （大盘自身加载失败不影响本面板）。
- **顶卡派生口径**：渗透率 = 记账用户/累计注册（前端派生，注册数 prop 传入，
  做除法前判零）；计时场次占比 = danceEntries/totalEntries；计时用户占比 =
  timerUsers/totalUsers。金额注记「累计支出/收入」只在有账目时显示。
- **用户下钻（2026-09-14 二轮）**：面板头「记账用户 →」+ 用户详情「计时 · 账本」卡。
  - `GET /admin/spend/usage-users`：有账目的真实用户按最近记账降序 + 逐用户聚合
    （昵称/头像随行），行点击**复用资料协作 user-detail 路由**（/users/{id}）。
    admin-web 新页 `views/SpendUsersView.vue`（router `/spend-users`）。
  - `GET /admin/spend/users/{userId}/entries?limit=50`（钳制 10~200）：用户详情
    「计时 · 账本」卡数据源——summary 全量汇总 + 最近流水（**只回未软删**，与
    统计口径一致）。`UserDetailView.vue` 卡片：meta 行（笔数/计时场次/收支）+
    流水行（分类 + 来源 tag 计时/手动 + 门店/时长/时间 + 金额，收入绿 `+` 前缀），
    非阻塞加载失败静默（同资料协作卡口径）。
  - **明细端点不做用户表口径过滤**（指定用户读取，入口列表已过滤；
    用户详情本身保留可见性——与 2026-09-09「列表不排除」判据同族）。

## 用户行为轨迹与行为分析（2026-09-15，资料协作域扩展）

### 定位

运营要「深挖单个用户的行为」，此前只能看**六个按维度分别手写的下钻列表**（积分 / 上报采纳 /
打卡 / 认领 / 分享 / 上报），既没有一处能把「这个账号做过什么」按时间讲清楚，也没有平台级的
「行为类型 / 节奏 / 分层」视图。本轮在**资料协作（用户）域**扩展三块能力：

- 平台级**行为分析页**（`/users/behavior-analysis`，入口 = 资料协作页顶部「行为分析 →」）；
- 用户详情**「行为统计」卡**（单账号窗口内的行为画像）；
- 用户详情**「行为轨迹」卡**（把 18 个事件源合并成一条可读时间线）。

### 根因（为什么动结构，而不是加三条 SQL）

**表象** = 缺一个「行为轨迹」页面。**根因 = 「行为事实」从来不是一个被声明的东西。**

2026-09-15 之前，行为事实只以 **SQL 文本**形式存在：`UserStatsSql.ACTIVE_FACT_UNION` 是一条
手写的 12 表 `UNION ALL` 串、`PASSIVE_TRACE_FACT_UNION` 是另一条手写的 4 表串——它们只声明了
「表名 + 列名」，**没有任何地方声明「这张表代表什么事件、属于哪一类、算不算活跃」**。后果是结构性的
（不是某一处写错）：

1. **每加一个消费方都要再抄一遍**：做轨迹 / 类型分布 / 活跃时段时，只能把表清单与列名再抄一份写进新
   SQL。抄写漂移是纯文本层面的不同步——编译器、HQL 语法测试、启动校验全都发现不了
   （同类事故已发生过：口径在 3 个仓库抄了 8 处，见本节上文「口径单一事实源」）；
2. **新行为表没有落点**：「站内信 / 快讯表态 / 消费账本要不要算活跃」只能靠人记、靠注释约束，
   没有任何地方能回答「哪些事件算活跃、为什么」；
3. **跨表叙事无法表达**：把 12 张表按时间合并成「一条轨迹」，在旧结构下只能写成第 N 份手抄的
   mega-query，且无法证明它与活跃口径同源。

### 修复（结构性）

1. **行为事件目录 `UserBehaviorEvent`（编译期声明，唯一事实源）**：每个事件声明
   表 / 时间列 / 业务日列 / **口径档 Nature** / 分类 / 中文标签 / 关联对象类型 / 明细列与渲染字典。
   四档分工（**「算不算活跃」在这里裁决，别处不再判断**）：

   | 档位 | 成员 | 含义 | 是否计入「活跃」 |
   |---|---|---|---|
   | `ACTIVE` | 12 | 用户主动发起的有意义行为 | **是（唯一含义）** |
   | `COLLAB` | 1 | 认领门店（主动但属资料协作工作流） | 否（纳入需立项，会改历史数字） |
   | `SIGNAL` | 1 | 登录自动打卡 = 「打开」 | **永不**（把它当活跃正是 09-15 修掉的那个错误决策） |
   | `PASSIVE` | 4 | 站内信 / 暂停上报 / 招工联系 / 公告已读 | 否（只在「有痕迹」判定里用） |

2. **SQL 由目录生成**：`activeFactUnion()` / `passiveTraceFactUnion()` / `allEventFactUnion()` /
   `activeEventFactUnion()` / `eventDetailUnion()` 五个生成器。
   ⚠️ **Java 硬约束（勿试图"改进"）**：事实集片段要写进 `@Query(...)`，而**注解值必须是编译期常量**
   ⇒ 方法调用进不了注解，也无法在运行时把片段拼给注解。故 SQL 仍以**字面量**承载
   （`UserStatsSql` / `UserBehaviorSql`），由门禁断言「字面量 `equals` 生成器输出」——
   等价于给生成物配了**校验和**：只改目录（漏改字面量）或只改字面量（漏改目录）都会立刻红，
   失败信息直接给出期望文本。**这不是退步**：抄写被结构性消灭——不是「有人记得同步」，
   而是「不同步过不了门禁」。

3. **事件级事实集 `UserBehaviorSql`**：`(user_id, event_type, event_day, event_time[, ref_id, detail_text])`。
   与日级事实集（`UserStatsSql`）的分工：前者回答「做了什么 / 对谁做 / 什么时候做」，
   后者只回答「这天活跃没活跃」。**两条日级事实集文本逐字未变**（目录生成的文本与手写版完全一致）
   ⇒ 看板 / 留存 / 账本的既有数字**零变化**。

4. **名称与字典单点化**：新增 `BehaviorRefNameResolver`（关联对象名批量解析 + 明细列渲染字典：
   分享渠道 / 门店上报类型 / 暂停报原因）。`AdminUserStatsDetailService` 的私有 venue/dancer 批量查询
   与三个字典方法**一并删除并委托给它**——此前同一份渠道字典在两处各写一份，改动只落一处就会让两个页面
   对同一份数据给出不同文案（无法被编译器或测试发现，只能靠「只有一个实现」根除）。

5. **门禁 `UserBehaviorCatalogMirrorTest`（10 条，零依赖）**：5 条事实集字面量 ⟺ 生成器逐字相等、
   目录四档规模锁定（12/4/1/1，无声增删即红）、事件码与表唯一、轨迹覆盖全目录、
   每分支「权威日 + 时刻 + 窗口下界」齐备、**轨迹禁带敏感列**（手机号 / 真实姓名 / 微信号 / openId）、
   消费方必须引用事实集常量、**平台级查询必须带 `USER_SCOPE` 而单用户查询必须不带**、
   **目录声明列必须真实存在于迁移脚本 DDL**、**派生表列引用必须解析得到**（后两条为 2026-09-15
   事故后补，见下）。

5.1 **事故与结构性补丁（2026-09-15 晚，轨迹接口 500 —— 同一条 SQL 上的两个独立缺陷）**：

**缺陷一：目录声明的列在库里不存在**（`Unknown column 'reason' in 'field list'`）

- **根因**：行为目录 `UserBehaviorEvent.STATUS_REPORT` 的 `detailColumn` 声明为 `reason`，
  而该列**早在 V11 泛化时就被换成 `type`**（V11 同迁移内 `ADD COLUMN type` +
  末段 `DROP COLUMN reason`，8 类突发事件的类型维度取代了「暂停营业专用原因」维度）。
  `reason` 是从旧手写 SQL 抄进目录的**残留列名**——目录建立时照抄的正是那份早已与 DDL 分叉的文本。
- **为什么五类判据一条都没拦住**：前 5 条全部作用在**文本层**（目录 ⟺ 字面量），
  而错列名在文本层是完全**自洽**的（目录写 `reason`、字面量也写 `reason`，逐字相等）
  ⇒ 编译绿、启动校验绿、门禁绿，直到运营点开页面才 500。**文本一致性 ≠ 与库结构一致**。
- **补丁（第 6 类判据 `catalogColumnsExistInSchema`）**：把 `db/migration-mysql` 当**唯一事实源**，
  零依赖解析 DDL（`CREATE TABLE` 列定义 + 按 `V<序号>` 自然序累积
  `ALTER TABLE ADD|DROP|RENAME|CHANGE COLUMN`），逐列断言目录声明
  （表 / 日列 / 关联列 / 明细列 / 时间列）确实存在。**负向验证已做**：把 `reason` 种回目录
  **并同步字面量**（模拟事故真实形态）后，只有本条变红——即该错误此前确实处于
  「全部门禁看不见」的盲区，现已封闭。
- **修复**：目录 `detailColumn` `reason` → `type`，`UserBehaviorSql.EVENT_DETAIL_UNION`
  的 `CAST(reason AS CHAR)` → `CAST(type AS CHAR)`（两处必须同轮改，否则逐字等价门禁红）。

**缺陷二：`ORDER BY` 用表前缀引用投影别名**（`Unknown column 'e.eventType' in 'order clause'`，1054）

- **暴露过程**：缺陷一修好并重启后**立刻**报出这一条——`reason` 在 field list 阶段先炸，
  把这个错误挡在后面。**「修到第一处报错为止」的验证深度不足以宣布修好**。
- **根因**：派生表 `e` 的列名取自内层 SELECT 的**列名**（事实集常量里是下划线风格的
  `event_type` / `event_day` / …），而 `e.event_type AS eventType` 里的 `eventType` 只是
  **本层投影别名**。带表前缀的引用（`e.eventType`）只解析派生表真实列名、**不参与** select
  别名的解析；同理 `happenedAt` 能出现在 `ORDER BY` 里正因为它**不带前缀**。
- **补丁（第 7 类判据 `aliasReferencesResolveToFactColumns`）**：反射取本仓全部 `@Query`
  的**运行时拼接后文本**，解析事实集第一分支的产出列名，断言每条 `e.<列>` 引用都落在该集合内。
  **负向验证已做**：把 `e.eventType` 种回去，10 条中只有本条变红，失败信息直接给出产出列集合。
- **横向排查（已完成）**：脚本扫全仓 104 条原生 `@Query`，命中的 12 处 `别名.驼峰` 引用逐条核实，
  **仅此一处非法**——另三处（`SpendStatsRepository` 的 `s.danceEntries`、`UserRetentionRepository`
  的 `c.cohortDay` / `s.newActive`）之所以合法，是因为它们的内层子查询**显式写了驼峰别名**
  （`AS cohortDay`）。**判据边界：合法与否取决于「内层是否真的产出了这个名字」**。
- **修复**：`ORDER BY happenedAt DESC, e.eventType` → `e.event_type`。
- ⚠️ **真机留意点**：该 UNION 的字符串列写作 `CAST(x AS CHAR)`（不带长度），MySQL 的分支类型
  聚合理论上会取最宽分支，但请在上线时确认 `detailText` 未被截断（静态门禁覆盖不到类型推断）。

两条补丁的**共同点**：都是「文本层自洽、外部世界不认」——一条与 DB 结构不一致，一条与 SQL
名字解析规则不一致；**编译器与逐字等价门禁对这两类问题都是盲的**，必须各自设一条「读外部事实源
或解析语义」的判据。


### 口径（与看板 / 留存严格同源）

| 指标 | 定义 |
|---|---|
| 活跃（事件级） | `UserBehaviorSql.ACTIVE_EVENT_FACT_UNION`（12 表主动行为），**不含登录自动打卡** |
| 打开 | `Nature#SIGNAL` = 打卡去重天数；单独成字段并排展示，**永不并入活跃** |
| 活跃天数 | 窗口内主动行为的**去重自然日**（跨事件类型去重）。**不可由各类型天数相加**——相加会把同一天做过两类事件的人重复计入 |
| 活跃时段 | 只统计 `event_time IS NOT NULL` 的主动行为；缺失条数用 `timedOutActiveEvents` 显式交代（宁缺勿造：折叠到 0 点会造出凌晨假尖峰） |
| 轨迹 | 含全部四档事件，每条带 `natureLabel`。**轨迹含非活跃事件不是口径放宽**：把打卡/被动事件排除在轨迹之外，只会让「天天打开却从不互动」这类形态（审核/巡检号画像）从界面上消失；「算不算活跃」由标签显式回答 |
| 用户范围 | 平台级 = `USER_SCOPE`（剔 ADMIN 运营号 / `test_` 开发号 / 微信审核账号）+ 口径自证等式；**单用户读取不过滤**（入口列表已过滤——运营需要对已标记账号做取证式查看与撤销标记） |

### 活跃分层（分母归一，勿回退）

六层**互斥且完备**（人数之和 = 真实用户数，页面上可当场验算）：高频活跃（活跃天数 ≥ 可用天数 50%）/
常规活跃（25% ~ 50%）/ 低频活跃（< 25%）/ 仅打开无行为（窗口内 0 主动行为但有打卡）/
完全沉默（既无主动行为也无打卡）/ 新近注册（可用天数 ≤ 3 天，不参与比例分档）。
其中 `可用天数 = min(窗口天数, 注册至今 + 1)`。

**为什么按比例而不是绝对天数**：与留存「未到期 ≠ 0%」同族——按绝对值切档会把窗口内**刚注册**的用户
系统性地判成「沉默 / 低频」（注册两天的人不可能有八个活跃日，那不是用户不活跃，是观测期不够）。
判据（阈值）在**服务端**，前端只渲染：分层是口径而非展示选择，前端若自算分档，改一次阈值就会与
同屏图表互相矛盾。

**行为宽度**桶 = 1 / 2 / 3-4 / 5+ 类主动行为（口径 = 主动行为**事件类型数**，只统计窗口内有主动行为的用户）。

### 后端实现（quwuting-service）

- `user/repository/UserBehaviorEvent.java`：**行为事件目录**（声明 + 5 个生成器）。
- `user/repository/UserBehaviorSql.java`：**事件级事实集**（全档 / 仅主动 / 轨迹带明细）。
- `user/repository/UserStatsSql.java`：两条日级事实集改为目录生成物（**文本逐字未变**）。
- `user/repository/UserBehaviorRepository.java`：只读统计仓（6 条查询：轨迹 2 + 单用户画像 1 + 平台 3）。
- `user/service/AdminUserBehaviorService.java`（单用户轨迹 + 画像）、
  `AdminUserBehaviorAnalyticsService.java`（平台级行为统计）、`BehaviorRefNameResolver.java`（名称/字典唯一实现）。
- `user/dto/response/AdminUserBehavior{Timeline,Profile,Analysis}Response.java`。
- `user/controller/AdminUserController.java`：`GET /admin/users/behavior-analysis?days=`、
  `GET /admin/users/{id}/behavior-timeline?days=&type=&limit=`、
  `GET /admin/users/{id}/behavior-profile?days=`（均 `UserContext.requireAdmin()`，挂既有 `/admin/**` 前缀）。
- 窗口钳制 **7~90**（三端点同族）、轨迹 `limit` 钳制 **20~200**；非法 `type` → **1007**（禁静默忽略：
  静默会让运营以为「该用户没做过」，而事实是参数写错了）。
- **性能**：轨迹 2 条 SQL、画像 1 条 SQL、平台 5 条同参聚合 + 1 条自证；单用户体量下不再拆查询
  （活跃天数是跨类型去重，拆成多条 SQL 反而容易算错）。**零迁移**（纯只读统计，无 Schema 变更）。
- ⚠️ **窗口下界一律比较原始时间戳列**（`created_at >= 当日 0 点`），**禁**写
  `DATE(created_at) >= CAST(:sinceDay AS DATETIME)`——函数包住列会让 12 张行为表的 `created_at`
  索引失效（两者语义等价，索引是白拿的收益）。门禁含反向断言。

### 前端实现（quwuting-admin-web）

- `services/userBehavior.ts`：三接口 + 展示 helper，**零事件字典**（事件码 / 中文名 / 口径档文案全部
  服务端下发；`nullableTime` 归一空值、`rateText`/`perUserText` 在分母为 0 时返回「—」）。
- `views/UserBehaviorAnalysisView.vue`（`/users/behavior-analysis`）：口径说明 + 窗口 chips（7/30/90）+
  4 顶卡（真实用户 / 窗口内活跃 / 人均活跃天数 / 人均行为次数）+ 口径自证一行 + 行为类型分布
  （横向条形，**全目录**：窗口内 0 次的类型不上图但在页脚列出）+ 活跃时段（24 格柱 + 去重用户线，
  并交代未入柱的条数）+ 活跃分层列表 + 行为宽度柱。
- `views/UserDetailView.vue`：新增「行为统计」卡（事件/主动行为/活跃天数/打开天数/近7日与此前7日/
  首次与最近主动行为 + 24 格 CSS 迷你直方图 + 类型分布）与「行为轨迹」卡（类型 chips + 事件流 +
  「加载更多」，**截断时明写「共 N 条 · 已显示最近 M 条」**）；两卡与资料协作卡一样非阻塞、失败可点重试。
- `views/UsersView.vue`：顶部「行为分析 →」入口（同 Dashboard 趋势卡头「留存分析 →」先例）。
- `router/index.ts`：新路由 **必须声明在 `users/:id` 之前**——vue-router 按声明顺序匹配，
  否则 `behavior-analysis` 会被当成 `:id` 命中用户详情页（静默错页）；该页不标 `meta.module`
  （下钻页，不进设置页清单）。

## 后续规划（P1/P2，未实施）

- 漏斗图：注册→打开→互动；游客→注册转化（NULL user 浏览 vs 注册量）监控分享引流。
- 分享裂变归因（share_from top）；留存维度的下钻（某批次 → 该批次用户的活跃/流失名单）。
- ~~注册批次留存 cohort~~ **已于 2026-09-15 落地**（本文档「用户留存分析」节；走现表回溯，
  无需日级快照）。
- ~~用户行为轨迹 / 行为类型分布~~ **已于 2026-09-15 落地**（本文档「用户行为轨迹与行为分析」节；
  行为事件目录 `UserBehaviorEvent` + 事件级事实集 `UserBehaviorSql`）。
- 行为维度的**下钻**（分析页「某类型 → 做过的用户名单」；当前只到类型维度，未到人）——
  需要新的「按类型列用户」查询（本仓已有 `listPlatformUserTypes` 的 userId，接列表页即可）。
- **口径扩展位**（纳入需立项评审 + 同步本文档：会改变既有 DAU / 留存历史数字）：
  消费账本 `qwt_spend_entries`、快讯表态 `qwt_bulletin_reactions` / 快讯浏览 `qwt_bulletin_views`、
  视频号/小程序启动上报等新信号——一律先改 `UserBehaviorEvent` 的口径档，**禁**在消费方就地过滤。
- admin-web 用户管理页（后端 /admin/users 全套就绪，前端未接）——大盘→列表→详情
  下钻链路打通后，噪音号可进用户管理标记/下线。
