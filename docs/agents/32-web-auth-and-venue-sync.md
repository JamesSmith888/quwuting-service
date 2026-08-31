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
- **apply 只提交 `confidence ∈ {EXACT, ALIAS}` 且 `venue.venue_id` 非空**的条目，组装 batch 复用 `DailyOpeningService.applyBatch`（写快照 + 高置信状态反转），返回 `BatchApplyResult`。
- 条目字段（管线 report["results"] 镜像）：city / source_name / source_name_raw / status / confidence / alias_key / venue{venue_id,name,city,status}。
- 实体 `@Lob String summary/items` 映射 MySQL longtext（与 V5 迁移列类型一致，validate 校验）。

DB：`qwt_venue_sync_reports`（V5 迁移）。

## 前端项目要点

- 登录：密码表单（`POST /web-auth/password-login`），未配置密码时提示无法登录；token 存 localStorage。
- 门店同步页：最近报告概览（匹配率/置信度分布）+ 条目列表（置信度 Tab 筛选）+ 确认写库 + 历史切换。
- 本地无后端时：`npm run dev:mock`（mock server 8082 + vite 5173 同进程，proxy 指向 mock；**沙箱下两个独立后台进程网络互不可达，必须同进程**）。
- 部署：`deploy/deploy.sh`（nginx 静态 + 反代，`admin.starseek.online` 直接对外，DNS A 记录 → ECS）；**禁止挂到 api 域 /admin 子路径**（与后端接口前缀冲突）。

## 风险与遗留

- V5 实体映射：`@Lob String` 必须配 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` 才能与迁移的 longtext 对齐（Hibernate 7 裸 @Lob 推断 tinytext → validate 启动失败，2026-08-31 事故）。
- 小程序确认页 `pages/admin-web-login/admin-web-login`：管理平台已不再使用扫码链路，页面保留待用；如需重新启用扫码，注意小程序提审时该页需为「小程序码可打开的页面」。
- 本地 8080 后端实例需重启（mysql profile）应用 V4/V5 迁移后，新接口才生效。
- 管线 `core/db.py` 仍 PG 驱动（psycopg2 + application-dev.yaml）——MySQL 迁移尾巴，`--refresh-snapshot` 会拉错库，待修。
