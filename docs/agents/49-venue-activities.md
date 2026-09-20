# 门店营业活动（Venue Activities）· 后端权威

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件是活动域的**后端唯一权威**（表结构 / 策略 / 接口契约 / 状态机）。
> 小程序端权威见 [`quwuting/docs/agents/49-venue-activities.md`](../../../quwuting/docs/agents/49-venue-activities.md)（展示层 / 合规 / 到店链路）。

## 0. 域定位

门店营业活动 = 门店优惠活动的**结构化载体**。与之相邻的两个域**都不合适**承载它：

- **公告域（34 号）** 是平台全局运营公告，`content` 是 markdown 正文——活动里"什么时候
  生效"必须被**程序**理解（决定"此刻命中"高亮与"何时自动下线"），塞进正文只能让服务端去猜；
- **门店动态域（`qwt_venue_posts`）** 有 `PostPublisherType.OWNER`（门店认领人可自己发），
  而活动含优惠承诺、属经营信息——门店自己发且不兑现时投诉落小程序主体，本域**刻意不设
  商家通道**。

## 1. 表结构（V27，`db/migration-mysql`）

### `qwt_venue_activities`

| 列 | 类型 | 说明 |
|---|---|---|
| `venue_id` | bigint | 所属门店（语义引用，全库无外键） |
| `title` | varchar(60) | 活动名（自由文本） |
| `benefit_kind` | varchar(24) | 权益类别枚举 `ActivityBenefitKind` |
| `badge_label` | varchar(8) | 列表页短标签（≤4 字最佳）；空则取类别缺省值 |
| `benefit_summary` | varchar(500) | 权益说明（自由文本） |
| `redemption_mode` | varchar(24) | 核销方式枚举 `ActivityRedemptionMode` |
| `redemption_hint` / `platform_addon` | varchar(200) | 核销提示 / 平台专属加项（自由文本） |
| `outer_type` | varchar(24) | 外层调度枚举 `ActivityOuterSchedule` |
| `start_date` / `end_date` | date | 有效期（闭区间；start = end 即单日） |
| `weekday_mask` | varchar(16) | CSV，ISO 1=周一…7=周日；NULL = 每天 |
| `windows` | varchar(500) | 时段 JSON 数组；NULL = 全天 |
| `status` | varchar(16) | 生命周期枚举 `ActivityStatus` |
| `sort_weight` | int | 排序权重（越大越靠前） |

索引：`(venue_id, status)` 服务详情/列表批量；`(status, end_date)` 服务到期扫描。

### `qwt_venue_activity_checkins`

`activity_id` / `venue_id` / `user_id` / `activity_date`（**自然日，服务端填写**），
唯一键 `(activity_id, user_id, activity_date)` = 每人每活动每日一次，幂等靠它兜底。

**列类型必须与实体 Java 类型匹配**（V13/V14 `tinyint` × `Integer` 启动期事故先例）：
枚举列一律 `varchar` + `EnumType.STRING`；`date` ↔ `LocalDate`；时段列 `varchar` ↔ `String`。

### `qwt_venue_shares.activity_id`（V28 加列，2026-09-16）

**本域不新建表**：分享事件（`qwt_venue_shares`，见 10 号文档）本来就承载"传播"这件事，
只是粒度只到场所。加一个可空 `activity_id bigint` + 索引 `(activity_id, event_type)`
即把归因升级为"**哪个活动被传播 / 哪条活动的卡片被点开**"。

- **可空** = 场所级分享（门店详情页 / 门店热度页发起，或 V28 之前的存量行）。
  **存量不回填**：历史行确实不知道当时有没有活动，替历史数据补归因是更坏的选择。
- **非外键**（项目基线无 FK，与 `venue_id` 同风格）：活动删除或到期下线后事件行保留原值。
- 索引服务管理端的**分组计数**（一次 `IN` 取全部活动两个计数），不是单点查询。
- 通道语义：SHARE 行 = 分享者点的是哪条活动；OPEN 行 = 哪条活动的卡片被点开。

## 2. 唯一使用策略模式的地方：外层调度

```
ActivityScheduleStrategy（接口：type() / containsDate() / expiryBoundary()）
  ├ AlwaysActiveStrategy   ALWAYS      —— 长期有效，不参与自动下线
  └ DateRangeStrategy      DATE_RANGE  —— 闭区间；缺日期取"宽松"侧（不静默丢活动）
ActivityScheduleStrategies  ← 注册表，构造期校验"每个枚举值都有实现"，漏实现直接启动失败
```

**为什么内层不做策略**：`windows`（可空=全天）× `weekdayMask`（可空=每天）两字段的组合
语义已覆盖「全时段 / 每日时段 / 每周固定日 / 每周固定日的指定时段」——**同一件事的不同
取值，不是不同算法**；设枚举会产生 4×2 组合爆炸且无扩展价值。

**「单日」不是独立枚举值**：`DATE_RANGE` 在 `start = end` 时的退化情形。
**判据：枚举值必须对应不同算法，不能只对应不同输入方式。**

**扩展点**：新增形态（每月固定日 / 仅节假日）= 加一个 `@Component` 实现类，注册表零改动。

## 3. 状态派生（`ActivityStateResolver`，全项目唯一实现）

优先级：**外层有效期（NOT_STARTED / 已过期）→ 星期掩码 → 生效时段 → 全部已过场**。

| 输出 `ActivityState` | 条件 | `nextChangeAt` |
|---|---|---|
| `NOT_STARTED` | 有效期还没开始（预热期：双节活动 9/25 开始，9/15 就该能看到并收藏） | 有效期首日 00:00 |
| `ACTIVE` | 此刻命中某个生效窗口；或有效期内无窗口（= 全天） | 命中窗口的结束时刻 / 有效期终点 |
| `UPCOMING_TODAY` | 今天还有未开始的窗口 | 下一个窗口开始时刻 |
| `ENDED_TODAY` | 今天的不生效（星期不匹配）或窗口都过了，但活动仍在有效期 | 下一个生效日首场开始（让前端能说"还有明天"） |

- **`nextChangeAt` 用绝对时刻而非"剩余秒数"**：剩余秒数依赖服务端"此刻"的缓存新鲜度，
  响应一旦被缓存就不再正确；绝对时刻不会。
- **彻底过期不在枚举内**：由 30s 调度强转 `OFFLINE`，用户端查不到。
- 批量入口 `resolveAll()` 与单条 `resolve()` 共用实现——保证详情页与列表页口径一致。

## 4. 跨夜契约（本域最易静默出错的一处）

`close < open` 表示结束于次日凌晨（同 `BusinessHoursEntry` 约定）。舞厅普遍营业到凌晨
02:00，naive 的 `start <= now <= end` 会让跨夜窗口**永不命中**。

- `ActivityWindow.contains(LocalTime)`：跨夜按"或"展开。
- `ActivityWindow.endFor(date, now)`：**倒计时也要分情况**——`23:30-00:30` 在 `00:10` 命中的
  是今天凌晨那段（结束于今天 00:30），在 `23:45` 命中的是今晚那段（结束于次日 00:30）。
  判据 = 当前时刻是否已过 `open`。写错不崩、只显示荒谬倒计时，故单点收口。
- **「今日」一律按自然日**，不引入"营业日"（会产生"今日 13:00"的双日歧义）。

## 5. 状态机与 30s 调度

```
DRAFT ──publish──▶ PUBLISHED ──(end_date < today 强转 / 手动 offline)──▶ OFFLINE
                                                                    └──publish──▶ PUBLISHED（唯一复活通道）
```

`VenueActivityService#processScheduledTransitions()`：`@Scheduled(fixedDelay = 30_000)` +
`@Transactional`，批量 UPDATE，转换数 > 0 才记日志（同公告域同款）。

- 判据 `endDate < today` ⇒ **结束当天仍然有效**，次日凌晨第一次调度才转下线。
- 只针对 `DATE_RANGE`（用 `outerType` 枚举排除，**不判 `endDate IS NULL`**——枚举是显式
  契约，判空是隐式的，后者会在业务演进时被悄悄改掉语义）。
- **查询只认 `status`，不做时效过滤**：否则过期活动会在不同查询路径上表现不一致
  （"单点状态机"原则，同公告域）。

## 6. 接口契约

### 用户端（未登录可读，打卡需登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/venues/{venueId}/activities` | 门店可见活动（只认 PUBLISHED）。返回 `state` / `stateDisplay` / `nextChangeAt` / `windowsText` / `validityText` / `badgeLabel`（已按类别兜底）等**派生好的结果** |
| GET | `/venues/activity-badges?venueIds=1,2,3` | 列表页批量标记，返回 `{venueId: [{state, stateDisplay, nextChangeAt, badgeLabel}, ...]}`：**活动在日期范围内即下发**（与营业状态徽标同源——状态要在、语气降级），**已彻底过期的不下发键**；同店多条**按 `命中优先 → nextChangeAt 最近 → activityId 兜底` 排序后全量下发**（规则实现 `VenueActivityService.compareBadgePriority`，与前端 `compareSharePriority` / `pickShareActivity` 必须一致；2026-09-20 由"取一条"扩为"排多条"，判据逐条等价——列表页那一行已支持轮播） |
| POST | `/venues/{venueId}/activities/{activityId}/checkin` | 打卡（幂等） |

分享复用 `venueshare` 域既有端点（本域**不新增接口**，只加可空 `activityId` 字段）：
`POST /venues/{id}/shares`（SHARE）与 `POST /venues/{id}/share-opens`（OPEN）。

### 管理端（`/admin/venue-activities`，`UserContext.requireAdmin()`，全 POST）

`GET /` 列表 · `GET /{id}` 详情 · `POST /create` · `POST /{id}/update` · `POST /{id}/offline`

- 全部 **POST + 动词路径**（项目铁律：只允许 GET/POST，禁 PUT/PATCH/DELETE）。
- **重新发布是 OFFLINE 的唯一复活通道**：`update` 带 `publish=true` 且当前非 PUBLISHED
  才转发布，不做隐式状态漂移。
- 管理端响应比用户端多 `venueName`、`status`、`checkinCountToday` / `checkinCountTotal`、
  `shareCount` / `openCount`（四个计数**批量 `GROUP BY` 取数**，避免列表页 N+1；
  四个 long 打包成 `ActivityCounters` 传递，防止位置参数写串而静默出错数字）。

### 错误码（登记于 12 号 API 约定同段位）

| 码 | 含义 |
|---|---|
| 1033 | 活动不存在 / 已下线 |
| 1034 | 活动参数非法（指定日期却无日期、结束早于开始、时段缺时间） |
| 1001 | 场所不存在（复用既有码） |

## 7. 打卡与归因

- 打卡是**平台侧唯一自有的**到店归因信源，只服务"与门店对账"。
- **⛔ 不入热度公式**：热度四问判据的「难伪造」过不了（同人反复打卡零成本；地理围栏又
  违背 dancer 地址域「克制采集、避免精确坐标」的立场）。平台真实使用深度看既有
  「报一下」（27 号）行为的**增量**。
- 幂等：查询前置 + 唯一键兜底并发（`DataIntegrityViolationException` 静默视为已打卡，
  打卡是"我到了"的声明而非累计动作，连点两次不该看到报错）。
- `activityDate` **由服务端 `LocalDate.now()` 填写，禁客户端传入**。

## 8. 传播归因（V28，2026-09-16）

活动域落地后暴露的缺口：活动**完全没有传播出口**（详见 10 号文档「门店营业活动分享」）。
后端只做两件事——**存下活动维度**、**给出漏斗计数**。

- **写入**：`VenueShareService.recordShare/recordOpen` 各多一个可空 `activityId`。
  不做活动存在性校验（同 `venueId` 策略：事件端点由已渲染的页面发起，冗余查询对
  fire-and-forget 是不合理的延迟负担；孤儿事件不会被任何统计引用）。
- **⚠️ 频控键刻意不含 `activityId`**：同一人 60s 内分享同一门店的多条活动只记第一条。
  有意的防刷取舍——并进频控键的话，连点脚本换个活动 ID 就能绕过。
- **读取**：`VenueShareRepository.countGroupByActivityIdsAndEventType`
  （分组键 = activityId，与 23 号贡献档案的 `countGroupByUserIdsAndEventType` 同构；
  刻意不合并成一个"万能分组"方法——分组键不同就是两个查询意图）。
- **漏斗**：`shareCount`（传播意图）→ `openCount`（卡片被点开）→ `checkinCountTotal`（到店）。
  **三个数字的差值才是信息**：传了没人看（文案/卡片图无效）、看了没到店（权益不够或时段不匹配）。
- ⚠️ **口径提醒（对门店沟通时必须守住）**：分享数与打开数取自事件日志，受 60s 频控影响，
  且小程序**无"分享成功"回调**（SHARE 按"分享意图"记录）⇒ 它们是**趋势量级**，
  不是精确计数。讲趋势（"这周比上周多"），不要讲成承诺值（"我给你带了 37 个人"）。

## 9. 验证

- 编译：`JAVA_HOME=~/.sdkman/candidates/java/25.0.4-oracle ./mvnw -s settings-central.xml -q -DskipTests compile`
- 跨夜：`23:30-00:30` 在 `00:10` 与 `23:45` 均判 `ACTIVE`，`nextChangeAt` 分别为今天/次日 00:30。
- 到期：把 `end_date` 改为昨天 → ≤30s 内 `status` 转 `OFFLINE`，用户端接口不再返回。
- 幂等：同一用户同一活动同日打卡两次 → 第二次不新增行、接口成功返回。
- 启动期自检：临时注释掉 `DateRangeStrategy` → 应用启动失败并指出缺失枚举值。
- **传播归因（V28）**：`POST /venues/{id}/shares` 带 `activityId` → `qwt_venue_shares`
  对应行 `activity_id` 落值；不带 → NULL（场所级分享，与存量行同语义）。
  管理端列表 `shareCount` / `openCount` 与库内 `SELECT COUNT(*) ... GROUP BY activity_id
  , event_type` 逐个对齐（四个计数走同一批 `GROUP BY`，不能出现某个活动漏算）。
