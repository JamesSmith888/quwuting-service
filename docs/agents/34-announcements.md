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

**未读数口径**：`count(PUBLISHED 且已生效公告) − count(该用户已读)`，
SQL 用 NOT EXISTS 子查询派生（对齐站内信 unread-count 模式）。

## 后端接口（新域 announcement 包，对齐 appfeedback 分包风格）

> 遵守项目 HTTP 约定：**仅 GET 和 POST，禁 PUT/PATCH/DELETE**。管理端写操作一律
> POST action 风格。管理端鉴权 = `UserContext.requireAdmin()`（AdminVenueSync* 先例）。

### 用户端（小程序，需登录）

| 接口 | 说明 |
|------|------|
| GET /announcements | 列表（分页倒序；pinned 优先；read 布尔派生；含 category/source 标签） |
| GET /announcements/unread-count | 未读数（首页公告条红点 / 我的页入口徽标数据源） |
| GET /announcements/{id} | 详情（返回 markdown 原文 + 元信息；已下线/已删 → 404） |
| POST /announcements/{id}/read | 标记已读（幂等；详情页打开即调） |

### 管理端（Web 后台）

| 接口 | 说明 |
|------|------|
| GET /admin/announcements | 列表（状态/分类/来源筛选 + 分页） |
| GET /admin/announcements/{id} | 详情 / 编辑回显 |
| POST /admin/announcements/create | 创建（默认存草稿 DRAFT） |
| POST /admin/announcements/{id}/update | 更新（**草稿自由改；PUBLISHED 仅允许追加正文**并刷新 updated_at，禁静默篡改已发内容） |
| POST /admin/announcements/{id}/publish | 发布（body 可带 publishAt 定时；publish_at 未到 → 状态仍 DRAFT 但置计划时间） |
| POST /admin/announcements/{id}/offline | 下线（置 OFFLINE + offlined_at；小程序端详情 404，列表不展示） |
| POST /admin/announcements/{id}/delete | 软删除（deleted=1） |
| GET /admin/announcements/{id}/stats | 阅读统计（阅读人数 = count(reads)、阅读率 = reads / 有效用户数） |

### 状态机与边界

- `DRAFT → PUBLISHED`（publish）；`PUBLISHED → OFFLINE`（offline）；任意态可软删除；
  **下线不可直接回已发布**（需重新 publish，作为新一次发布记录 updated）。
- 定时发布：`publish_at` 生效时刻的扫表任务（Spring @Scheduled 每 30s 扫
  `status=DRAFT AND publish_at<=now` → 置 PUBLISHED + published_at）。下线同理按
  offline_at 自动执行。**本期若不做调度器，则定时仅前端约定（到点前端拉列表可见），
  服务端不强转状态——实现时二选一，倾向 @Scheduled 强转（状态权威）**。
- SYSTEM 公告创建仅内部调用（service 方法），不暴露管理端创建接口的 SYSTEM 来源入口
  （管理员手动发只能选 MANUAL）。

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
- 底部 van-tabbar（`门店同步` / `公告管理` / `更多`，tab 由路由派生 watch 同步，
  「更多」为弹层入口不占用高亮）；
- 「更多」= van-popup 底部弹层 + van-grid 功能菜单（`MENUS` 数组登记全部功能，
  **未来新模块只加 MENUS 数组，不进 tabbar 挤占**）；
- 路由重构为嵌套结构：`/` → AppLayout → `/sync`、`/announcements`；
  登录后默认跳 `/sync`（原 `home` 路由名废弃为 `sync`）。

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
  定时发布时间（datetime-local，留空 = 立即发布）；保存草稿 / 立即发布 / 定时发布三态；
  PUBLISHED 态锁定标题/分类/置顶/定时并提示「仅允许在原文末尾追加」（前端 startsWith
  预检 + 后端校验兜底）。
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
    分页触底加载）；已读逐条在详情页发生（后端无 read-all，列表页不自动全读）。
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

## 风险与降级（P0：towxml × Skyline）

towxml 是 WebView 时代产物，基于 wxml 递归模板渲染。**项目为 glass-easel + Skyline**，
M3 首个真机验证点即 towxml 兼容性。若递归模板在 Skyline 下不兼容，按序降级：

1. 公告详情页单独降级 WebView 渲染（app.json 页面级 `renderer: webview`，小程序支持单页降级）；
2. 若降级后体验/包体不可接受 → 换自研 MD 子集渲染（标题/粗斜体/列表/引用/代码块/图片/链接，
   公告场景覆盖 ~95%）——决策已在用户处备案（原方案选项之一）。

**验证方式**：towxml 集成后立即真机验证（几何/渲染类 bug 必须真机，项目纪律），
不通过不得进入 M4。
