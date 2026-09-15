# 34 — 全局公告系统（2026-09-01，设计定稿）

> ⚠️ 维护警告：本文档记录「全局公告」能力的设计契约（数据模型 / 接口 / 管理后台 /
> 小程序端 / 数据更新公告触发链路）。新增/修改细节先更新本文档；AGENTS.md 索引表
> 保持一行摘要。**本设计已与用户确认四项关键决策（见「决策记录」），实现时以本文档为唯一事实源。**

## 背景与定位

去舞厅需要向全体用户传达两类信息：

1. **运营公告**（人工发布）：版本更新、规则调整、活动通知；
2. **数据更新公告**（可自动触发）：今日舞讯更新（新增 N 家门店、M 家营业状态变化）。

**设计判断：不建两套系统。** 公告实体 + 管理/发布/已读链路为同一套能力，差异仅体现在
`source` 字段（`MANUAL` 人工 / `SYSTEM` 系统）与一条「数据更新钩子」——数据更新公告
= 系统在发布页填好模板并自动发布，复用同一套存储与展示。

管理面在 **Web 管理后台（quwuting-admin-web，admin.starseek.online）**，非小程序内；
小程序端只消费（入口 + 列表 + 详情渲染）。

## 决策记录（2026-09-01 用户拍板）

| 决策点 | 结论 |
|--------|------|
| 小程序端 Markdown 渲染 | **towxml**（生态最成熟，代码高亮/表格/图片全支持）——**P0 风险：Skyline 兼容性必须真机验证**（见「风险与降级」） |
| 公告入口 | **首页公告条（v3 悬浮式 + 可关闭：同日不重显、次日/新公告回归；关闭 ≠ 已读；悬浮条存在时内容区让位——收藏/城市列表首项不被遮挡，状态卡存在时由状态卡让位）+ 我的页「公告中心」入口**，双入口 |
| 数据更新公告触发 | **自动 + 手动双通道**：venuesync 写库成功后自动生成 SYSTEM 公告（同日防重），管理员也可在后台手动发布同类别公告 |
| 已读机制 | **已读回执表**（user_id × announcement_id 唯一），支持未读红点与阅读率统计 |
| **触达等级（2026-09-15 用户拍板，根因修复）** | 新增 `touch_level`：**未读徽标 = 「需触达（ALERT）」公告的未读**；流水类公告（数据更新 / 每日舞讯）落 `SILENT` ⇒ 不再计入未读，但**仍可在公告中心查看、置顶仍进首页公告栏**——把「可见性」与「未读债务」拆成两件事 |
| **未读收敛通道（2026-09-15 用户拍板）** | 新增 `POST /announcements/read-all`：用户在公告中心主动点「全部已读」，一次点击清零。**仍不做**「进入列表即全读」（公告是运营内容，不替用户做已读决定；与站内信的差异保持不变） |

## 数据模型（MySQL 迁移 V7，qwt_ 前缀）

> 迁移写入 `db/migration-mysql/V7__announcements.sql`。时间戳一律 Java 侧
> `LocalDateTime.now()` 写入（时间戳红线，禁 DB now()）；状态/枚举列用
> tinyint/varchar，**禁 CHECK 约束**（扩枚举免迁移）。

### qwt_announcements（公告表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AUTO_INCREMENT | |
| title | varchar(100) NOT NULL | 标题，≤ 50 字（前端限制） |
| content | mediumtext NOT NULL | Markdown 原文 |
| category | varchar(32) NOT NULL | `NOTICE` 运营公告 / `DATA_UPDATE` 数据更新 |
| touch_level | varchar(16) NOT NULL DEFAULT 'ALERT' | **触达等级**（V26，2026-09-15）：`ALERT` 计入未读 / `SILENT` 不打扰（仅可查）。未读口径的唯一判据，见「触达等级」节 |
| source | varchar(16) NOT NULL | `MANUAL` / `SYSTEM` |
| scope | varchar(16) NOT NULL DEFAULT 'ALL' | 一期仅 ALL；预留 `CITY`（城市粒度，后续扩展） |
| status | varchar(20) NOT NULL DEFAULT 'DRAFT' | 状态机枚举（STRING 存储，禁 CHECK；对齐 ReportStatus 先例）：DRAFT → PUBLISHED → OFFLINE |
| pinned | tinyint(1) NOT NULL DEFAULT 0 | 置顶（列表排序权重） |
| publish_at | datetime(6) NULL | 计划发布时间（定时发布）；NULL=创建即按草稿 |
| offline_at | datetime(6) NULL | 计划下线时间（可空） |
| published_at | datetime(6) NULL | 实际发布时间 |
| offlined_at | datetime(6) NULL | 实际下线时间 |
| operator_id | bigint NULL | 操作管理员；SYSTEM 来源 = NULL（Agent 来源先例） |
| created_at / updated_at | datetime(6) | Java 侧写入 |
| deleted | tinyint(1) NOT NULL DEFAULT 0 | 软删除（查询恒过滤） |

无业务唯一键 → 不需要软删除生成列（V1 第 3 条先例不适用）。

### qwt_announcement_reads（已读回执表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AUTO_INCREMENT | |
| user_id | bigint NOT NULL | 读者 |
| announcement_id | bigint NOT NULL | 公告 |
| read_at | datetime(6) NOT NULL | Java 侧写入 |

- 唯一索引 `(user_id, announcement_id)`（幂等标记已读的天然约束，重复插入 → 23505 幂等语义）
- 独立索引 `(announcement_id)`（阅读统计 count 用）
- 已读记录**不软删**（用户已读事实保留）；膨胀预案：单条公告发布超 N 天 + 阅读率统计归档后，
  可对超期公告的 reads 行做离线归档（本期不做，仅预留）

**未读数口径（2026-09-15 收敛）**：`count(touch_level='ALERT' 且 PUBLISHED 且已生效且非快讯) − count(该用户已读)`，
SQL 用 NOT EXISTS 子查询派生（对齐站内信 unread-count 模式）。
**SILENT 的公告恒不计入**——这是「未读徽标只增不减」的根因修复（见「触达等级」节）。

> ⚠️ **同源声明**：未读判据在**两处** SQL 中各写一遍——
> `AnnouncementRepository#countUnread`（读：徽标）与
> `AnnouncementReadRepository#markVisibleReads`（写：全部已读）。
> 两处的 WHERE 条件必须逐条对应，**改一处必须同改另一处**，否则会出现
> "点了全部已读但徽标不清零"的幽灵数字。

## 后端接口（新域 announcement 包，对齐 appfeedback 分包风格）

> 遵守项目 HTTP 约定：**仅 GET 和 POST，禁 PUT/PATCH/DELETE**。管理端写操作一律
> POST action 风格。管理端鉴权 = `UserContext.requireAdmin()`（AdminVenueSync* 先例）。

### 用户端（小程序，需登录）

| 接口 | 说明 |
|------|------|
| GET /announcements | 列表（分页倒序；pinned 优先；**read 已读事实 + unread 未读债务** 双布尔派生；含 category/source 标签）。**可选 `pinned` 过滤参数**（2026-09-05）：`true` = 仅置顶（首页公告栏数据源）；不传 = 全量（公告中心） |
| GET /announcements/unread-count | 未读数（我的页入口徽标数据源；**口径 = 需触达（ALERT）的可见未读公告**，2026-09-15 收敛） |
| GET /announcements/{id} | 详情（返回 markdown 原文 + 元信息；已下线/已删 → 404） |
| POST /announcements/{id}/read | 标记已读（幂等；详情页打开即调） |
| POST /announcements/read-all | **全部已读**（2026-09-15）：一次收敛全部未读的 ALERT 公告，幂等；返回收敛后的未读数 |

> **列表响应为什么要有 `read` 和 `unread` 两个布尔**（2026-09-15）：`read` = 已读回执事实
> （用户是否打开过详情）；`unread` = 是否构成未读债务（ALERT 且无回执）。SILENT 公告
> `read=false` 但 `unread=false`——**渲染未读点一律用 `unread`**，`!read` 会把"不打扰"
> 读成"未读"。判据单点 = `AnnouncementService#isUnread`。

### 管理端（Web 后台）

| 接口 | 说明 |
|------|------|
| GET /admin/announcements | 列表（状态/分类/来源筛选 + 分页） |
| GET /admin/announcements/{id} | 详情 / 编辑回显 |
| POST /admin/announcements/create | 创建（默认存草稿 DRAFT） |
| POST /admin/announcements/{id}/update | 更新（**2026-09-05 修订：发布中可编辑**——PUBLISHED 除 publishAt 外全字段可改并即时生效；DRAFT 全字段可改；OFFLINE 禁改） |
| POST /admin/announcements/{id}/publish | 发布（body 可带 publishAt 定时；publish_at 未到 → 状态仍 DRAFT 但置计划时间） |
| POST /admin/announcements/{id}/offline | 下线（置 OFFLINE + offlined_at；小程序端详情 404，列表不展示） |
| POST /admin/announcements/{id}/delete | 软删除（deleted=1） |
| GET /admin/announcements/{id}/stats | 阅读统计（阅读人数 = count(reads)、阅读率 = reads / **真实用户数**） |

> **触达分母口径（2026-09-15 收敛）**：分母 = `UserStatsSql.USER_SCOPE`（未软删、
> `role='USER'`、非 `test_` 开发号、非微信审核账号）= **公告的实际受众**，与数据看板
> 「累计注册」、留存分母同一个盘子（此前为「全部未软删非审核账号」，含 ADMIN 运营号与
> 开发联调号——分母口径与其它统计不一致）。详见
> [35-dashboard-stats.md](35-dashboard-stats.md)「口径单一事实源」。

### 状态机与边界

- `DRAFT → PUBLISHED`（publish）；`PUBLISHED → OFFLINE`（offline）；任意态可软删除；
  **下线不可直接回已发布**（需重新 publish，作为新一次发布记录 updated）。
- **编辑权限（2026-09-05 修订，用户拍板「发布中依旧可以编辑」）**：

  | 状态 | 可编辑字段 | 锁定字段 |
  |------|-----------|---------|
  | DRAFT | 全部（标题/正文/分类/置顶/定时发布/自动下线） | — |
  | PUBLISHED | 标题/正文/分类/置顶/自动下线（保存即对用户生效） | **定时发布 publishAt** |
  | OFFLINE | —（需重新 publish 走新发布周期） | 全部 |

  **为什么只锁 publishAt**：发布时间已生效，改到未来时刻会让公告对用户瞬间消失
  （可见性谓词 `publishAt ≤ now`）；且「定时发布」本质是**发布动作**而非公告属性，
  要改定时 = 重新安排一次发布（先下线再 publish）。其余字段都是公告自身的展示内容，
  运营纠错（错别字 / 过期标题 / 撤掉置顶）是刚需——旧契约「仅允许追加正文」让改一个
  错别字的代价变成「下线 + 重发」，代价远大于收益。静默篡改风险由 `operator_id`
  审计兜底（公告是平台官方内容，无用户 UGC 争议面）。
- **置顶口径（2026-09-05 修订，用户拍板）**：**只有 `pinned=true` 的可见公告进小程序
  首页公告栏**（`GET /announcements?pinned=true`），非置顶公告只在公告中心出现。
  首页位是强触达位，由运营用置顶显式决策——「发一条就霸屏」不合理。
  未读数口径不变（全部可见公告），否则公告中心红点会漏掉非置顶未读。
- 定时发布：`publish_at` 生效时刻的扫表任务（Spring @Scheduled 每 30s 扫
  `status=DRAFT AND publish_at<=now` → 置 PUBLISHED + published_at）。下线同理按
  offline_at 自动执行。**本期若不做调度器，则定时仅前端约定（到点前端拉列表可见），
  服务端不强转状态——实现时二选一，倾向 @Scheduled 强转（状态权威）**。
- SYSTEM 公告创建仅内部调用（service 方法），不暴露管理端创建接口的 SYSTEM 来源入口
  （管理员手动发只能选 MANUAL）。

## 触达等级 `touch_level`（2026-09-15，未读口径根因修复）

### 现象与根因

**现象**：小程序「我的 → 公告中心」入口的数字徽标长期只增不减（每日舞讯每天至少 +1），
公告中心列表每行还挂一个未读点；用户必须**逐条点进详情**才能消除，而实际阅读率很低
⇒ 徽标失效（"狼来了"），真正需要知晓的运营公告也被一起无视。

**机制层（三个零件拼出必然结果）**：

1. **未读 = 逐条确认的债务**。`unreadCount` = `count(可见公告) − count(回执)`，隐含前提是
   "每条公告都是一个需要用户确认知悉的事项"；
2. **收敛路径唯一 = 进详情页**（单条 `POST /{id}/read`），消除 N 条 = N 次点击；
3. **内容性质与触达策略脱钩**：`category` 只决定列表标签文字，`pinned` 只决定是否进首页，
   未读口径对所有非 FLASH 公告一视同仁——发布方**没有任何办法表达"这条是流水，别计未读"**。

**决策层（真正的根因）**：公告系统设计于 2026-09-01，当时的前提写得很明确——
公告 = **低频运营内容**，因此逐条已读是**正确**的（低频、每条都值得读、逐条回执还能喂阅读率）。
但 **09-09「公告分开制」**（同日可并存多条 DATA_UPDATE）与 **09-15「高置信永远自动发公告」**
之后，公告变成**日更高频流水**，前提被业务演进打破，而**未读模型没有随之演进**。
⇒ 根因不是某行代码写错，而是「未读语义」与「内容频率 / 性质」的耦合被业务演进切断后，
系统里没有任何机制保证两者同步演进。同类问题只要再出现一次（任何一类通知变成高频），
就会以同样的形态复发。

### 设计

一个字段回答一个问题：**这条公告是否构成用户的未读债务**（"用户不知道就会吃亏"的信息）。

| 值 | 含义 | 行为 |
|----|------|------|
| `ALERT` | 需用户知晓（新功能、规则调整、平台变更…） | 计入未读徽标 / 未读红点；未读即"用户欠一个知道" |
| `SILENT` | 流水 / 存档（数据更新、每日舞讯…） | **恒不计入未读**；仍出现在公告中心、仍可搜索阅读、置顶时仍进首页公告栏 |

**与另外两个维度正交、互不替代**（判据）：

- `category` = 内容**分类**（给用户看的标签）；
- `pinned` = **位置**（是否进首页公告栏这个强触达位）；
- `touch_level` = **打扰与否**（是否计入未读）。**"可见性"与"未读债务"是两件事**——
  这正是旧模型把两者混在一起才出的问题。

**缺省值 = 分类派生，且是单点**：`AnnouncementCategory#defaultTouchLevel()`
（`NOTICE` → `ALERT`，其余 → `SILENT`）。
- 请求体里的 `touchLevel` **可空**，缺省走该映射 ⇒ **自动化发布链路（Agent / Skill 直接调
  管理端接口）不传参也能落在正确档位**——否则每加一条发布通道都要记得补参数，漏一处就复发。
- 落库默认 `ALERT`（保守：新建条目未显式指定时宁可多提醒一次，也不静默丢掉触达）。
- `createDataUpdateAnnouncement`（SYSTEM 通道）显式走同一映射，**不在服务里硬写枚举**。

### 存量回填（V26）

`UPDATE qwt_announcements SET touch_level='SILENT' WHERE category <> 'NOTICE'`
——DATA_UPDATE（数据更新 / 每日舞讯）是流水；FLASH（行业快讯）本就无已读回执。
**纯口径切换、不动任何已读回执**：回填后用户侧历史未读徽标自然归零，不需要逐条补回执。

### 相关：未读收敛通道（同日落地）

即便分级后不再日常积压，只要**存在积压的可能**（长期未登录、连续多条重要公告），
用户就需要一条一次点击清零的出口——只靠"少发"避免积压，是把系统的债转嫁给用户操作。
故新增 `POST /announcements/read-all`：

- **语义 = 用户主动动作**（不是"进入列表即全读"），回执如实记录"用户声明已读"，
  与逐条点开详情得到的回执同构 ⇒ **不影响阅读率口径**；
- 只覆盖 `ALERT` 公告（SILENT 本不计未读，写回执反而污染阅读率）；
- 实现 = 一条 `INSERT IGNORE ... SELECT`（`AnnouncementReadRepository#markVisibleReads`）：
  幂等靠唯一键而非应用层"先查后插"——并发下后者必抛重复键、而异常会让事务 rollback-only，
  同事务内再统计未读就会炸；
- 返回收敛后的未读数（权威值，前端直接落 data，省一次往返）。

### 防复发（判据沉淀）

1. **未读徽标 = 需要用户知晓的信息的债务**。任何新增公告类型，先问"用户不知道会不会吃亏"：
   不会 ⇒ 落 SILENT。**流水 / 存档类内容一律不得计入未读**。
2. **判据只许一处实现**：`AnnouncementService#isUnread`（列表 unread 派生）+
   `countUnread`（徽标）。前端一律消费后端派生的 `unread`，**禁写 `!read`**。
3. **写侧与读侧两处 SQL 必须同改**（见「未读数口径」同源声明）。
4. **发布侧后果必须对运营可见**：管理端编辑页「用户提醒」控件（含后果文案）、列表「不打扰」标记。

## 数据更新公告触发链路（B 场景）



- **触发点**：venuesync 写库成功处调用 `AnnouncementService.createSystem(...)`：
  - `VenueSyncDataService.batchCreateVenues`（批量新增门店后）
  - 营业状态 batch 反转成功后（`DailyOpeningService` 权威反转处）
- **防重**：`(source='SYSTEM' + category='DATA_UPDATE' + 同一天)` 唯一约束——
  用生成列 `uk_key`（MD5 拼接 source/category/日期）实现，同一天重复同步只生成一条，
  重复调用幂等返回已存在（对齐 crowd report 幂等先例）。
- **内容模板**：模板化文案（如「今日舞讯更新：新增 N 家门店、M 家营业状态变化」），
  **模板与开关进 ops-config**（qwt_ops_config，键如 `announcement.data_update.template` /
  `announcement.data_update.enabled`），禁业务硬编码（项目红线）。
- **手动通道**：管理员在管理后台公告模块创建 category=DATA_UPDATE 公告（source=MANUAL），
  两路并存，互不干扰。

## 管理后台（quwuting-admin-web）

### 结构性动作：底部导航栏 + 功能菜单（AppLayout，已落地 2026-09-01）

现状单页结构（Login / SyncReport 两个视图，`/` 直连同步页，无导航）。**2026-09-01 用户
明确要求底部导航栏 + 功能菜单**，已实现 `src/layouts/AppLayout.vue`：

- 顶部 van-nav-bar（标题随路由 meta.title，退出登录全局收口）；
- 底部 van-tabbar（`数据看板` / `公告管理` / `更多`，tab 由路由派生 watch 同步；
  **2026-09-11 撤「门店同步」</b>，「更多」改为**设置页**（`/more`，MoreView.vue），
  不再是占用抽屉弹层）；
- 「更多」= 设置页 van-cell-group 功能清单（`MoreView.vue#MODULES` 数组登记全部功能，
  **未来新模块只加 MODULES 数组，不进 tabbar 挤占**）；
- 路由重构为嵌套结构：`/` → AppLayout → `/dashboard`、`/announcements`、`/more`；
  登录后默认跳 `/dashboard`。

#### ⚠️ Vant fixed+placeholder 布局约定（2026-09-01 根因修复，长期有效）

**事故**：AppLayout 底部 tabbar 曾出现「不悬浮视口底部（滚到列表末尾才见）+ 左移半宽
飘出屏幕」。**根因**：Vant Tabbar 在 `fixed`+`placeholder` 同时为 true 时，外层会包一层
`.van-tabbar__placeholder` 占位 div，**Vue 3 的外部 class / scoped attribute 全落在该
占位层（组件根 vnode）而非 tabbar 本体**；把 `transform: translateX(-50%)` 居中 hack
写在 `.layout-tabbar` 上 = 写在 tabbar 的**祖先**上，按 CSS 规范 transform 使该祖先成为
fixed 后代的 containing block → tabbar 不再相对视口定位（`bottom:0` 钉在文档流末尾的
placeholder 处）+ placeholder 自身 static 定位下块级靠左再左移半宽 → 左半截出屏。

**约定（后续给 fixed Vant 组件加自定义样式时必须遵守）**：

1. `fixed`+`placeholder` 组合下，class 落在 placeholder 层——该层只承担文档流占位，
   **禁止**写 transform / filter / perspective / will-change（任何一项都会劫持 fixed）；
2. 视觉样式一律 `:deep()` 命中组件本体（如 `.layout-tabbar :deep(.van-tabbar)`）；
3. fixed 元素限宽居中用标准方案 `left: 0; right: 0; margin: 0 auto; max-width: 640px`，
   **禁用** `left: 50% + translateX(-50%)` hack；
4. 改动后必须在真实浏览器验证 `getBoundingClientRect()`（悬浮性）与 `offsetParent === null`
   （containing block 未被劫持），不能只看 DOM 存在性。

### 公告管理页（M2 已落地 2026-09-01）

- **列表页**（AnnouncementListView）：状态 tabs（全部/草稿/已发布/已下线）+ 分类下拉筛选、
  分页、置顶标记、发布（含 OFFLINE 重新发布）/ 下线 / 编辑 / 删除操作（confirm 确认）、
  阅读统计弹层、新建按钮。
- **编辑页**（AnnouncementEditView，`/announcements/edit` 与 `/announcements/edit/:id` 双模式）：
  标题 / 分类 radio / 置顶 switch / **bytemd 编辑器（split 双栏编辑+预览，gfm 插件）** /
  定时发布时间（datetime-local，留空 = 立即发布）/ 自动下线时间（同款控件）；
  保存草稿 / 立即发布 / 定时发布三态。**2026-09-05 修订（发布中可编辑）**：

  | 状态 | 页面形态 |
  |------|---------|
  | DRAFT | 全字段可编辑；底部「保存草稿」+「立即发布 / 定时发布」 |
  | PUBLISHED | 标题/分类/置顶/自动下线/正文可编辑；**定时发布禁用**并提示「已发布，定时不可改（如需调整请先下线再重新发布）」；底部单一主按钮「保存修改」，提示保存后用户端立即生效 |
  | OFFLINE | 全字段禁用（后端禁改）；底部「重新发布」按钮（唯一复活通道，无需退回列表页） |

  - 置顶字段常驻说明：「置顶 = 展示在小程序首页公告栏；不置顶仅在公告中心展示」——
    让「置顶」这个动作的结果对运营可见（首页位与公告中心位的分工）。
  - **已发布公告的过期 offlineAt 回显时清空**：该值已失效（30s 调度即将强转下线），
    带着它保存必然撞「必须晚于当前时间」校验，清空后由运营重新决定。
  - 旧「已发布仅允许追加正文」契约（前端 startsWith 预检 + 后端校验）已整体移除。
- **Markdown 编辑器选型已定：bytemd 1.22**（`bytemd` + `@bytemd/vue-next` + `@bytemd/plugin-gfm`；
  编辑页为懒加载 chunk ~650KB，仅进入编辑页加载，可接受）。备选 vditor 未启用。
- services：`services/announcement.ts`（8 接口 + 类型 + 文案映射，对齐 venueSync.ts 风格）。
- 验证：`npm run build`（vue-tsc + vite）通过；浏览器简单验证通过（页面渲染 / bytemd
  编辑器 / 列表卡片 / 状态按钮）；**完整业务流程（创建→预览→发布→下线→删除）由用户自测**。

## 小程序端（quwuting）（M3 已落地 2026-09-01）

- **入口（双入口已落地）**：
  - 首页顶部公告条（index 页，**v2 2026-09-01 悬浮式 + 可关闭**）：fixed 悬浮于
    Tab 栏下方、内容区之上（top = Tab 栏实测高度 + 4px，初始加载屏障兜底 44px、
    `decideInitialTab` 就绪后补测校正），**不占文档流**（列表可见区域最大化）；
    z-index 80（高于内容浮层 41、低于弹层 1000+）；最新一条可见公告 + 未读红点
    （数据源 = listAnnouncements(0,1) 首条 read 布尔），点击进详情；未登录不渲染；
    onShow 重拉收敛已读。**右侧 x 主动关闭**：本地立即消失 + storage 持久化
    （key `announcement_banner_dismissed`，值 `{ [id]: 'YYYY-MM-DD' }`，写时清理
    非今日条目防膨胀）——**同日不重显，次日/新公告（id 变化）自动回归**；
    **关闭 ≠ 已读**（未读态保留在公告中心徽标）。状态提醒卡在公告条存在时顶部
    让位（`.status-alert-card--banner` margin-top 48px，避免标题被遮挡）。
  - 我的页「公告中心」入口（「我的」section 消息行下方）+ 未读徽标
    （数据源 = GET /announcements/unread-count）。
- **页面（已落地）**：
  - `pages/announcements/announcements`：列表（分类标签 + 置顶标识 + 未读点 + 时间，
    分页触底加载）；**未读点消费后端派生的 `unread`**（禁 `!read`，2026-09-15）；
    顶部「全部已读」动作行（仅未读 > 0 时出现）。逐条已读仍在详情页发生，
    **列表页不自动全读**（不做"进入即全读"）。
  - `pages/announcement-detail/announcement-detail`：详情（**towxml 渲染 markdown**，
    onLoad 取 id → 详情 → towxml 解析 → `<towxml nodes>` 渲染；打开即调 read 接口
    标已读，幂等失败静默）。
- **towxml 集成**：3.3.1 按需裁剪（剔除 echarts/latex/yuml 插件与目录，包体
  1.1M → 632K；parse/markdown/index.js 插件注册行同步删除对应 md.use）。
  页面 json usingComponents `towxml: "/towxml/towxml"`；theme 参数按
  当前主题传 'light'/'dark'。**⚠️ P0：Skyline 兼容性未真机验证（用户自测）。**
- 请求走 httpRequest 层 + behaviors/page-lifecycle safeSetData、禁 ES2020+；
  门禁通过：tsc（0 error）+ check:tokens（66 token 全注册）。
- 公告列表无缓存要求（低频数据），不引入缓存复杂度。

## 安全与合规

- **XSS**：towxml 自身对 HTML 转义；发布侧后端做基础白名单校验（禁 script/iframe/事件属性），
  小程序渲染端再兜底（towxml 配置 htmlToNodes 白名单）。图片域名沿用平台存储域校验
  （对齐 ImageContentValidator 先例）。
- **内容限制**：标题 ≤ 50 字、正文 ≤ 50KB（后端校验，超出 400 错误码）。
- **审计**：operator_id 留痕（对齐 AdminVenueSyncDataController 审计先例）；
  SYSTEM 来源 operator_id=NULL = 系统生成（对齐 Agent 来源 changedBy=null 先例）。
- 公告内容为管理员发布、无用户 UGC，**一期不上敏感词拦截**（个人主体政策收紧时再加）。

## 里程碑与验收

| 阶段 | 内容 | 验收 |
|------|------|------|
| M1 | 后端 announcement 域：V7 迁移 + 实体/仓库/服务 + 用户端 4 接口 + 管理端 8 接口 + 状态机/定时/防重 | 接口级 E2E 复现验证（含 23505 幂等、软删过滤） |
| M2 | 管理后台：AppLayout 侧边栏 + 公告列表页 + 编辑页（MD 编辑/预览/定时） | 浏览器走通 创建→预览→发布→下线→删除 全流程 |
| M3 | 小程序端：首页公告条 + 我的页入口 + 列表页 + 详情页（towxml）+ 未读红点 | **真机验证**：towxml 在 Skyline 下渲染 + 红点 |
| M4 | 数据更新自动公告钩子 + 模板进 ops-config + 同日防重 | 触发同步 → 自动出公告；重复同步不重复发 |
| M5 | 门禁全过：后端 `./mvnw -q clean test-compile`、前端 `npm run check:tokens` + tsc；AGENTS.md 索引同步 | 全部门禁通过 |

## 实现状态（2026-09-01，M1-M5 全部落地）

- **V7 迁移已应用**（RDS qwt_mysql，history v7 success）：双表 + DATA_UPDATE 同日防重
  生成列唯一索引 + ops_config 两键默认行（enabled=false / template）。
- **announcement 域已落地**：enums（Status/Category/Source/Scope 全 STRING 枚举）、
  实体（Announcement 大文本列 @Lob+LONGVARCHAR / AnnouncementRead 已读回执）、
  仓库（可见列表/未读数 NOT EXISTS/管理端筛选/同日查询/定时强转 UPDATE）、
  AnnouncementService（状态机 + 定时 @Scheduled 30s 强转 + createDataUpdateAnnouncement
  防重入口）、用户端 4 接口 + 管理端 8 接口。
- **E2E 全流程验证通过**（本地签发自建 ADMIN token，dev 库）：创建→列表→发布→用户端
  列表/未读/详情→已读两次幂等→未读归零→阅读统计→下线→用户端详情 404→软删→管理端
  404；XSS 拦截（`<script>` → 400）；PUBLISHED 更新锁（改标题/改原文 400、追加正文 ok）；
  定时发布保持 DRAFT（publishAt 未来）；@Scheduled 到点强转 PUBLISHED（实测 35s 内）；
  DATA_UPDATE 同日唯一索引兜底（SQL 直插第二条 Duplicate）。
- **M2 管理后台已落地**：AppLayout（底部导航 + 功能菜单）+ AnnouncementListView +
  AnnouncementEditView（bytemd 1.22）；构建通过 + 浏览器简单验证，完整流程用户自测。
- **M3 小程序端已落地**：首页公告条 + 我的页「公告中心」入口 + 列表页 + 详情页
  （towxml 3.3.1 裁剪版 632K）；tsc + check:tokens 通过；**Skyline 真机验证待用户**。
- **M4 数据更新钩子已落地（2026-09-01）**：
  - `VenueSyncDataService.batchCreateVenues`：created > 0 → `createDataUpdateAnnouncement(created, 0)`；
  - `DailyOpeningService.applyBatch`：reversals 非空 → `createDataUpdateAnnouncement(0, reversals.size())`；
  - 开关（announcement.data_update.enabled，默认 false）关闭时内部直接返回，
    同日防重幂等；注入无循环依赖（AnnouncementService 不依赖 venuesync/dailyopening）。
  - **开关默认关闭**：用户需在管理后台 ops-config 将 `announcement.data_update.enabled`
    置 true 后，同步写库才会自动生成数据更新公告。
- **M5 门禁全过**：后端 `./mvnw -q clean test-compile` ✓ + 应用启动无循环依赖 ✓；
  管理端 `npm run build`（vue-tsc + vite）✓；小程序 tsc + check:tokens ✓。
- **契约微调**：status 由 tinyint(0/1/2) 改为 varchar STRING 枚举（对齐 ReportStatus 先例，
  禁 CHECK 一致）。

### 2026-09-05 修订（用户拍板，两项契约变更）

1. **发布中可编辑**：`update` 放开 PUBLISHED 的 title/content/category/pinned/offlineAt，
   仅锁 publishAt；前端编辑页移除「仅允许追加正文」的 startsWith 预检与后端前缀校验，
   OFFLINE 态改为全字段禁用 + 页面内「重新发布」按钮。后端 `validateDraftSchedule`
   更名 `validateSchedule`（publishAt 传 null = 不参与窗口校验）。
   **未加二次确认弹窗**——用户诉求就是「编辑不该被卡」，保存即生效，由 toast
   「已保存，用户端立即生效」反馈结果。
2. **首页公告栏只出置顶**：`findVisiblePage` / `listVisible` / `GET /announcements`
   全链路新增可选 `pinned` 过滤；小程序首页 `listAnnouncements(0, 1, true)`。
   未读数口径不变（全部可见公告）。**无需数据迁移**（pinned 列 V7 已有）。

### 2026-09-15 修订（用户拍板：触达等级 + 未读收敛通道）

1. **V26 迁移**：`qwt_announcements.touch_level varchar(16) NOT NULL DEFAULT 'ALERT'`
   + 存量回填 `category <> 'NOTICE'` → `SILENT`。
2. **用户端接口**：列表项新增 `unread` 派生字段；新增 `POST /announcements/read-all`；
   `GET /announcements/unread-count` 口径收敛为**只计 ALERT**。
3. **管理端**：请求/响应 DTO 增加 `touchLevel`（请求可空 = 按分类派生）；编辑页新增
   「用户提醒」控件（分类切换自动带出该分类缺省档位，可手动覆盖）；列表页新增「不打扰」标记。
4. **小程序**：列表未读点与首页公告条未读点改用 `unread`；公告中心新增顶部「全部已读」
   动作行（仅未读 > 0 时出现；**不做成右下角悬浮按钮**——右下角 fixed 位归计时胶囊，
   项目既有契约，`check:float-corner` 门禁约束）。
5. **验证边界**：后端 `./mvnw -q clean test-compile` ✓；管理端 `npm run build` ✓；
   小程序 `npx tsc --noEmit` + `npm run mirror:js` + `npm run check`（十一道）✓。
   **V26 迁移尚未在任何库执行**（本地 develop 未起服务、生产未动），部署时随 Flyway 应用；
   `read-all` 的原生 SQL 与迁移 DDL 均属"只能真机/真库验证"的部分，未做运行期冒烟（验证红线）。

## 风险与降级（P0：towxml × Skyline）

towxml 是 WebView 时代产物，基于 wxml 递归模板渲染。**项目为 glass-easel + Skyline**，
M3 首个真机验证点即 towxml 兼容性。若递归模板在 Skyline 下不兼容，按序降级：

1. 公告详情页单独降级 WebView 渲染（app.json 页面级 `renderer: webview`，小程序支持单页降级）；
2. 若降级后体验/包体不可接受 → 换自研 MD 子集渲染（标题/粗斜体/列表/引用/代码块/图片/链接，
   公告场景覆盖 ~95%）——决策已在用户处备案（原方案选项之一）。

**验证方式**：towxml 集成后立即真机验证（几何/渲染类 bug 必须真机，项目纪律），
不通过不得进入 M4。
