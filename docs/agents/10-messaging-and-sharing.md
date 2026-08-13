# 站内信 / 状态关注 / 分享追踪

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 站内信（消息中心，message 模块，2026-08-08 新增）

### 设计定位

站内信是**通用消息基础设施**——承载平台对用户的**主动通知**（舞伴主页审核结果
驳回附原因；上报处理结果，2026-08-10 新增）。前端「消息中心」页面统一展示：
站内信（本模块）+「我的上报」（venuefeedback / venuestatusreport 业务数据）。
上报处理结果的回传通道（2026-08-10 决策，根因见「统一用户上报 → 处理结果站内信」）：
① 原记录 handleNote（随「我的上报」展示，数据源独立）；② **处理结果站内信**
（FEEDBACK_RESULT，状态实际流转时同事务发送，驱动「消息」未读红点）——两条通道
并行、页面统一展示。

### 数据模型（qwt_messages，V4 迁移）

| 列 | 说明 |
|---|---|
| user_id | 收件人（用户级资源，查询/已读一律按此过滤，越权返回空） |
| type | MessageType 枚举：DANCER_REVIEW（审核结果）/ DANCER_STATUS（隐藏/恢复状态变更）/ FEEDBACK_RESULT（上报处理结果，2026-08-10 新增）/ STATUS_REPORT_RESULT（突发事件采纳结果，2026-08-10）/ VENUE_STATUS_CHANGED（关注门店状态变化，2026-08-12） |
| title / content | 标题 / 正文（TextSanitizer 清洗入库，长度 ≤100 / ≤500 与列定义一致） |
| related_type / related_id | 业务软关联：DANCER → 舞伴详情页 / VENUE → 场所详情页，可空 |
| read_at | 已读时间（null = 未读；未读数徽标依据） |

### 接口（MessageController，均需登录）

| 接口 | 说明 |
|---|---|
| GET /users/me/messages | 我的站内信（分页倒序，read 派生布尔） |
| GET /users/me/messages/unread-count | 未读数（个人中心 / 首页 FAB 未读徽标） |
| GET /users/me/messages/status-alerts | 未读的关注门店状态变化提醒（首页提醒卡片数据源；最新 N 条，默认 3） |
| POST /users/me/messages/{id}/read | 单条标记已读（越权/已读幂等） |
| POST /users/me/messages/read-all | 全部标记已读（前端打开消息中心即调用——标准通知中心范式） |

### 写入约定

- **业务模块调 `MessageService.create(...)` 发送**（无发件人概念，平台即发件人）；
  当前调用点：
  1. `DancerService.updateStatus`（审核/隐藏/恢复，**状态实际变化时**才发送，与状态
     流转同事务——事务失败整体回滚，通知不丢失）
  2. `VenueFeedbackService.handleByAdmin`（上报处理结果，2026-08-10 新增：PENDING→
     任一终态**实际流转时**发送，与状态流转同事务、幂等——终态重复操作不重复发信；
     **匿名上报（userId null）不通知**，与积分奖励同一匿名边界）
- **文案规则**：真实正式、只陈述事实（同前端「分享内容契约」）；驳回时 reason
  经 TextSanitizer 清洗后拼入正文；上报处理结果按终态区分奖励语义（ADOPTED 已奖励
  / ADOPTED_NO_REWARD 未奖励），奖励数额不在消息内硬编码（以积分流水为唯一事实源）
- **新增消息类型** = 枚举加值 + 前端 `types/message.ts` 联合类型/文案同步（见前端
  [AGENTS.md 索引](../../AGENTS.md) · 「消息中心」）；消息表结构无需变更（type 为 varchar 列）

### 表结构演进

`qwt_messages` 由 `db/migration/V4__messages.sql` 创建（Flyway 版本化迁移，见
「Schema 演进与数据库完整性」）；`DancerStatus` 新增 `REJECTED` 为纯枚举变更
（status 列为 varchar，无 DDL）。

---


---
## 关注门店营业状态（venuestatuswatcher 模块，2026-08-12 新增）

### 设计定位

用户可在门店详情页开启「营业状态变化通知」——该店营业状态**每次实际变更**时，
关注者收到站内信（MessageType.VENUE_STATUS_CHANGED）+ 首页「关注状态变化」提醒
卡片（未读即提醒，点击深链门店详情页并标记已读）。**决策：不依赖微信订阅消息**
（一次性订阅"一次授权一条"体验受限、长期订阅类目受限），纯应用内站内信通知——
复用既有消息基础设施，零外部依赖/零成本/零合规风险（与「状态流转对用户有结果的
通知必须走站内信」长期约定一致）。

**与收藏（qwt_favorites）解耦**：关注 = 只盯营业状态变化，不收藏也能开通知
（如"这家暂停营业了，等它恢复"），语义独立。

### 数据模型（qwt_venue_status_watchers，V14 迁移）

| 列 | 说明 |
|---|---|
| user_id | 关注者（用户级资源，开关/查询一律按当前登录用户过滤） |
| venue_id | 被关注门店 |
| UNIQUE(user_id, venue_id) | 同一用户对同一门店只关注一次（重复开启幂等） |
| deleted | 软删列沿用 BaseEntity 约定；**取消关注 = 物理删除**（无审计价值，与 reaction 取消同语义） |

### 接口（VenueStatusWatcherController，均需登录）

| 接口 | 说明 |
|---|---|
| PUT /venues/{id}/status-watch | 开启关注（幂等：已关注直接成功；门店不存在抛 1001） |
| DELETE /venues/{id}/status-watch | 关闭关注（幂等：未关注静默成功） |
| GET /venues/{id}/status-watch | 我的关注态（{ watching: boolean }） |

### 触发挂点（唯一入口 = VenueService 三个状态变更事实源）

`VenueStatusWatcherService.notifyStatusChanged(venueId, from, to)`（REQUIRED 传播
加入调用方事务）——查门店名 + 全部关注者，逐用户 `MessageService.create(...)`
发 VENUE_STATUS_CHANGED（软关联 VENUE）。**幂等 = 一次状态变更一次调用一条消息**，
调用方保证仅在状态实际变更后调用：

1. `VenueService.updateVenue`：`newStatus != oldStatus` 分支内（状态未变不发）
2. `VenueService.markSuspendedByReport`：采纳暂停报（幂等早退"已是 SUSPENDED"已拦截）
3. `VenueService.reopenByReport`：采纳恢复报（幂等早退"已是 OPEN"已拦截）

**通知正文**（`composeContent`，仅陈述事实）：「XX舞厅」已恢复营业（原暂停营业）——
to=OPEN 用「已恢复营业」，其余用 to 的 displayName，from 非空时附「（原X）」。
门店已软删 / 无关注者时不发消息（空通知短路）。

### 首页提醒卡片数据源

复用站内信本身（不新增表）：`GET /users/me/messages/status-alerts` = type 为
VENUE_STATUS_CHANGED 的**未读**消息最新 N 条（`MessageRepository` 按
userId+type+readAt IS NULL 查询）。未读即提醒；点击深链详情页 + 单条标记已读后
从卡片消失（历史留在消息中心）。

---

### 系统默认标签（VenueDefaultsConfig）


**设计动机与根因**：标签系统最初没有"默认"概念——所有标签由管理员手动输入，SQL seed 数据也需手工写入通用标签。这导致：(1) 数据冗余——每个门店重复存储相同标签；(2) 数据不一致——新门店可能忘记添加基础标签；(3) 修改困难——改一个默认标签需更新所有行。

**架构**：系统默认标签是业务规则（非数据），定义在 `application.yaml`（`venue.default.tags`），读时合并，不在 DB 中存储。

**合并规则**：
- DB `qwt_venues.tags` 只存储管理员自定义标签
- 读路径合并：`effectiveTags = defaults ∪ customTags`（去重，默认在前）
- 写路径防御：`VenueService.createVenue()/updateVenue()` 通过 `VenueDefaultsConfig.filterCustomOnly()` 剥离可能误传的默认标签
- 配置键 `venue.default.tags` 在 `application.yaml` 中定义，所有 profile 继承默认值

**注入点**（共 3 处）：
| 类 | 用途 |
|----|------|
| `VenueResponseMapper` | 合并默认标签到 `VenueResponse.tags`，填充 `VenueResponse.defaultTags` |
| `TagAggregateStatsService.computeAggregate()` | 合并默认标签到 venueTags，确保无交互的默认标签以 0 计数出现 |
| `VenueService.createVenue()/updateVenue()` | 防御性剥离传入标签中的默认标签，确保 DB 纯净 |

**缓存影响**：因 `computeAggregate` 的 venueTags 现在包含默认标签，聚合缓存失效周期不变（60s refresh-ahead），无需调整。

---


---
## 分享追踪（venueshare / dancershare 模块，2026-08-05 场所新增 / 2026-08-13 舞伴镜像）

### 设计定位

「去舞厅」是线下社交消费场景，分享（转发到好友/群聊）是产品自然增长的主要通道。本模块承载分享行为的**事件追踪数据通道**（P2）：前端上报分享动作与被分享者打开，数据落 `qwt_venue_shares`（场所）/ `qwt_dancer_shares`（舞伴，镜像结构）事件日志，支撑邀请排行 / 热门传播门店 / 回流归因分析。**前端入口与分享内容契约见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「分享能力规范」章节**（此处只述后端契约）。

### 数据模型（单表双事件，场所 / 舞伴各一张）

`qwt_venue_shares`：`(id, venue_id, user_id, event_type, channel, share_from, created_at)`
`qwt_dancer_shares`：`(id, dancer_id, user_id, event_type, channel, share_from, created_at)`（V20 镜像，仅目标列不同）

| 字段 | 语义 |
|------|------|
| `event_type` | SHARE（分享动作） / OPEN（被分享者打开） |
| `user_id` | 事件发起者（分享者 / 打开者），匿名为 NULL（仅参与 IP 频控，不参与身份归因） |
| `channel` | 分享渠道（仅 SHARE）：BUTTON（页内按钮）/ MENU（右上角菜单）/ TIMELINE（朋友圈） |
| `share_from` | 归因来源（仅 OPEN）：原分享者用户 ID，来自分享路径 `share_from` 参数 |

事件日志语义：**只追加，不修改不删除**，无唯一约束（每次分享/打开是一条独立事件）。**表结构（含索引）由 `db/migration/V1__baseline_schema.sql`（场所）与 `V20__dancer_shares.sql`（舞伴）权威定义，新变更走 V{n} 迁移**（2026-08-07 起 Flyway 策略，见「Schema 演进与数据库完整性」）。

### 接口（软鉴权，fire-and-forget）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/venues/{id}/shares` | 软鉴权（匿名可上报） | 记录分享动作，body `{channel}`（可选，@Pattern 校验 400） |
| POST | `/venues/{id}/share-opens` | 软鉴权（匿名可上报） | 记录分享打开，body `{shareFrom}`（可选） |
| POST | `/dancers/{id}/shares` | 软鉴权（匿名可上报） | 记录舞伴分享动作，body `{channel}`（可选，@Pattern 校验 400） |
| POST | `/dancers/{id}/share-opens` | 软鉴权（匿名可上报） | 记录舞伴分享打开，body `{shareFrom}`（可选） |

与 `POST /venues/{id}/view` 同语义族（`VenueViewService` 模式）：

- **fire-and-forget**：失败不影响主流程；**不做目标存在性校验**（事件端点由详情页发起，目标不存在时详情页已 404，冗余的目标查询对事件端点是不合理的延迟负担；孤儿事件不会被任何统计引用）
- **60s 频控**：同目标同身份（已登录按 userId，匿名按 IP）60s 窗口内最多记 1 条（Caffeine，`VenueShareService` / `DancerShareService`，两模块镜像同构），压制脚本连点刷事件放大分享/回流量的漏洞（尽力而为，多 IP 分布式刷无法拦截，与浏览频控同语义）
- **event_type 枚举共享**：两模块复用 `venueshare.enums.ShareEventType`（SHARE/OPEN，语义通用）

### 边界（与热度公式解耦）

分享维度**不在热度公式闭集内**（公式由产品定义，见「场所热度」章节），本模块**不 invalidate 热度缓存**、不参与任何展示逻辑——纯分析数据源。若未来产品将分享纳入热度公式，需同步修改公式文案（`formulaText`/`formulaDetail` 后端下发）与热度服务。

---

