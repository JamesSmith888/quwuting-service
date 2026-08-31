# 32 — Web 管理后台登录与门店同步（2026-08-31）

> ⚠️ 维护警告：本文档记录 Web 管理后台（`quwuting-admin-web`，独立前端仓库）配套的后端能力。
> 新增/修改细节先更新本文档；AGENTS.md 索引表保持一行摘要。

## 背景与定位

「我的-管理」内容审核类留在小程序；权限敏感/批量配置类拆分到独立 Web 项目（移动端优先，浏览器直接访问）。
当前 Web 端仅实现：**账号密码登录**、**门店同步报告**。其余存量管理功能不迁移（逐步演进）。

- 前端仓库：`quwuting-admin-web`（Vue 3 + Vite + Vant 4，独立 git 提交/部署）
- **管理平台独立于小程序生态（2026-08-31 用户明确）**：登录仅账号密码通道；扫码登录（小程序码 + 小程序确认页）已从管理平台前端移除，后端 webauth 扫码接口保留待用。
- 复用现有 JWT Bearer 体系：Web 端拿到 token 后 `AuthInterceptor` 软鉴权 + `UserContext.requireAdmin()`，18 个 `/admin/**` 接口零改动
- 同域反代：nginx 静态站 + `/admin/**`、`/web-auth/**` → `localhost:8080`（前端零 CORS 改造）

## 部署拓扑（2026-08-31 调整：弃用 Cloudflare Tunnel）

```
admin.starseek.online ──DNS A 记录──> ECS(114.55.0.14) ──nginx──
   ├── /            → 静态站（SPA history 路由）
   ├── /admin/**    → 反代 127.0.0.1:8080（后端管理接口）
   └── /web-auth/** → 反代 127.0.0.1:8080（密码登录）
```

与 `api.starseek.online`（后端）、`starseek.online`（门户）并存于同一 nginx，按 server_name 路由。
**禁止**挂到 api 域 `/admin` 子路径（与后端接口前缀冲突）。HTTPS 可选：`certbot --nginx -d admin.starseek.online`。

## 密码登录（管理平台唯一通道，web-auth 包）

```
网页 POST /web-auth/password-login {username, password} → JWT → 后续 /admin/** 复用 Bearer
```

关键规则（WebAuthService）：

- 凭据 = 服务器配置 `web-auth.username`（默认 admin）+ `web-auth.password`（环境变量 `WEB_ADMIN_PASSWORD`，**空值 = 通道禁用**）；敏感值不入 git，由 systemd EnvironmentFile 注入。
- `constantTimeEquals` 防时序攻击；登录对象 = `userRepository.findFirstByRoleAndDeletedFalse(ADMIN)`（平台首个管理员）。
- 后端扫码链路（createSession/confirm/reject/poll + `qwt_web_login_sessions` V4 表）**保留待用**，前端已无入口：
  - scene 32 字符硬约束：`w` + 29 位 hex = 30 字符（曾踩 35 字符超限 bug）；严禁改长前缀或 32hex ID。
  - token 一次性下发：`findBySessionIdForUpdate` 悲观锁 + poll 取走置 null。
  - `WechatService.getAccessToken()`：内存缓存 7200s 提前 300s 刷新（synchronized 双检锁）。

## 门店同步报告（venuesync 包）

管线 `quwuting-ops/venue-opening/main.py --report --upload-report` 跑完后把报告 JSON 上报存档，Web 页读取/确认写库。

- `POST /admin/venue-sync/reports`：幂等 upsert（同 `source_id` 同 `report_date` 覆盖，生成列部分唯一索引）；summary 剔除 `_` 开头内部聚合键。
- `GET /admin/venue-sync/reports?limit=`（≤30 倒序）、`GET .../{id}`、`POST .../{id}/apply`。
- `POST /admin/venue-sync/reports/{id}/apply-item`（**2026-08-31 单条写库；09-01 放宽 + 确认放行**）：条目级「写库」按钮，
  请求 `{venueId, sourceName}` 定位报告条目（venueId + source_name 联合防同名多店误定位），
  **已匹配平台门店即可**；**单条写库 = 管理员人工确认**（`ApplyDailyOpeningRequest.forceReversal=true`）——
  EXACT/ALIAS 可反转；CONTAINED/FUZZY 低置信经管理员确认后同样允许反转（若资讯 OPEN 且平台
  CEASED/SUSPENDED），快照仍存原始 confidence（审计不失真）。管线批量/自动路径 forceReversal 恒 false
  （保守：低置信只落快照不反转）——旧管线 body 缺该字段，反序列化默认 false 兼容。
- **apply 只提交 `confidence ∈ {EXACT, ALIAS}` 且 `venue.venue_id` 非空**的条目，组装 batch 复用 `DailyOpeningService.applyBatch`（写快照 + 高置信状态反转），返回 `BatchApplyResult`。
- **状态口径（2026-08-31 明确；09-01 修订为实时）**：① 条目状态 tag = 信息源宣称的当日状态（UI「资讯·xx」）；
  ② 「平台门店」对比条 = **平台实时状态**——2026-09-01 起调 `POST /admin/venue-sync/venues/status-batch`
  批量查库（`findByIdInAndDeletedFalse` 单次往返，前端加载报告详情后拉一次）展示当前状态，
  **不再显示报告快照**（快照会过时误导：菲琳/玫瑰天堂快照 CEASED 而平台已 OPEN，用户投诉「明明营业却显示停业」）；
  ③ 详情弹层 = 实时详情（复用 GET /admin/venue-sync/venues/{id}）。
  apply 反转**单向**（仅「资讯 OPEN + 平台 CEASED/SUSPENDED」反转），**不会把营业中的门店标停业**。
- 条目字段（管线 report["results"] 镜像）：city / source_name / source_name_raw / status / confidence / alias_key / venue{venue_id,name,city,status}。
- 实体 `@Lob String summary/items` 映射 MySQL longtext（与 V5 迁移列类型一致，validate 校验）。

DB：`qwt_venue_sync_reports`（V5 迁移）。

## 管线一键拉取（venuesync 包 · VenueSyncPipelineService，2026-08-31）

Web 页「拉取数据」按钮触发，**替代手动执行 main.py**（抓取 + 匹配 + 上报报告存档）：

- `POST /admin/venue-sync/pipeline/run`：异步启动子进程 `python3 main.py --source {source} --upload-report --refresh-aliases [--refresh-snapshot] --base-url {base}`，立即返回状态；重复触发抛 `5004`（管线运行中）。
- `GET /admin/venue-sync/pipeline/status`：最近一次执行状态（state/running/startedAt/finishedAt/exitCode/durationMs/tail 日志环形缓冲 ≤200KB），前端 2.5s 轮询。
- 子进程 env `ADMIN_TOKEN` = **触发者登录 token**（controller 从 Authorization header 剥离 Bearer 透传）——复用 Web 登录态，服务器无需额外配置管线凭据；changedBy 审计语义与管线手动执行一致。
- 只跑「抓取+匹配+上报」，**不写库**——写库仍是页面「确认写库」人工动作。
- 执行前自动 `--refresh-aliases`：管理员在「映射管理」新配的映射，拉取数据时即生效，无需单独跑脚本。
- 超时 10 分钟强杀；日志读流线程独立（防管道缓冲写满死锁），工作目录 = 脚本目录（output/data 相对路径）。
- 配置（application.yaml `venue-sync.pipeline.*`，环境变量覆盖）：`python`（默认 python3）、`script`（默认 `./venue-opening/main.py`，**本地/服务器按实际路径配置**）、`base-url`（默认 http://127.0.0.1:8080）。脚本不存在或未配置 → 1001 明确报错。
- 单实例单槽位：并发槽位在 JVM 内存态，重启后自然复位。

## 手动映射别名（venuesync 包 · VenueSyncAliasService）

管理员在 Web 后台「映射管理」配置「网上门店名称（信息源店名）+ 城市 → 平台门店」映射，
等价于管线 Matcher 的别名表（ALIAS 置信度命中，优先级高于归一化精确匹配）。

- `GET /admin/venue-sync/aliases`：全部有效映射（带平台门店名，最近配置在前）。
- `POST /admin/venue-sync/aliases`：幂等 upsert（同 `city + source_name` 覆盖 venue_id/note；校验门店存在）。
- `DELETE /admin/venue-sync/aliases/{id}`：软删（保留审计痕迹，重配时恢复）。
- `GET /admin/venue-sync/aliases/export`：管线消费格式 `{city: {source_name: venue_name}}`，
  与 matcher 的 aliases.json 结构一致；门店已失效的映射自动跳过（防悬空）。
- key 口径：city 用标准城市名（对齐 `quwuting-ops/venue-opening/data/cities.json`，如「成都市」），
  source_name 用信息源清洗后店名（对齐报告 `source_name`，非 `source_name_raw`）。

管线侧：`main.py --refresh-aliases`（需 ADMIN_TOKEN）拉取 export 写 `data/aliases.json`
（matcher `_load_aliases` 下次匹配自动加载）。**顺序依赖**：先配映射 → 拉取 → 再跑匹配，
aliases 只影响下次生成的报告，历史报告条目保持管线快照不变。
**页面「拉取数据」已自动刷新别名**（子进程固定带 `--refresh-aliases`），手动跑脚本仅在调试管线时使用。

DB：`qwt_venue_sync_aliases`（PG V64 / MySQL V6 迁移；幂等 = 部分唯一索引 city+source_name）。

## 前端项目要点

- 登录：密码表单（`POST /web-auth/password-login`），未配置密码时提示无法登录；token 存 localStorage。
- 门店同步页：**「拉取数据」按钮**（管线一键执行 + 状态轮询 + 日志弹层）+ 最近报告概览（匹配率/置信度分布）+ 条目列表 + 确认写库 + 历史切换。
- **条目列表 = 决策漏斗 6 视图（2026-08-31 重构，`filterByView`）**：全部 / 匹配到的（venue 非空）/
  可直接更新（EXACT+ALIAS，即 apply 提交集）/ 精确匹配（EXACT）/ 需人工复核（CONTAINED+FUZZY）/
  未匹配（venue 空 = 新店线索）。每个 tab 带 item 级计数；未匹配/包含条目提供「映射」按钮
  一键带入城市+店名打开映射弹层；概览 stats 为 summary opening 级权威数字（含多命中差异，口径不同）。
- **平台门店对比块（2026-08-31）**：条目 venue 非空时行内渲染「平台门店」对比条
  （名称 + 状态 tag + 城市）——资讯店名与系统门店同屏对照；点击调
  `GET /admin/venue-sync/venues/{id}`（**走 /admin 前缀**——管理后台 nginx/vite 反代
  只覆盖 /admin、/web-auth，小程序 /venues 前缀打不通，2026-08-31 事故）弹层展示
  门店详情（城市/区/地址/营业时段/门票/电话/简介/更新时间）。
- **条目级写库（2026-08-31）**：命中平台门店且 EXACT/ALIAS 的条目显示「写库」按钮，
  逐条确认后调 apply-item；「可直接更新」视图每条均可单独决策，避免整报告批量误伤。
- 映射管理弹层（`src/components/AliasManagePopup.vue`）：新增/编辑（城市+网上名称+门店搜索选择
  `GET /admin/venue-sync/venues?keyword=`（走 /admin 反代）+ 备注）+ 已配置列表删除。保存后管线 `--refresh-aliases` 生效。
- 本地无后端时：`npm run dev:mock`（mock server 8082 + vite 5173 同进程，proxy 指向 mock；**沙箱下两个独立后台进程网络互不可达，必须同进程**）。
- 部署：`deploy/deploy.sh`（nginx 静态 + 反代，`admin.starseek.online` 直接对外，DNS A 记录 → ECS）；**禁止挂到 api 域 /admin 子路径**（与后端接口前缀冲突）。

## 风险与遗留

- V5 实体映射：`@Lob String` 必须配 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` 才能与迁移的 longtext 对齐（Hibernate 7 裸 @Lob 推断 tinytext → validate 启动失败，2026-08-31 事故）。
- 小程序确认页 `pages/admin-web-login/admin-web-login`：管理平台已不再使用扫码链路，页面保留待用；如需重新启用扫码，注意小程序提审时该页需为「小程序码可打开的页面」。
- 本地 8080 后端实例需重启（mysql profile）应用 V4/V5 迁移后，新接口才生效。
- 管线 `core/db.py` 仍 PG 驱动（psycopg2 + application-dev.yaml）——MySQL 迁移尾巴，**页面「拉取数据」带 `--refresh-snapshot` 时会拉错库**（默认用本地缓存快照不受影响），待修。
