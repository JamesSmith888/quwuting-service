# 统一用户上报（venuefeedback / venuestatusreport）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 统一用户上报（venuefeedback 模块，2026-08-05 泛化）

### 设计定位

本模块从「场所信息纠错反馈」泛化为**统一用户上报模板**：任何"信息缺失/有误需管理员维护"的场景共用同一张表、同一个提交通道（`POST /venues/{venueId}/feedbacks`）与同一套管理端处理流程（`/admin/reports`）。**新增上报场景 = 扩展 `FeedbackType` 枚举 + 前端入口**，禁止为单个场景新写表/接口/表单——这是"通用模板"的可扩展性保证。

与 `venuestatusreport`（实时 4h TTL 众包信号）的边界保持：本模块是**异步管理员审核流程**（有处理状态），实时信号职责不在此承担。`SUSPENDED` 类型在此 = "不在场但认为状态信息有误"，现场确认关门走 venuestatusreport（见「场所状态上报」章节）。

**匿名决策（2026-08-06，需求根因）**：上报**不强推登录**——未登录用户直接提交（`user_id` = null，响应 `trackable=false`），管理员照常处理（管理端不依赖上报者身份）。但匿名记录无法在个人中心回看（「我的上报记录」按 `user_id` 查询）、处理结果无法回传——前端在匿名提交时提示"一键登录后可查看管理员处理结果"（showModal 不强推）。登录用户上报 → `user_id` 落库 → 个人中心可见全部记录与处理结果。**"匿名可参与、追踪需登录"是本模块的明确设计决策**（前端交互落点见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「我的上报记录」）。

### 类型与状态机

- `FeedbackType`：CLOSED_DOWN（已关门/停业）/ SUSPENDED（暂停营业）/ **RESUMED（门店已恢复营业——2026-08-10 新增，与 CLOSED_DOWN/SUSPENDED 相反的纠正信号：门店存储态**声称非营业**（RENOVATING/CLOSED/SUSPENDED/CEASED，2026-08-11 泛化，原仅「已停业」CEASED——根因：前端 chip 旧实现把该语义塌缩为 `status === 'CEASED'` 单值特判，遗漏其余非营业态，暂停营业门店仍显示「报告暂停营业」）时，详情页报告操作 chip 翻转为「报告恢复营业」，提交本类型走异步管理员审核；管理员核实后经 updateVenue 将状态改回 OPEN（恢复通道 = 既有 updateVenue，与暂停报采纳 markSuspendedByReport 对称）。为什么走 venuefeedback 而非 venuestatusreport：纠正的是存储态（非营业→OPEN），属异步审核职责；4h TTL 实时信号层对"已声称非营业"门店无决策意义（`StatusReportService.submitReport` 已按同一语义拒绝非营业门店的暂停报，见「场所状态上报」），前端收敛逻辑见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「报告操作状态机」）** / INACCURATE（信息有误）/ **MISSING_INFO（信息缺失——营业时间/联系方式/地址/简介/微信联系等字段缺失的上报入口，2026-08-06 新增，详情页"信息缺失？点此上报"统一使用，note 承载字段说明与用户补充数据）** / **PRICE（价格信息缺失或有误——门票/舞伴数据缺失空态的上报入口，2026-08-05 新增）** / OTHER（其他）
- `ReportStatus` 状态机：PENDING（待处理）→ **ADOPTED（已采纳·奖励，2026-08-10 V2 新增）/ ADOPTED_NO_REWARD（已采纳·未奖励，2026-08-10 三动作定稿新增）/ RESOLVED（已处理，2026-08-10 起管理端无 UI 入口，保留兼容历史）/ DISMISSED（已忽略）**。**终态固定不可回退**——**ADOPTED** 表示管理员核实并**采纳**该上报（如按纠错内容更新门店数据/确认状态异常属实）→ **同一事务发放积分奖励**（仅登录用户，匿名采纳不发；幂等见「积分系统」章节）；**ADOPTED_NO_REWARD** 表示上报被采纳但管理员选择不发积分（采纳动作 `reward=false`，与 RESOLVED 的"核实后未采纳"语义区分，上报者可见「已采纳·未奖励」）；RESOLVED 表示管理员完成核实但信息无需采纳应用（**不发分**）；DISMISSED 判定为误报/无需处理（**不发分**）。布尔 handled 无法区分多种终态语义（历史 `handled` 列保留为实体兜底映射，见下文「Schema 演进」）。**「采纳」独立于「已处理」是 V2 核心决策：奖励资格取决于"上报是否真的被采用"而非"管理员有没有点过按钮"**（根因与决策记录见 `docs/积分系统-需求设计-V2-2026-08-10.md`）
- **处理结果回传（2026-08-06 新增）**：管理员 resolve/dismiss 时可填写 `handleNote`（处理结果说明，≤500 字），随「我的上报记录」回传上报者——"管理员处理完成后反馈处理结果给用户"的载体。不填 = 仅流转状态，用户侧只见处理状态。终态幂等语义下重复处理不覆盖已有 handleNote
- **处理结果站内信（2026-08-10 新增，替代原"不复制为站内信"决策）**：`VenueFeedbackService.handleByAdmin` 在 **PENDING → 任一终态实际流转时**向上报者（userId 非空）发送 `FEEDBACK_RESULT` 站内信（与状态流转**同事务**、幂等——终态重复操作不重复发信；**匿名上报（userId null）不通知**，与积分奖励同一匿名边界）。正文 = 场所名 + 上报类型 + 终态结论（ADOPTED 明确"已奖励积分" / ADOPTED_NO_REWARD 明确"未奖励积分" / RESOLVED "已处理" / DISMISSED "已忽略"）+ 可选 `handleNote`（"管理员说明："前缀回传）；软关联 `VENUE`（前端深链场所详情页）。**根因**：消息中心设计（2026-08-08）把站内信窄化为"平台主动通知（舞伴审核）"，处理结果被视为纯被动业务数据（只经 handleNote 回传、无已读语义）——早于 2026-08-10 FAB 红点"有新提醒"语义，上报者无法被主动提醒处理结果（曾被列为 P3 遗留"采纳站内信"）。**长期约定：凡是"状态流转对用户有结果"的通知都必须走站内信**（同事务、幂等、匿名边界），不得只落在业务记录上
- **结构化纠错载荷（2026-08-10 新增）**：`INACCURATE` 类型可携带 `field`（哪个字段有误，受控词汇表 `FeedbackField`：NAME/ADDRESS/HOURS/TICKET/PARTNER/CONTACT/WECHAT/DESCRIPTION/OTHER——与前端 `MISSING_INFO_FIELDS` data-key 同源 + name + other 兜底）+ `correctedValue`（用户认为正确的数据，≤500 字，TextSanitizer 清洗；空白不入库存 NULL）。**根因**：门店数据经 OCR 批量导入系统性错误，旧载荷只有自由文本 `note`（"哪里错了"与"正确值"混在一起），管理端无法机器可读核对纠错建议。`field`/`correctedValue` 均可空：只指出字段或只提供正确值都是有效上报。**其余类型忽略这两个字段（不落库）**——缺失（MISSING_INFO/PRICE）、状态（SUSPENDED/CLOSED_DOWN）、其他（OTHER）的语义仍由 note 承载

### 数据模型

`qwt_venue_feedbacks` 表：venueId + userId（**可空 = 匿名，2026-08-06 放宽**）+ type + note + **field（纠错目标字段，可空，2026-08-10 V8 新增）+ corrected_value（用户认为正确的数据，可空，2026-08-10 V8 新增）** + status + handledBy + handledAt + handleNote（处理结果说明，2026-08-06 新增，可空列自动加列）+ handled（遗留兜底列）。索引 `(venueId)`、`(userId)`、`(status, createdAt)`（管理端状态筛选分页）+ PENDING 部分唯一索引（防刷，见下）。

**Schema 演进（2026-08-07 起：Flyway 版本化迁移，见「Schema 演进与数据库完整性」）**：status 列默认值由 `@ColumnDefault("'PENDING'")` **单一通道**声明（配合 `@Column(length=20, nullable=false)` + 字段初始化器）——2026-08-05 曾因 columnDefinition 与 @ColumnDefault **双声明 DEFAULT** 报 "multiple default values specified"（修复 + 根因见「Schema 演进 → 事故根因」）；handledBy / handledAt 为可空列；遗留 `handled` 布尔列由实体字段映射兜底（@Deprecated + `@ColumnDefault("false")`，insert 恒写 false）。表结构变更（含新列/索引/约束）一律新增 `db/migration/V{n}` 迁移脚本，禁止依赖 ddl-auto 自动演进。

⚠ **user_id 可空（2026-08-06 匿名上报）**：旧库曾需手动执行 `db/migrate-feedback-anonymous.sql`（`ALTER COLUMN user_id DROP NOT NULL`）放宽约束（该脚本已执行，勿重复运行）；**新环境由 V1 baseline 直接建成可空列**，无需任何手动步骤。

### 防刷机制（2026-08-07 补齐）

**根因**：feedback 泛化为统一上报模板时（2026-08-05）未对齐其余上报类接口的既有防刷模式——status-report 有 5 次/小时频控、reaction/recognize 有每日唯一约束、view/share 有 60s 频控，唯独 feedback 零防刷（匿名可提交 + 无唯一约束），登录用户连点重复插入、脚本可无限刷脏数据。深层原因：项目防刷机制是"每模块自行实现"（Caffeine 内嵌 / DB 唯一约束），无统一抽象，新增/泛化模块容易遗漏。

**双防线（分层收口）**：

1. **应用层 60s 冷却**（`VenueFeedbackService` 内嵌 Caffeine，与 VenueViewService / VenueShareService 同模式）：key = `venueId:type:field:identity`（**field 维度 2026-08-10 加入**——结构化纠错后语义单位 = (type, field)，用户报完"门票价格"紧接着报"联系电话"（不同字段）属正常连续纠错，不应被冷却误伤；同字段连点仍被压制），identity 登录取 `u{userId}`、匿名取 `ip:{ClientIpResolver.resolve()}`——同身份对同场所同类型同字段在窗口内重复提交抛 1006。尽力而为（多 IP 分布式刷无法拦截），与 view/share 频控同语义。
2. **库内 PENDING 部分唯一索引**（`db/migration/V2__feedback_pending_dedup.sql` + `V8__feedback_correction_fields.sql` 拆分）：**去重单位（2026-08-10 升级）**——旧索引 `UNIQUE (user_id, venue_id, type) WHERE user_id IS NOT NULL AND status = 'PENDING'` 的去重单位是 type，与字段级纠错的语义单位 (type, field) 不匹配（同场所同类型报两个字段会被幂等吞掉）。V8 拆为两条部分唯一索引：① `UNIQUE (user_id, venue_id, type, field) WHERE user_id IS NOT NULL AND status = 'PENDING' AND field IS NOT NULL`——每字段一条 PENDING（同字段重复提交仍去重，跨字段互不阻塞）；② `UNIQUE (user_id, venue_id, type) WHERE user_id IS NOT NULL AND status = 'PENDING' AND field IS NULL`——非纠错场景（field 不填）保持 V2 原语义。管理员处理后旧行移出索引，可再次上报；匿名行不参与（NULL 无法身份归因）。并发/多实例竞争窗口内撞唯一键时（**2026-08-20 确定性化，根因见下**），登录用户经**原子 upsert**（`INSERT ... ON CONFLICT ... DO NOTHING`，冲突目标 = 列清单 + 完整索引谓词，`VenueFeedbackRepository.upsertPendingWithField` / `upsertPendingWithoutField`）单次往返收口，随后**同一事务内按去重单位回查**（纠错场景 `findByUserIdAndVenueIdAndTypeAndFieldAndStatus`，否则 `findByUserIdAndVenueIdAndTypeAndStatus`）幂等返回胜出行（新插入或已存在）；匿名无索引冲突面，走 save 原路径（60s IP 冷却兜底）。V2 迁移先清理存量重复（保留每组最早一条）再建索引；V8 拆分时存量行 field 均为 NULL 只落索引②，无需清理。

**幂等确定性化根因（2026-08-20 线上实证：报告恢复营业连点报 500）**：旧实现为「save（INSERT）+ catch `DataIntegrityViolationException`（23505）+ `entityManager.clear()` + 同事务回查」——PostgreSQL 中语句失败（SQLState 23505）后整个事务进入 **aborted 状态（25P02）**，catch 内 clear 只清理 session、无法恢复已中止的 DB 事务，catch 后的回查必然抛 `JpaSystemException`「current transaction is aborted」→ 未捕获 → GlobalExceptionHandler → HTTP 500。触发路径：首次提交成功创建 PENDING 记录 → 60s 冷却窗口外再次点击 → INSERT 撞唯一索引 → 500（日志中 `qwt_uk_feedbacks_user_venue_type_pending` 23505 与紧随其后的 25P02 即此链）。这是 2026-08-19「23505 并发幂等确定性化」治理的遗漏点（当时按问题域只覆盖 dancer 域），本次与 `StatusReportService.submitReport`（同类模式）一并收敛，长期规则见 15-governance 错误表「有唯一约束的幂等写入」。**禁止再引入 catch+clear 表达幂等。**

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/venues/{venueId}/feedbacks` | **匿名可提交** | 提交上报（type 必填 + note 可选 + **field/correctedValue 可选（2026-08-10：仅 INACCURATE 类型承载的结构化纠错载荷）**），响应含 maintenanceHint + trackable + field/fieldDisplay/correctedValue 回显 |
| GET | `/venues/{venueId}/feedbacks/mine` | 需登录 | 我对**当前门店**的上报（详情页弹窗数据源，2026-08-06 新增） |
| GET | `/feedbacks/mine?venueId=` | 需登录 | 我的上报（venueId 可选：缺省=跨场所全部=个人中心；传值=单门店，2026-08-06 新增，与上者同口径共用 service） |
| GET | `/admin/reports` | ADMIN | 平台级列表（status/type 可选筛选，分页倒序，含 venueName / handleNote） |
| POST | `/admin/reports/{id}/resolve` | ADMIN | 标记已处理（幂等；body 可选 `{"note": 处理结果说明}`） |
| POST | `/admin/reports/{id}/dismiss` | ADMIN | 标记已忽略（幂等；body 可选 `{"note": 处理结果说明}`） |

管理端列表场所名称批量查询（`VenueRepository.findByIdInAndDeletedFalse`）消除 N+1；已逻辑删除的场所回退"已下架场所"占位。「我的上报」记录同样批量回填场所名（同一模式），但**不过滤场所删除**——用户历史记录真实性不因场所下架而消失（与 status-reports/mine 的 JOIN 策略一致）。

### 用户侧 read path（2026-08-06 补全，根因）

2026-08-05 泛化时只建设了 write path（提交）与 admin path（列表/处理），**用户侧 read path 从未设计**；"我的上报记录"一度被错误嫁接在 status-report 的跨场所 mine 接口上（详情页弹窗展示与所在门店无关、feedback 记录用户侧完全不可见——见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「我的上报记录」根因）。本次补全：

- **用户级** `GET /feedbacks/mine`（个人中心：全部场所、各维度上报一览）+ **场所级** `GET /venues/{venueId}/feedbacks/mine`（详情页弹窗：当前门店）——同一 service 方法 `listMyFeedbacks(venueId)` 两个入口，venueId null = 全部
- 范围：**全部状态**（PENDING/RESOLVED/DISMISSED）均返回——异步审核流程每条记录都有消费价值（待处理 = 未反馈，已处理 = 展示处理结果）；与 status-report 的"已撤销不返回"语义不同（实时信号撤销 = 收回，异步上报无撤销概念）
- 响应 `MyFeedbackResponse`：id/venueId/venueName/type/typeDisplay/note/**field/fieldDisplay/correctedValue（2026-08-10：结构化纠错载荷随「我的上报记录」回显用户）**/status/statusDisplay/handleNote/handledAt/**rewardEarned（2026-08-12 新增：该条上报实际到账积分，仅 ADOPTED 非空——「我的上报记录」闭环展示"+N 积分已到账"）**/createdAt——处理结果随记录原样回传

### 上报激励下发（2026-08-12 新增，根因见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「上报激励三触点」）

采纳发分（`rewardFeedback`，匿名不发/幂等）早已存在，但用户全程不知情、上报无动机。本次把"采纳可得积分（积分可兑换礼物赠送）"按三触点透出，**金额/文案唯一事实源在后端，前端零硬编码零拼接**：

- **公开只读接口 `GET /points/reward-hint`**（PointsController 中与其他 /points 接口"均需登录"的**唯一例外**——全局配置无隐私，激励对匿名同样有意义，登录引导复用）：返回 `RewardHintResponse`（`rewardAmount` = 配置 `app.points.feedback-reward` + `rewardHint` = 整句激励文案）。文案由 `PointsService.rewardHintText()` 拼接（**唯一事实源**），详情页 onLoad 经 `getRewardHint()` 拉取缓存，失败静默降级
- **`VenueFeedbackResponse` 增 `rewardAmount` + `rewardHint`**（提交响应下发，成功 toast 消费；匿名同样下发用于登录引导，能否真领由 `trackable` 决定）——与 reward-hint 接口**同源消费** `rewardHintText()`，禁止两处各自拼接
- **`MyFeedbackResponse` 增 `rewardEarned`**（仅终态 ADOPTED 非空——同事务发分 + 流水幂等保证已到账，按 status 派生，不查流水）
- 管理端 `AdminReportResponse` 同步增加 field/fieldDisplay/correctedValue（2026-08-10）——`/admin/reports` 卡片展示「字段名 → 正确值」纠错建议，管理员按字段核对/跳转详情核实

### 文本防注入（2026-08-06）

用户自由文本（`note` / `handleNote`，statusReport 的 `note` 同规则）入库前统一经 `common/text/TextSanitizer` 清洗：控制字符剥离（保留 \n）+ trim + 500 字截断——DTO `@Size` 校验拦非法请求，sanitize 兜底防御绕过校验的直落库路径（双保险）。防注入分层约定（全链路）：

- **SQL 注入**：JPA 参数化查询天然免疫（本项目全部查询走 JPA/JPQL/命名参数，无字符串拼接 SQL；原生 SQL 条件一律 `:param IS NULL OR col = :param` 传参，禁拼接）
- **XSS**：小程序端全部经 `<text>` 文本节点渲染（天然转义）；管理端页面同为小程序原生页面。**约定：任何未来新增的 web/富文本消费端必须对文本做 HTML 转义或仅用文本节点渲染**
- **协议/日志污染**：sanitize 剥离控制字符，保证入库文本不携带可干扰下游的字节

### 维护承诺配置（maintenanceHint）

提交响应携带 `maintenanceHint`（"已通知管理员，我们会在 X 日内维护好"），**X 来自配置 `app.reports.maintenance-days`（`config/ReportsProperties`，默认 3，缺失自动回退）**——前端 toast/空态直接展示，禁止硬编码承诺天数。调整承诺天数只改一处配置。

---


---
## 场所状态上报（venuestatusreport 模块）

### 设计定位

舞厅门店状态变更（警察检查、突然关门）发生频率远高于管理员手动更新 `Venue.status` 的能力——极端情况可能 30 分钟内多轮检查导致反复开关门。`venuefeedback` 模块是异步管理员审核流程（有 PENDING/RESOLVED/DISMISSED 状态机），无法满足实时性需求。此模块提供**实时众包信号层**：用户在现场一键报告"现在关门了"，信号对其他用户即时可见。

**2026-08-11 泛化（紧急公告）**：原"仅暂停营业"泛化为 **8 类突发事件**（`ReportType` 枚举：突然检查/情况不明/暂停营业/舞池不开/突然清场/恢复营业/突然关门/禁龙），作为详情页「紧急公告」区数据源。每类携带展示文案/严重级（`Severity`：high/medium/low/recovery，前端色阶直接消费）/是否影响营业状态（`affectsStatus`，仅 SUSPENDED/RESUMED 为状态类）/TTL 小时数。**不新建表**（遵循「扩场景=扩枚举」约定），`ReportReason` 枚举删除（CHECK/UNKNOWN/CLEARED 并入新类型）。

**与 venuefeedback 的边界**（重要）：

- `venuefeedback.SUSPENDED/RESUMED` = "我不在场但认为状态信息有误"→ 异步管理员审核 → `ReportStatus` 状态机流转（各终态）
- `venuestatusreport` = "我现在就在现场，刚确认发生X"→ 实时 TTL 信号 → 自动过期；管理员采纳（状态类联动门店状态）或移除

两者共存，语义边界清晰：一个走异步审核，一个走实时众包。`venuefeedback` 不重复承担实时信号职责。

### 独立信号层（用户上报不直接改 Venue.status；采纳是唯一联动通道）

用户上报**不改变** `Venue.status` 字段。`Venue.status` 的变更权仍属管理员/认领人（`POST /venues/{id}/update`）或**报告采纳联动**（`POST /admin/status-reports/{id}/adopt`，2026-08-10 新增，2026-08-11 泛化——管理员核实属实后，状态类（SUSPENDED/RESUMED）联动门店营业状态，见下「管理端可见性」）。用户上报作为独立信号层，出现在热度接口中为"N人报告X"的众包标记。管理员在管理后台查看活跃报告（`GET /admin/status-reports`）并处置：**采纳**（信号属实 → 状态类联动 + 奖励上报者积分 + 处理结果站内信，同事务）或**移除**（虚假信号清理，soft delete 公开视图即时消失）。

### TTL 语义（按类型分级，expires_at 列）

```java
// 写入时：expiresAt = 报告时刻 + type.getTtlHours()
// 活跃判定：expiresAt > now()
```

**2026-08-11 迁移**：TTL 唯一事实源从 Service 常量（`ACTIVE_REPORT_TTL_HOURS=4` 单窗口）迁移到 **`expires_at` 列**（写入时 = createdAt + 类型 TTL，如情况不明 2h / 暂停营业 4h / 恢复营业 24h）。所有"活跃"判定统一判 `expires_at > now()`——旧单窗口无法表达分级 TTL，是本次迁移根因。活跃判定点清单（全部已迁移到 `expires_at > :now`，`now` 由 Service 层传入，**SQL 层禁止自行定义时间窗**）：

- `VenueRepository.countHeatCounters` 的 `reportcount` / `latestreporttime`（热度聚合，`now` 参数）
- `StatusReportRepository.countActiveAndLatestTime`（提交/撤销响应摘要）
- `StatusReportRepository.findAnnouncementsByVenue`（**详情页单行公告条**聚合，`now` + `includeExpired` 参数——2026-08-20 分窗参数化后仅 `includeExpired=false`（活跃视图）属本清单；`includeExpired=true`（公告页历史视图）不在此列，见「紧急公告区」）
- `VenuePostRepository.findDetailStats` 的 `hasmyreport` EXISTS（详情页个人已报告标记）——**历史实现只过滤 `deleted = false` 漏 TTL 过滤**，与热度聚合口径不一致：TTL 过期后 `activeReportCount` 归零但 `hasMyStatusReport` 恒真，详情页"已报告·补充"按钮永不还原（用户必须手动撤销）。此为修复根因，新增活跃判定查询时必须对照本清单。
- `StatusReportRepository.findActiveReports` / `countActiveReports`（管理端列表/计数，`now` 参数）
- `StatusReportRepository.countClustersByVenueAndType`（同类型聚簇计数，`now` 参数，2026-08-11）

**例外（2026-08-12 明确，2026-08-20 扩展）**：`StatusReportRepository.findRecentByVenue`（门店突发事件列表）**不在此清单**——它是"事实明细"而非"活跃信号"视图：过期报告仍需展示（`expired` 标注）。**2026-08-20 起该查询无任何时间窗口**（旧实现曾以展示窗口 `created_at >= now - recent-history-hours` 裁剪，超窗历史不可见——2026-08-12 修复了 TTL 硬套但保留了窗口，属半成品，本次移除），范围 = 未撤销（deleted=false）的全部报告，时间维度由 Service 层逐行按 `expires_at` 列标注过期。凡新增"活跃视图"查询对照本清单，凡"明细/历史视图"查询不得套用活跃判定、不得引入时间窗口（详见「门店突发事件列表」根因）。

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/venues/{venueId}/status-reports` | 需登录 | 上报突发事件（body 可空=快速上报默认 SUSPENDED，或含 type/occurredAt/note；**2026-08-11 守卫**：SUSPENDED 对非营业门店拒绝 1010、RESUMED 对营业中门店拒绝 1012、SITUATION_UNCLEAR 必填 note 拒绝 1011，事件类不受存储态约束，见下「提交守卫」） |
| POST | `/venues/{venueId}/status-reports/cancel` | 需登录 | 撤销我的上报（软删除） |
| GET | `/venues/{venueId}/status-reports` | 公开 | 门店最近突发事件列表（**无时间窗口**：全部未撤销报告含已过期带 `expired` 标注，倒序上限 20，2026-08-10 新增 / 2026-08-12 含过期 / 2026-08-20 移除展示窗口，见下「门店突发事件列表」） |
| GET | `/venues/{venueId}/status-reports/announcements?includeExpired=` | 公开 | 紧急公告聚合（活跃 + 已采纳按类型聚簇，严重级降序；`includeExpired=false`（默认）= 活跃视图（详情页入口条），`true` = 历史视图（公告专属页列表，含已过期），2026-08-11 新增 / 2026-08-20 分窗参数化，见下「紧急公告区」） |
| GET | `/status-reports/mine?venueId=` | 需登录 | 我的全部突发事件上报（用户级资源，顶层路径；venueId 可选，2026-08-06，见下「我的上报记录」） |
| GET | `/admin/status-reports?page=&size=&type=` | ADMIN | 活跃突发事件列表（跨场所分页倒序，type 可选筛选，2026-08-10，见下「管理端可见性」） |
| POST | `/admin/status-reports/{id}/remove` | ADMIN | 移除突发事件（soft delete + REMOVED 标记，公开视图即时消失，幂等） |
| POST | `/admin/status-reports/{id}/adopt` | ADMIN | 采纳突发事件（状态类联动门店状态 + 奖励上报者积分（情况不明除外）+ 处理结果站内信，同事务，幂等） |

**提交守卫（2026-08-11 泛化，状态类 vs 事件类）**：
- **SUSPENDED**（暂停营业）仅对声称营业（OPEN）门店有决策意义——存储态声称非营业（RENOVATING/CLOSED/SUSPENDED/CEASED）时抛业务错误 1010（「该门店当前为X，无需报告暂停营业」），置于限频检查之前（无效请求快速失败，不消耗限频额度）。**根因**：前端报告操作状态机 2026-08-10 把同一语义塌缩为 `status === 'CEASED'` 单值特判，遗漏 SUSPENDED 等其余非营业态；本守卫保证 API 契约与前端 UI 语义一致（防绕过）。
- **RESUMED**（恢复营业）与 SUSPENDED 对称——仅对声称非营业门店有意义，OPEN 门店报告恢复营业自相矛盾（业务错误 1012）。
- **事件类**（突然检查/舞池不开/突然清场/突然关门/禁龙/情况不明）不受存储态约束（非营业门店同样可能突发检查/清场）。
- **SITUATION_UNCLEAR**（情况不明）信息量最低、噪音高危：提交必须携带补充说明（业务错误 1011，`note` 非空）。

**限频（2026-08-11 扩展）**：滑动窗口每用户每小时最多报告 5 个不同场所（`countDistinctVenuesByUserIdSince`）+ 每用户每日最多 10 条（`countReportsByUserSince`，当日 0 点起——批量刷同批门店由每日上限兜底）。

### 管理端可见性（2026-08-10 新增，落实"管理员可在管理后台查看活跃报告"约定）

**根因（用户反馈）**：暂停报是实时众包信号（公开 4h TTL），但管理端零可见性——`admin-reports` 页只查 venuefeedback，`pending-count` 只数 PENDING 反馈；用户上报暂停后管理员**收不到任何提醒、也看不到记录**（虚假信号无处置通道，只能等 TTL 过期）。设计早已预留管理端查看（「独立信号层」L693），"后续约定"一直未落地。

- **列表 `GET /admin/status-reports`**：TTL 窗口内全部活跃报告，按时间倒序分页（PageRequest ≤100），`type` 可选筛选（2026-08-11，服务端库内过滤）。管理端上下文与公开列表的差异：① 上报者**真实昵称 + userId**（不脱敏——管理员需识别上报者）；② 携带 `note`（补充说明，审核安全约定"note 仅管理端可见"，公开响应禁止返回，见 L1897）；③ JOIN `qwt_venues` 取场所名；④ `peerCount` = 同店同类型活跃信号数（`countClustersByVenueAndType` 聚簇，众报置信度，管理端「N人报」显示）。`findActiveReports` 原生 SQL + 投影接口（全小写别名），countQuery 与主查询同谓词
- **移除 `POST /admin/status-reports/{id}/remove`**：soft delete + `adminAction=REMOVED`（与用户自撤同软删语义，操作者是管理员）；移除后所有"活跃"查询立即过滤（公开视图即时消失，无需等 TTL）；同事务失效 `venueHeat` 缓存。幂等：已处置/不存在静默成功。语义 = **清理虚假/失效信号**（无副作用）
- **采纳 `POST /admin/status-reports/{id}/adopt`**（2026-08-10 新增，2026-08-11 泛化）：管理员核实**事件属实**后的处置（区别于移除的虚假信号清理）。同一事务完成：① **状态类联动门店营业状态**——SUSPENDED 经 `VenueService.markSuspendedByReport`、RESUMED 经对称的 `VenueService.reopenByReport`（写 `VenueStatusLog` 变迁日志 + 逐出 venue/hotVenueIds 缓存；目标态已一致时幂等跳过不写冗余日志）；**事件类不改状态**；② 报告处置（soft delete + `adminAction=ADOPTED`——**公告区保留展示至 TTL 过期并带"已核实"标记**，与移除的即时消失语义区分）；③ **积分奖励**上报者（userId 非空，经 `PointsService.rewardStatusReport`，来源 `STATUS_REPORT_REWARD`，流水幂等键兜底并发；匿名不发；**SITUATION_UNCLEAR 不设奖励**——信息量最低、噪音高危，防价值错配）；④ **处理结果站内信**（`STATUS_REPORT_RESULT`，同事务、幂等、匿名不通知，正文含类型 + 状态类结论）。已处置（软删）/不存在幂等返回。**无"已处理"状态机**——实时信号处置语义 = 采纳（属实）或移除（虚假）两动作，均为 soft delete 收尾；状态回开走既有 `updateVenue`（认领人/管理员）
- **计数 `countActiveReports`**：TTL 窗口内活跃报告总数；与 venuefeedback PENDING 计数经 `/admin/reports/pending-count` 合并为**管理端上报待办总数**（FAB「上报管理」红点数据源，2026-08-10 扩展口径——两类上报任一非空即亮；处置/过期后自然归零）
- **移除不通知上报者**（2026-08-10 决策）：与用户自撤同语义（记录从「我的上报」消失，无回传通道）；误报清理属运营动作，若需"移除告知"后续按「处理结果站内信」约定补发。**采纳必须通知**（`STATUS_REPORT_RESULT` 站内信）——上报被核实采纳且用户获得积分，属「状态流转对用户有结果」范畴，与 feedback 采纳通知同一长期约定
- **仅活跃报告入管理视图**：TTL 过期信号已自动从公开视图消失，无需管理处置（不展示历史/已过期列表）

### 门店突发事件列表（GET /venues/{venueId}/status-reports，2026-08-10 新增，2026-08-11 泛化，2026-08-20 去窗口）

公告页「最近的突发事件」明细数据源（公开读，无需登录）。**根因（需求 2026-08-10）**：报告是社区信号动作，用户报告前需要看到"已有多人报告"的明细才能建立信任——原实现只有聚合计数（`activeReportCount`）没有明细，前端系统弹窗只能承载纯文本确认、无法展示列表。本接口补齐"门店级报告的公开读路径"。

**根因链（2026-08-12 → 2026-08-20 两轮修复）**：
- **2026-08-12**：旧实现把**活跃判定**（`expires_at > now`，TTL 语义）错误复用于**事实明细列表**——TTL 过期只代表信号失效（不计入活跃计数/当前公告区），不代表报告事实消失。硬套活跃判定后，报告过期即从列表消失，用户回看时看到空列表：无法区分「从未有人报」与「报过但已过期」，社区信任信号丢失；且与「我的上报记录」（含已过期 + `active` 标注）的既有契约口径不一致。**修复：列表展示 = 未撤销的报告事实（活跃 + 近期过期），过期由 `expired` 字段标注。**
- **2026-08-20（本次）**：上述修复保留了展示窗口 `created_at >= now - recentHistoryHours`（默认 48h）——窗口外历史事实仍被裁剪，门店（如测试门店）记录全部超窗时列表依旧全空，与"公告页 = 报告事实历史视图"语义冲突（用户回看社区历史必须可见全部记录）。**根因**：把"信号新鲜度"维度（TTL/展示窗口）错误套在"事实历史"视图上——历史事实只应被「非事实」（撤销/处置）裁剪，时间维度逐行标注即可。**修复：移除时间窗口，范围 = 未撤销（deleted=false）全部报告（含已过期与超窗历史），倒序，Service 层 `.limit(RECENT_REPORT_LIST_LIMIT=20)` 防无限增长（SQL 不写 LIMIT，避免方言绑定）；过期判定仍在 Service 层按 `expires_at` 列完成（TTL 唯一事实源 = 列）。**

- **范围**：无时间窗口的全部未撤销报告（撤销是用户主动收回、处置属内部语义，已撤销/已处置 `deleted=true` 不进列表，公告区聚合单独消费），按 `createdAt` 倒序，上限 20 条；`expired = !expires_at.isAfter(now)`（活跃判定全局口径：`expires_at > now` 为活跃，边界相等视为过期），前端灰显 + 「已过期」徽标
- **实现**：`StatusReportRepository.findRecentByVenue(venueId)` 原生 SQL LEFT JOIN `qwt_users` 取昵称（LEFT JOIN：用户异常态回退匿名，不因关联缺失丢行），投影含 `expires_at`（供 Service 判过期）；`mine` 标记由 `UserContext.getCurrentUserId()`（可空，未登录恒 false）对比行 `user_id` 得出
- **隐私**：`reporterName` 脱敏（`maskNickname`：首字 + "**"，无昵称回退「舞友」）——保护用户身份隐私的同时保留"社区已有多人报告"的信任信号
- **响应**：`StatusReportListItem`（id/reporterName/type/typeDisplay/severity/createdAt/**expired**/mine），typeDisplay/severity 取自 `ReportType` 枚举（前端不再自持文案/色阶映射）
- **死配置清理**：`app.status-report.recent-history-hours` 与 `StatusReportProperties`（唯一字段即该窗口）随窗口移除一并删除（禁死配置/死代码）

### 紧急公告区（GET /venues/{venueId}/status-reports/announcements，2026-08-11 新增，2026-08-20 分窗参数化）

详情页「紧急公告」入口条与公告专属页（`pages/venue-announcements`）列表数据源（公开读，无需登录）。展示 = **活跃信号（deleted=false）+ 已采纳信号（deleted=true 且 adminAction=ADOPTED，带"已核实"标记）** 按类型聚簇摘要；移除（REMOVED）信号不展示。2026-08-11 前端拆页：同一聚合接口被两处消费——详情页取首条派生单行入口条（severity 色点 + 「紧急公告 · 最紧急类型摘要」），公告专属页全量渲染列表（列表层）+ 最近突发事件明细（详情层，`GET /venues/{id}/status-reports`）。

**根因（2026-08-20 分窗参数化）**：同一接口的两处消费方对时间窗口的语义要求**相反**——详情页公告条 = "当前紧急信号"（过时信号不得误导为当前紧急，应只看 TTL 窗口内）；公告专属页①区 = "历史事实摘要"（用户回看社区历史必须含已过期记录）。旧实现把 TTL 窗口（`expires_at > now`）硬套在整接口上：门店信号全部过期时公告页①区恒空，与"历史所有记录可见"语义冲突（同「门店突发事件列表」根因：信号新鲜度 ≠ 事实历史）。**修复：`includeExpired` 参数化**——`false`（默认）= 活跃视图（仅 TTL 窗口内，详情页公告条）；`true` = 历史视图（全部未撤销 + 已采纳，含已过期，公告页①区）。**长期规则：凡"同一接口被活跃视图 + 历史视图双消费"，时间窗口必须由消费方显式选择（参数化），禁止按单方语义硬编码。**

- **聚合**：`StatusReportRepository.findAnnouncementsByVenue(venueId, now, includeExpired)` 按 `(venue_id, type)` 聚簇（COUNT / 已采纳数 / MAX(createdAt)），Service 层组摘要 `AnnouncementSummary`（type/typeDisplay/severity/count/adopted/latestAt），**按严重级降序**（HIGH→MEDIUM→LOW→RECOVERY，恢复营业语义上最后呈现）；`includeExpired=true` 时聚簇含过期记录（类型枚举有限，摘要条数有界，无无限增长风险），时效由 `latestAt` 相对时间传达
- **契约**：**不返回 note**（审核安全约定"note 仅管理端可见"，公开响应禁止携带——公告区不展示用户自由文本，规避微信审核风险）；`adopted` 驱动前端「已核实」标记；空结果 = 前端入口条/列表空态（详情页整行隐藏）

### 我的上报记录（GET /status-reports/mine，2026-08-05 新增，2026-08-06 收敛）

「我的上报记录」的用户侧数据源。**用户维度资源**（跨场所），路由放顶层 `/status-reports/mine` 而非场所子资源路径（与 `/favorites` 用户级资源模型一致，区别于 `/venues/{venueId}/status-reports` 的场所子资源）。

- **范围**：仅未撤销（`deleted = false`）记录，含已过期（TTL 外）——「已过期」记录前端标注后提醒用户可重新上报；已撤销记录不返回（撤销是用户主动收回动作，soft delete 属内部实现细节，语义上不再属于"上报记录"）
- **venueId 可选过滤（2026-08-06）**：null = 跨场所全部（个人中心「我的上报」区块）；非 null = 单门店（详情页「我的上报记录」弹窗——只展示当前门店记录，全部记录入口在个人中心）。与 `venuefeedback.listMyFeedbacks(venueId)` 的可选过滤同构——两套上报（异步审核 / 实时信号）的"个人中心全量 + 详情页单店"消费模型一致
- **实现**：`StatusReportRepository.findMyReportsByUserId(userId, venueId)` 原生 SQL JOIN `qwt_venues` 一次取回场所名称/城市/区县/地址（消除 N+1）；venueId 过滤用 `:venueId IS NULL OR r.venue_id = :venueId` 参数化传值（防注入约定见 TextSanitizer javadoc）；`active` / `expiresAt` 在 `StatusReportService.listMyReports` 按 `ACTIVE_REPORT_TTL_HOURS` 统一计算（TTL 唯一权威源，SQL 不自行定义时间窗）
- **场所软删除不回退占位**：JOIN 不过滤 `v.deleted`——记录真实性不因场所下架而消失，与 /admin/reports 的"已下架场所"占位策略有意区分（那是管理端当前列表，这是用户历史记录）
- **响应**：`MyStatusReportResponse`（id/venueId/venueName/venueCity/venueDistrict/venueAddress/createdAt/active/expiresAt），前端展示剩余时间只做 `expiresAt - now` 纯计算，不持有 TTL 常量

### 数据模型

`qwt_venue_status_reports` 表：一个用户对一个场所至多一条活跃报告（`UNIQUE(userId, venueId)`），重新上报 = upsert 覆盖（刷新 `createdAt` 续期 TTL）。撤销 = 逻辑删除（`deleted = true`），再次上报 = 恢复（`deleted = false`），复用收藏模块的逻辑删除模式。

索引：`(venueId, createdAt)` 覆盖活跃计数查询，`(userId)` 覆盖用户频率限制查询。

### Upsert 语义（软删恢复模式）

`submitReport` 使用 `findByUserIdAndVenueId`（**不限 `deleted`**）查找已有记录，与 `FavoriteService.addFavorite` 同模式：

1. 找到**活跃记录**（`deleted=false`）→ 更新字段（reason/occurredAt/note）
2. 找到**软删记录**（`deleted=true`）→ 恢复：设 `deleted=false`、刷新 `createdAt = now` 续期 TTL、更新字段。频率限制检查仅在恢复时触发（恢复 = 新报告行为）
3. 未找到 → 新建 INSERT，并发首报竞态由 `UNIQUE(user_id, venue_id)` 索引 + **原子 upsert**（`StatusReportRepository.upsertReport`，`INSERT ... ON CONFLICT (user_id, venue_id) DO NOTHING`）收口——恒 1 次往返零异常，冲突 = 另一请求已插入，幂等忽略本请求数据（2026-08-20 确定性化，替代旧「save + catch 23505 + 同事务继续查询」：PG 语句失败后事务中止 25P02，catch 后 `getActiveReportSummary` 必然 HTTP 500，与 venuefeedback 同源修复，见 15-governance 错误表）

**根因（为什么不查 `findByUserIdAndVenueIdAndDeletedFalse`）**：UNIQUE 约束 `qwt_uk_status_report_user_venue` 在 `(userId, venueId)` 上，不含 `deleted` 列——软删记录仍占用唯一槽位。若仅查活跃记录，撤销后再次上报会走到 INSERT 分支，与软删记录冲突。`FavoriteService` 的 `findByUserIdAndVenueId`（含软删）+ 恢复模式是标准做法，此模块此前遗漏了此模式导致 `AssertionFailure` 崩溃。

**`@CreationTimestamp` 属性不可变，TTL 续期必须经 JPQL 批量更新（2026-08-10 根因修复）**：`BaseEntity.createdAt` 标注 `@CreationTimestamp`，Hibernate 将其视为**不可变属性**——实体 setter（`report.setCreatedAt(now)`）在 UPDATE 时被静默忽略（WARN HHH000502，UPDATE 语句不含 `created_at` 列）。原实现"手动 setCreatedAt 刷新 TTL"从未生效：旧 `createdAt` 超出 4h TTL 窗口后，详情页 `hasMyStatusReport`（EXISTS 带 TTL 过滤）为 false、公开列表（TTL 过滤）查不到 → 用户"刚报告的记录消失"。**正确做法**：经 `StatusReportRepository.renewReport(id, now, now+ttl)`（`@Modifying(flushAutomatically=true, clearAutomatically=true)` 的 JPQL 批量更新）直写 `created_at` + `expires_at` 两列——批量更新不走实体生命周期，不受不可变约束；`flushAutomatically` 保证实体脏修改（deleted/reason 等）先落库再续期。**长期规则：`@CreationTimestamp` 字段禁止用实体 setter 改，需"续期"语义（如 TTL 刷新）时必须走批量更新**。

**首次上报 INSERT 分支的并发收口（2026-08-20 更新，旧「catch 23505 后必须 `entityManager.clear()`」指引已废止）**：旧写法在 `save()` 失败后 `entityManager.clear()` 清脏实体再继续同事务查询——这只能解决 Hibernate session 的 `AssertionFailure`，无法恢复 PostgreSQL 已中止（25P02）的 DB 事务，`getActiveReportSummary` 必然 HTTP 500。**现一律走 `upsertReport` 原子 upsert**（`INSERT ... ON CONFLICT (user_id, venue_id) DO NOTHING`，`qwt_uk_status_report_user_venue` 为唯一索引，列清单推断即可），不产生任何异常路径，无 session/事务状态恢复问题。

### 防刷机制

频率限制（`MAX_REPORTS_PER_HOUR = 5`）：滑动窗口（now - 1h），统计用户上报的不同场所数，超过阈值抛 1006。**仅对新上报生效**——已有活跃报告的续期更新不触发频率检查（否则"续期"会被误判为重复上报而拦截）。

### 审核安全

- `note` 字段存储于 DB 但**不公开返回**（`ActiveReportSummary` 仅含 `activeCount` + `latestReportTime`），仅供管理端查看（`StatusReportResponse` 含 note，后续管理接口使用）
- `ReportReason` 枚举命名避免敏感词：`CHECK`（门店检查）、`UNKNOWN`（情况不明）、`CLEARED`（清场）——不出现"警察/扫黄"等微信审核敏感词

### 缓存失效（显式 invalidate 热度缓存）

`submitReport` 和 `cancelReport` 在写入完成后调用 `venueHeatService.invalidate(venueId)` 显式失效热度缓存（热度为 `VenueHeatService` 内嵌 LoadingCache，不走 Spring `@CacheEvict`，见「查询性能优化 → 写路径缓存逐出」）。活跃报告数是热度响应的输出之一，上报/撤销后必须让其他用户及时看到最新信号。

`getActiveReportSummary()` 本身不缓存，仅供 `submitReport` / `cancelReport` 组装响应使用——热度接口已不经由它，活跃上报计数内联在热度 mega-query（`countHeatCounters`）的标量子查询中，随热度缓存整体命中/逐出。

---

