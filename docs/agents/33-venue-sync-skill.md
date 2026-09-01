# 33 — 舞讯采集 Skill 数据接口（2026-09-01）

> ⚠️ 维护警告：本文档记录「去舞厅每日舞讯采集 Skill」（WorkBuddy 技能，Agent 对话式维护门店）
> 配套的后端能力。新增/修改细节先更新本文档；AGENTS.md 索引表保持一行摘要。

## 背景与定位

去舞厅是「出门前查舞厅」场景，门店营业状态每天变化（当天有舞会/没舞会、临时休息、
停业恢复），舞讯源（砂舞线报网 xianbao360 等）**每日发布当日营业清单**——平台状态
不跟进就过期（菲琳/玫瑰天堂事故：快照 CEASED 而平台已 OPEN，用户投诉「明明营业却
显示停业」）。「每日维护门店状态」= 把当日舞讯反写为平台状态，是产品核心体验保鲜动作。

现有维护机制：
1. 用户上报（venuestatusreport）→ 管理员采纳（markSuspendedByReport / reopenByReport）；
2. Python 管线（`quwuting-ops/venue-opening`）→ matcher 规则匹配 → 报告 → Web 后台确认
   写库（**仅状态反转，UNMATCHED 不建档**）；
3. 人工编辑（小程序管理入口 POST /venues、POST /venues/{id}/update）。

**本 Skill = 第四套：对话式自动化**（WorkBuddy 技能，`~/.workbuddy/skills/quwuting-venue-daily-sync/`）：
Agent 采集（网页/用户非结构化数据）→ 比对（LLM 语义 + 规则）→ 差异清单 → **用户确认** →
写库（**新增门店** + 状态反转）。与管线差异：
- **新增门店**：管线不做，本 Skill 补上（batch-create）；
- **采集来源多样**：支持用户喂任意非结构化数据（聊天记录/截图文本/口头信息），LLM 提取；
- **比对智能度**：LLM 语义比对（错别字/简称/方言）叠加管线 matcher 规则；
- **交互**：对话式差异清单 + 一键确认，不依赖 Web 后台按钮。

## 后端新增接口（venuesync 包 · AdminVenueSyncDataController / VenueSyncDataService）

### GET /admin/venue-sync/venues/export —— 候选门店按量加载

Skill 比对的数据底座。**为什么新增**：现有 GET /admin/venue-sync/venues（searchVenues）
只有 keyword 搜索、size 上限 50（listVenues MAX_PAGE_SIZE）、且走重型组装
（Reaction 徽标/浏览量/照片/热度角标对机器比对无意义）。export = 轻量直查：

- 参数：`city`（可选，精确）、`status`（可选，VenueStatus 枚举名）、`page`（默认 0）、
  `size`（默认 500，**上限 500**——Skill 按城市逐城拉取，一城一页）。
- 返回：`Page<VenueExportItem>`，条目 = `{venueId, name, city, district, address, status}`
  （id 升序稳定翻页——**Skill 候选拉取必须稳定排序**；字段命名对齐管线快照 venue_id 口径）。
- 实现：`VenueRepository.findExportPage`（JPQL，city/status 精确 + deleted=false）。

### POST /admin/venue-sync/venues/batch-create —— 批量新增门店（Skill「一键录入」）

**为什么新增**：现有 POST /venues（createVenue）单条 + changedBy=管理员（Agent 来源不可
区分）+ 无幂等 + 全字段（照片校验等 Skill 用不上）。

- 请求：`{items: [{name, city, district?, address?, status?}]}`，1~100 条
  （容器级 @NotEmpty/@Size；**元素级不挂 @Valid**——单条脏数据记 FAILED，不整体 400）。
- 语义：
  - 仅 name/city 必填（舞讯能提供的信息）；district/address 选填；status 缺省 OPEN；
    营业时段/门票/照片等字段批量建档不提供，走默认值，后续人工补全
    （「确认态 / 未经核实」哲学：批量建档只保证基础信息）。
  - **幂等**：同城（city 精确）+ 名称归一化（去空白/小写/全角括号转半角，对齐管线
    matcher._norm）判重 → EXISTED（返回已存在 venueId）；批内重复同样拦截。
  - **逐条独立事务**：外层不挂 @Transactional，save() 各自提交——单条失败不拖累整批
    （与 GeocodeService.backfillAll「禁跨批大事务」哲学一致，连接池仅 5 连接）。
  - **审计**：statusLog.changedBy = null（null = 系统/Agent 来源，人工编辑 = userId，
    与 DailyOpeningService 反转同口径——Web 后台「更新记录」按 changedBy IS NULL
    统一追踪系统/Agent 动作）。
  - 缓存失效：新建 >0 时统一失效列表缓存（venueService.invalidateVenueListCache）+
    hotIds + cityStats（CacheManager.clear，与 createVenue @Caching 同口径）。
  - **数据更新公告联动（2026-09-01，docs/agents/34）**：新建 >0 时触发
    `AnnouncementService.createDataUpdateAnnouncement(created, 0)`——SYSTEM 来源
    数据更新公告，同日防重（一天一条）；开关 announcement.data_update.enabled
    默认 false，未开启不产生公告。
- 返回：`{total, created, existed, failed, items: [{index, name, city, result
  [CREATED/EXISTED/FAILED], venueId, message}]}`，index 对齐请求 items。

### 状态更新 —— 不新增，Skill 直接复用

`POST /admin/venue-daily-openings/batch`（DailyOpeningService.applyBatch，2026-08-31）：
- 语义：仅「资讯 OPEN + 平台 CEASED/SUSPENDED → OPEN」反转（单向保守，未上榜 ≠ 停业）；
  EXACT/ALIAS 自动可反转，CONTAINED/FUZZY 需 forceReversal=true（用户确认放行）。
- 审计链完整：VenueStatusLog（changedBy=null）+ 关注者站内信 + 热度/详情/列表缓存失效。
- Skill 构造 items：`{venueId, reportDate(舞讯报告日期), sourceId(默认 xianbao360),
  status: "OPEN", confidence, forceReversal?}`。
- **避免再造一套**：状态写库逻辑唯一权威 = DailyOpeningService。

## Skill 与管线的分工

| 环节 | Python 管线（quwuting-ops） | 本 Skill（Agent 对话） |
|---|---|---|
| 采集 | 固定渠道 adapter（xianbao360/telegram） | WebFetch 网页 + 用户非结构化数据（LLM 提取） |
| 比对 | 规则 matcher（EXACT/ALIAS/CONTAINED/UNMATCHED） | LLM 语义 + 规则叠加 |
| 新增门店 | ❌ 不建档（新店线索人工处理） | ✅ batch-create 一键录入 |
| 状态更新 | ✅ applyBatch（管线/Web 后台） | ✅ 复用 applyBatch |
| 确认 | Web 后台按钮 | **表格化差异清单（四类）+ 用户确认** |
| 写库鉴权 | ADMIN_TOKEN | /web-auth/password-login 换 JWT |

两者写库语义一致（都走 applyBatch / 同源审计），重复执行无锁冲突，以最后执行为准。

## 表格化差异清单（Step 4 确认规范，2026-09-01 优化）

比对结果按「写库动作」分类为四张对比表格，用户浏览确认后直接执行：

| # | 分类 | 内容 | 确认交互 |
|---|---|---|---|
| ① | 可直接更新（100% 确定） | EXACT/ALIAS + would_reverse | 回复「执行」全部反转，不逐项确认 |
| ② | 需用户确认（低置信） | CONTAINED + would_reverse（需 forceReversal） | 逐条确认/剔除，确认的 forceReversal=true |
| ③ | 平台未维护（新店候选） | UNMATCHED + keyword 交叉验证无命中 | 回复「录入」一键 batch-create，可剔除个别 |
| ④ | 参考信息（无需动作） | 命中但平台已 OPEN / CONTAINED 非反转 / 平台未覆盖城市 | 仅展示不写库 |

规范：每表 ≤5 列、空表不渲染；分类主键 = would_reverse 与置信度（高置信+反转→①、
低置信+反转→②、UNMATCHED→③、其余→④）。写库命令与 items 结构见 SKILL.md Step 4。

### 批量更新标识（2026-09-01，V8）

Agent+Skill 通过 `POST /admin/venue-daily-openings/batch` 落库时，`ApplyDailyOpeningRequest`
新增可选字段 `source`（变更来源标识），写入状态审计日志 `qwt_venue_status_logs.change_source`
（V8 迁移新增列）：

- `AGENT_BATCH` —— Agent+Skill 批量落库（status-reverse 通道自动注入，qw_api.py
  `--change-source` 默认值；管理后台「更新记录」据此展示「批量更新」标签）；
- `ADMIN` —— 管理端人工写库（Web 后台 apply / apply-item / apply-selected 通道，代码内固定）；
- `null` —— 旧数据或其他系统自动变更（不强制回填，向前兼容）。

展示链路：`GET /admin/venue-sync/reversals` 返回 `VenueReversalRecord.changeSource`，
前端据此渲染来源徽标；`BatchApplyResult.ReversalDetail.source` 同步带出（审计/回滚依据）。


## 前端/调用方

调用方 = WorkBuddy Skill（`quwuting-venue-daily-sync`）：
- SKILL.md 工作流四步 + 表格化确认（四类对比表格见上节）；scripts/qw_api.py 为
  API 封装（Python3 标准库，零依赖）。
- 鉴权：`POST /web-auth/password-login`（WEB_ADMIN_PASSWORD 对应密码）→ JWT →
  Bearer 调 /admin/**；Skill 红线：本地 localhost:8080 默认，生产需用户明确确认。

## 验证

- 后端：`./mvnw -s settings-central.xml clean test-compile` 通过（新增 6 文件无编译错误）。
- Skill 脚本：`python3 -m py_compile scripts/qw_api.py` 通过。
- 端到端（本地 mysql profile 起服务后）：login → export（city=成都市）→ batch-create
  （含已存在门店验证 EXISTED）→ status-reverse（验证反转/静默跳过）。

## 风险与遗留

- **分页漂移 bug（2026-09-01 演练中发现并修复）**：`searchRanked` / `searchRankedNoLocation`
  的 `ORDER BY HEAT_SCORE DESC` 无 id tie-break——热度分大量为 0 时排序键重复，翻页边界
  漂移导致门店在页间重复/遗漏（实测 recommended 翻页拉成都 107 家只返回 81 家，漏 26 家；
  舞讯 Skill 候选拉取因此误判 10 家「假新店」；小程序默认列表翻页同样受影响）。修复 =
  两个排序补 `, v.id DESC`（对齐 searchHeat/searchNearest 既有做法），export 的
  `findExportPage` 用 `id ASC` 天然稳定不受影响。**教训沉淀进 SKILL.md**：候选拉取
  必须稳定排序（export 首选 / 公开接口带 sort=newest）。
- 批量建档门店无坐标/营业时段等精细信息 → 列表半径筛选（300km 圈，2026-09-01 起排序无邻近加成，纯热度）与时段派生对这类新店不生效，属预期（「确认态 / 未经核实」哲学）；后续人工补全或地理编码回填
  （POST /admin/venues/geocode/backfill 可一键补齐坐标）。
- export 按 city 精确匹配——舞讯城市名必须映射到平台标准词表（GET /venues/cities），
  简称（蓉/渝/杭）先在 Skill 侧归一化。
- 状态更新仍受「只反转 OPEN 方向」约束：舞讯报 CLOSED（今日休息）不落任何动作
  （快照缺失 ≠ 停业），长期停业确认走管理端人工通道——与管线语义一致。
