# 50 · 门店开业计划（预期开业日 + 到点自动兑现）

> 一句话：**门店状态补上了「将来时」。** `status` 只有 5 个此刻事实取值，而「明天开业」
> 是一条计划——本域把它落成 `qwt_venues.expected_open_date`，展示层派生 `UPCOMING`
> （「9月18日开业」），到点由调度器走正规通道自动转 OPEN。
>
> 落地 = V29 迁移 + `VenueOpeningScheduler` + `VenueService#applyScheduledOpening`。
> 小程序展示层权威见 [`quwuting/docs/agents/50-venue-opening-plan.md`](../../../quwuting/docs/agents/50-venue-opening-plan.md)。

## 1. 问题（根因，不是症状）

2026-09-17 用户报障：南通「寻梦缘歌舞厅」**明天开业**，且已放出「门票减免」活动，
但系统怎么填都别扭——

```
填 OPEN      → 今天就是「营业中」，用户今天白跑
保持停业     → 今天事实正确，但「9月18日开业」这条信息没了
报「恢复营业」→ 语义是「我此刻在场看到它开门了」，今天境内无目击者
```

病根不在某个状态值选错了，而在**模型少了一维**：

| | 语义 | 承载 |
|---|---|---|
| `qwt_venues.status` | **现在时**：此刻开不开 | 5 态枚举 |
| （缺失） | **将来时**：什么时候开 | —— |

用户看到的两个信号因此互相矛盾：状态徽标说「已停业」（现在时，隐含"这家店不营业了"），
活动行说「门票减免 · 9月18日 起」（将来时）。**两句话都不含「明天开业」这个唯一重要的结论。**

### 为什么三条现成路径都不对（逐个论证，勿再讨论）

**① 直接置 OPEN** —— 违反 V25（48 号）文档的核心不变量：人工置 OPEN 的错判代价
= 用户白跑一趟。且副作用三连：给所有关注者推一条「寻梦缘恢复营业」的**假通知**；
3 天人工锁到 9/20 到期，真开业时保护期只剩 2 天（时间窗错位）；热度上报的「非营业禁报」
守卫（27 号）会在开业前一天就放开，众包信号被污染。

**② 报 `ReportType.RESUMED`（恢复营业）** —— 语义错位：该类型的定义是「我此刻在场，
看到它开门了」（现在时 + 现场目击），并承担「解除 SUSPENDED 公告」的角色。
更关键的是**不采纳也不安全**：门店报告信号会上列表「最新上报」行与详情页公告条
（27/39 号），它以「恢复营业 · 舞友上报 · 未经核实」的形态**照样曝光**，假信息一样被消费；
采纳则立刻 `reopenByReport`（OPEN + 锁 + 通知），与路径 ① 同病。

**③ 不改** —— 矛盾信号留存（就是报障时的现状）。

## 2. 设计：补「计划」这一维，而不是补第 6 个状态枚举值

```
qwt_venues.status              现在时的事实（5 态，一行不改）
qwt_venues.expected_open_date  将来时的计划（V29 新增）
```

展示层派生（**与既有 `NOT_OPEN_YET` 完全同构**，详见小程序端 50 号）：

| 时间尺度 | 派生态 | 判据 | 徽标文案 |
|---|---|---|---|
| 日内将来时（已有） | `NOT_OPEN_YET` | `status = OPEN` × 不在营业时段 | 「今晚 19:00 开门」 |
| **跨日将来时（本域）** | `UPCOMING` | `status ≠ OPEN` × `expectedOpenDate >= today` | 「9月18日开业」 |

### 为什么走「派生 + 调度」而不是新增 `VenueStatus` 枚举值

① **它是「日期 × 今天」的函数，不是独立事实**。落库立刻会出现「9 月 19 日库里还写着
UPCOMING」的假状态（同活动域 `NOT_STARTED` 由 `ActivityStateResolver` 派生而不落库，
见 49 号 §3 的同一判据）。

② **所有基于存储态的守卫天然正确、零改动**——这是本方案与"加枚举"的分水岭：

| 既有机制 | 判据 | 加枚举要做什么 | 本方案要做什么 |
|---|---|---|---|
| 热度上报「非营业禁报」（27 号） | `status != OPEN` | 全库审计每处比较 | **零改动** |
| 报告类型守卫（`RESUMED` 仅对非营业门店有意义） | `status` | 同上 | **零改动** |
| V25 人工锁 / 永久豁免（48 号） | 只认 5 态 | 同上（锁时长表要补第 6 行） | **零改动** |
| 列表筛选 / 统计口径 / 快照 | `status` | 同上 | **零改动** |

判据落在「**新增的状态值是否需要全库审计所有 `status` 比较**」——需要就是高风险改动，
说明它不是状态，是派生。

③ **到点的自动开业必须真的写库**（否则徽标永远停在派生态、关注者通知发不出、
门店进不了热度/统计的正常口径）。这个「计划兑现」动作由调度器走正规通道完成，见 §4。

## 3. 数据模型（V29，`db/migration-mysql`）

| 列 | 类型 | 语义 |
|---|---|---|
| `expected_open_date` | `date NULL` | 预期开业日。`NULL` = 无开业计划（含计划已兑现）。索引 `qwt_idx_expected_open_date` |

**写入语义（与其余可空字段刻意不同，判据见 `CreateVenueRequest#expectedOpenDate`）**：

- `null` = **清空**（不是"保留原值"）。本字段存在合法的清空场景（计划取消 / 改期），
  必须给它一条通道；且它是纯新增、零存量，唯一调用方 = 管理端门店编辑页（与后端同轮接入），
  "旧表单不带该字段"的窗口期没有任何数据可丢。对比：`status` / `venueType` / `sortWeight`
  的「null = 保留原值」是为**存量调用方**（旧版表单、脚本、Skill）准备的兼容语义。
- **置 OPEN 即清空**：`createVenue` / `updateVenue` / 调度器三条路径都会在状态为 OPEN 时
  把开业日置 null——一个计划只兑现一次，不留「已营业但仍挂着开业日」的第二套真值。

**不进离线快照**（`VenueSnapshotItem`）：快照是静态子集，而本列**会自然过期**——
离线端可能已过开业日却仍显示「9月18日开业」，比不带更坏。

**状态日志**：兑现时写 `qwt_venue_status_logs`，新增 `change_source = 'SCHEDULED'`
（常量 `VenueStatusLog.CHANGE_SOURCE_SCHEDULED`，与 `AGENT_BATCH` / `ADMIN` 并列），
`changed_by = NULL`（系统自动，无操作人）。

## 4. 兑现通道（`VenueService#applyScheduledOpening`，第 6 个状态写入点）

48 号文档登记了 5 个状态写入点，本域新增第 6 个。副作用链与 `reopenByReport`
（人工确认恢复营业）**刻意保持同构**，差异三处、都是语义要求：

| 差异 | 值 | 理由 |
|---|---|---|
| `changeSource` | `SCHEDULED` | 审计链上「系统按计划兑现」「人改的」「舞讯推的」三者可信度不同 |
| `changedBy` | `null` | 系统自动变更，无操作人 |
| `expectedOpenDate` | 置 null | 计划兑现即作废 |

其余完全一致：写状态日志 → 置 OPEN → **打人工锁** → 保存 → 通知关注者（
`VenueStatusWatcherService#notifyStatusChanged`）→ 逐出 heat / detail-public / venue-list 三级缓存。

### ⚠️ 为什么必须打人工锁（本域最易漏的一条）

`lockOnManualChange(venue, OPEN, now)` 走 **OPEN 档 3 天**。理由：开业计划是**人设定的**，
属人工意图的延伸，理应享受与人工置 OPEN 同等的优先权。而一家新店刚开业时，
**舞讯大概率尚未收录它** ⇒ 次日 `applyBatchSuspend` 的「未上榜差集」推断正好命中，
会把状态推回暂停——**自动开业第二天就自我推翻**。48 号文档已经把这条路径完整踩过一遍，
这里是同一个坑的第二个入口。

### 幂等与早退

| 情形 | 行为 |
|---|---|
| `expectedOpenDate == null` | 早退（计划已被其它路径兑现/撤销） |
| `status == OPEN` | 只清悬挂的开业日（`clearFulfilledOpeningPlan`，不写日志、不通知——状态无变化） |
| 其余 | 完整兑现 |

## 5. 调度器（`VenueOpeningScheduler`）

```
@Scheduled(fixedDelay = 30_000)
  → VenueRepository#findDueOpeningPlans(today, PageRequest(0, 200))
      deleted = false AND expected_open_date IS NOT NULL AND expected_open_date <= today
      ORDER BY expected_open_date, id
  → 逐店 venueService.applyScheduledOpening(id)（独立事务 + 单店 try-catch）
```

**为什么是独立 Bean 而不是 VenueService 里的一个方法**：`applyScheduledOpening` 带
`@Transactional` + `@Caching`，而**同类内自调用不经 Spring 代理** ⇒ 把调度循环写进
`VenueService` 会让那两个注解**静默失效**（事务不生效、缓存不逐出，且不报任何错）。
拆成独立 Bean 经代理调用，与 `StatusReportLatestService` 为打破构造器循环而拆微服务的
先例同源：**能力边界与注入关系决定类的边界**。

**为什么 30s 轮询而不是每日定点**：与公告域 / 活动域的 `processScheduledTransitions`
完全同款（一个系统里「到点自动变更」只该有一种节奏）。判据是**日期粒度**，
故 00:00 一过即生效；当天「还没到营业时段」由前端 `NOT_OPEN_YET` 派生自然接管
（徽标显示「今晚 19:00 开门」），与开业日无缝衔接——**这里不需要判断具体时刻**。

**为什么查询不排除 `status = OPEN` 的行**：那些是「人工提前开业、开业日忘了清」的悬挂值，
本查询是清理它们的唯一入口；按状态过滤掉，悬挂值就永远清不掉。

**失败隔离**：单店 try-catch，一家失败不阻塞同批；抛出的店本轮不推进、下一轮自动重试
（开业日仍是「已到期」，查询条件不依赖任何"已处理"标记位 ⇒ **无需补偿任务**）。
单轮上限 200 是防御性设计（防批量误填过去日期演变成全表串行事务风暴）。

## 6. 接口

| 方法 | 路径 | 变化 |
|---|---|---|
| POST | `/venues/{id}/update` | 请求体新增可选 `expectedOpenDate`（`yyyy-MM-dd`）；**null = 清空** |
| POST | `/venues` | 同上（新建时直接填开业日 ⇒ 门店一建立就是「即将开业」） |
| GET | 列表 / 详情 / 收藏 / 快照之外的响应 | `VenueResponse` 新增 `expectedOpenDate`（`@JsonFormat("yyyy-MM-dd")`） |

**顺带解掉的既有缺口**：V1 建表 `status DEFAULT 'OPEN'` ⇒ 未开业的新店若不被显式设成
非 OPEN，录进去就是「营业中」，是同一类错误的另一个方向。填了开业日的门店天然表达
「还没开」，无需再靠人工记得改状态。

## 7. 明确不做

- **只做开业方向**（2026-09-17 用户拍板）。对称的「X 日起停业改造」预告不做；
  列设计留位——真要做时加 `expected_close_date`，展示层同构再加一个派生态。
- **不做历史回溯**：存量门店全部 NULL（本就无计划，无需反推）。
- **不做「提前 N 天推送提醒」**：关注者通知只在**兑现时刻**发一次（与状态变更通知同源）。
  开业前的预热由活动域（`NOT_STARTED` 预热态，49 号 §3）与公告域承担。

## 8. 验证（静态层）

- 编译：`JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw -q -s settings-central.xml clean test-compile`
- 调度判据：把某店 `expected_open_date` 设为昨天 + `status = CEASED` → ≤30s 内 `status` 转
  `OPEN`、`expected_open_date` 清空、`qwt_venue_status_logs` 新增一行 `change_source = SCHEDULED`
  / `changed_by = NULL`、`status_locked_until` ≈ now + 3 天。
- 悬挂值自愈：`status = OPEN` 且 `expected_open_date` = 昨天 → 该行只被清空日期，无新日志、无通知。
- 幂等：同一行连着两轮调度 → 第二轮查不到（日期已清空）。
- 早期清空：`updateVenue` 传 `status = OPEN` + 任意 `expectedOpenDate` → 落库后开业日为 null。
- 行为与观感交用户真机验证（验证红线 §2）。

## 9. 涉及文件

| 层 | 文件 |
|---|---|
| 迁移 | `db/migration-mysql/V29__venue_expected_open_date.sql` |
| 实体 / 契约 | `venue/entity/Venue.java`、`venue/entity/VenueStatusLog.java`（新增常量）、`venue/dto/request/CreateVenueRequest.java`、`venue/dto/response/VenueResponse.java`、`venue/mapper/VenueResponseMapper.java` |
| 兑现通道 | `venue/service/VenueService.java`（`applyScheduledOpening` / `clearFulfilledOpeningPlan` / `createVenue` / `updateVenue`） |
| 调度 | `venue/scheduler/VenueOpeningScheduler.java`、`venue/repository/VenueRepository.java`（`findDueOpeningPlans`） |
| 展示层（小程序） | `utils/venueStatus.ts`、`types/venue.ts`、`components/venue-card/*`、`pages/venue-detail/*`、`app.wxss` |
| 管理端 | `quwuting-admin-web`：`services/venueAdmin.ts`、`views/VenueEditView.vue` |

## 10. 沿革

- **2026-09-17 建立**（用户报障「南通寻梦缘明天开业，系统怎么填都别扭」）。
  用户拍板：① 按「存储 + 展示 + 调度」三层完整落地；② 字段扩展边界**只做开业方向**，
  停业预告留位不做。
  后端 `./mvnw clean test-compile` 通过；小程序 `npx tsc --noEmit` + `npm run check`
  十一道全绿；管理端 `vue-tsc -b` 通过。
- **待办（未做）**：无。停业方向预告为明确不做项（见 §7）。
