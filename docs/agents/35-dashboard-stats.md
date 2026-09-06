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

## 口径定义（单一权威 = UserDailyStatsRepository 类注释，禁止散落再定义）

| 序列 | 定义 | 数据源 |
|------|------|--------|
| 注册数 | 当日 created_at 落当日、未软删、`role='USER'` 且 open_id 非 `test_` 前缀 | qwt_users |
| 打开数（打卡） | 当日 qwt_daily_checkins 去重用户 | qwt_daily_checkins |
| 真实互动数 | 当日至少在任一互动表出现过的去重用户：门店/舞伴浏览、门店/舞伴分享、表情认可、门店/舞伴收藏、邀约、关注、热度上报、纠错、标签互动 | 12 表 UNION |
| 打卡型噪音 | 当日注册用户中「注册后从未在互动全集（上述 12 表 + 消息/状态上报/招工联系/公告已读）出现」的人数 | 互动全集 NOT EXISTS |

排除项：ADMIN（运营/测试号）与 `test_` openid（开发联调号）不入任何计数——
2026-09-06 分析确认 uid=1 test_openid、uid=2 last night's stars 等 10 个噪音号。

## 后端实现（quwuting-service）

- `user/repository/UserDailyStatsRepository.java`：`countDailyStats(sinceDay)` MySQL 8
  方言 mega-query（WITH RECURSIVE 骨架补零 + 4 组 LEFT JOIN，含噪音 NOT EXISTS 子查询）。
  **勿在 PG 环境执行**（本地联调需连 application-mysql.yaml 的 RDS）。
- `user/service/AdminDailyStatsService.java`：窗口钳制 7~90，LocalDate 现算 since，
  空值兜底 0。
- `user/dto/response/AdminDailyStatItem.java`：`record(day, registered, opened,
  interactive, noisy)`。
- `user/controller/AdminUserController.java`：`GET /admin/users/daily-stats?days=30`，
  `UserContext.requireAdmin()`。

## 前端实现（quwuting-admin-web）

- `services/dailyStats.ts`：`getDailyStats(days)` + `getUserStats()`（顶部大盘卡）。
- `views/DashboardView.vue`：notice-bar 口径说明 + 4 顶卡（累计注册/今日新增/近7日活跃/
  管理员）+ 30 天三线趋势（echarts 按需：Line+Bar，注册蓝 378ADD / 打开灰虚线 B4B2A9 /
  真实互动红 E24B4A）+ 噪音占比柱（≥60% 红 / ≥40% 橙 / 其余蓝）+ 噪音文字注记
  （今日占比 + 30 天峰值日，提示对照提审日期）。
- `router/index.ts`：`/dashboard` 路由，`/` 与登录成功 redirect 到 dashboard。
- `layouts/AppLayout.vue`：TABS 增「数据看板」(bar-chart-o，4 项内) + MENUS 同步登记；
  `tabKeyOf` 加 `/dashboard` 分支。
- **禁假数据红线**：全部走 `/admin/users/daily-stats` + `/admin/users/stats` 真实接口
  （dev:mock 无此页 mock，联真实后端用 `npm run dev`）。

## 数据现状快照（2026-09-06 分析，供对照验证）

- 累计 DB 注册 295（剔 10 噪音号 ≈ 285）；微信累计 672（含游客，口径不同勿直接比）。
- 真实互动 DAU：8-25 峰值 35 → 9-4 仅 21 / 9-5 12（剔除噪音号口径）。
- 打卡型噪音：8-31 前 <10%，9-3 起 60%+（9-4 24/36、9-5 16/23）。
- 留存极差：8-31 注册 53 → 后续真实互动仅 8；9-1 注册 27 → 0。微信「新增日留存 0%」与 DB 吻合。
- 早期流量主靠 uid2（last night's stars）自分享卡片（share_from=2 占分享打开绝对多数），
  真实裂变少；8-31 后游客浏览骤降（<22/日）。

## 后续规划（P1/P2，未实施）

- 漏斗图：注册→打开→互动；游客→注册转化（NULL user 浏览 vs 注册量）监控分享引流。
- 分享裂变归因（share_from top）；注册批次留存 cohort（需日级快照或现表回溯）。
- admin-web 用户管理页（后端 /admin/users 全套就绪，前端未接）——大盘→列表→详情
  下钻链路打通后，噪音号可进用户管理标记/下线。
