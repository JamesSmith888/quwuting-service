# 运营配置（opsconfig 模块，feature flag 设施）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 定位

可热更新的动态产品规则（区别于 `application.yaml` 的部署期静态配置）——运营经
管理端修改后**即时生效（缓存失效），无需发版**。当前消费方：

> **2026-09-07：管理端 UI 由小程序 `pages/ops-config` 迁往 quwuting-admin-web
> 「运营配置」（`/ops-config`，src/services/opsConfig.ts + views/OpsConfigView.vue）。
> 后端接口与契约零改动；小程序侧仅保留公开读 `GET /ops-config`（Reaction 乐观层用）。**
- Reaction「每日唯一表情」开关（`reaction.daily.single`，见 [`08-reaction-and-rating.md`](08-reaction-and-rating.md) · 「每日一票」）；
- 舞伴认可「每日一票」开关（`dancer.recognition.daily.single`，V31，默认 true）；
- 联系方式「每日首免」开关（`dancer.contact.daily.free`，**V49（2026-08-26），默认 false = 下线**——每个用户每天对「有积分门槛」舞伴首次获取联系方式免费；关闭时一律按门槛扣积分，见 [`09-dancer-and-points.md`](09-dancer-and-points.md) · 积分解锁）。

## 数据模型（qwt_ops_config，V22 迁移）

| 列 | 类型 | 说明 |
|----|------|------|
| id | bigint IDENTITY PK | 主键沿用项目惯例（SchemaIntegrityChecker 统一校验 IDENTITY） |
| key | varchar(64) NOT NULL，唯一约束 qwt_uk_ops_config_key | 配置键（代码契约） |
| value | varchar(255) NOT NULL | 配置值（布尔开关存 `"true"` / `"false"`） |
| updated_by | bigint 可空 | 最近修改人（管理端用户 ID；seed 默认行为 NULL） |
| updated_at | timestamp(6) 可空 | 最近修改时刻 |

**键即代码契约**：新增配置键必须同时——① Flyway 迁移插入默认行；②
消费方定义常量（`OpsConfigService` 或属主服务，如 `VenueStatusGuardService` / `SuspendBlastRadiusGuard`）；
③ admin-web `src/services/opsConfig.ts` 的 `OPS_CONFIG_META` 登记（含 `kind`，见下「值类型」）。
**管理端只能改值不能造键**（`setValue` 校验 key 已存在，抛 1016）——防止手滑造出
无人消费的配置。

## 服务（OpsConfigService）

- **读**：内嵌 Caffeine `LoadingCache<String, Optional<String>>`（聚合缓存同款模式：
  单飞回源 + `expireAfterWrite(30s)` 短 TTL 兜底）——`Optional` 承载"键不存在"
  （Caffeine 禁 null 值）；`isEnabled(key, defaultValue)` 提供布尔开关语义
  （"true"/"1" 忽略大小写 → true；键不存在 → 调用方默认值）
- **写**：`setValue(key, value, adminId)`——key 必须已存在（1016），保存后显式
  `cache.invalidate(key)` 即时生效（不等 TTL）

## 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/ops-config` | 公开 | 全部配置 `{key: value}` 映射，前端 feature flag 初始化（值非敏感） |
| GET | `/admin/ops-config` | ADMIN | 配置列表（含最近修改时刻），管理页渲染 |
| POST | `/admin/ops-config` | ADMIN | 更新单键（body `{key, value}`；禁 PUT/PATCH——项目 HTTP 语义） |

## 管理端入口

首页 FAB 二级菜单「运营配置」（`adminOnly`，qwt-fab 组件按 admin property 过滤）→
`pages/ops-config`：配置项列表 + 自绘开关（乐观切换 + 失败回滚 + in-flight 守卫，
同 statusWatch 模式）；修改成功后 `refreshOpsConfig()` 刷新前端公开缓存。

## 前端联动

见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「运营配置」：`services/opsConfig.ts`
（公开读 + 管理端读写 + 会话内缓存 + 单飞）、乐观层默认值语义（缓存未就绪时
用代码侧默认，服务端 toggle 响应 reconcile 兜底）。

## 值类型与管理端控件（2026-10-01）

- 配置值有三种类型：`boolean`（`isEnabled`）/ `integer`（`getInt`）/ `text`（`getValue`）。admin-web
  `OPS_CONFIG_META` 每个键必须声明 `kind`（integer 另含 `min / max / unit`），管理页据此渲染**开关**或
  **输入框 + 保存**。
- **根因**：旧管理页把所有键都渲染成开关——数值键（人工锁天数等）显示「已关闭」，点一下就把 `3` 覆写成
  `'true'`，后端 `getInt` 解析失败静默退回代码默认值，运营以为在「开关」，实际是在破坏配置。
- 当前键清单（迁移 = 默认值来源）：`reaction.daily.single`、`dancer.recognition.daily.single`、
  `dancer.contact.daily.free`、`announcement.data_update.{enabled,template,auto_offline_hours}`、
  `heat.excluded.user.ids`、`presence.collect.enabled`、`venue.status_lock.{enabled,human_open_days,
  human_closed_days}`（V25）、**`venue.suspend_guard.{min_count,max_ratio_percent}`（V36，批量暂停熔断，
  见 33 号）**、**`bulletin.content_redline.enabled`（V36，快讯红线，默认 false，见 47 号 §1.2）**。

  > 计时「二维码同步给对方」**不在此列**：它是正常功能、无运营开关（2026-10-07 用户裁决删除，
  > 见 54 号 §八）。**什么算开关**的判据：需要「一键停掉一整类用户可见行为」的能力才算；
  > 「功能正常可用」不是开关，默认开的开关只是三端各背一份无用缓存 + 一个「忘了开就静默不可用」的失败模式。

## 长期规则

1. **feature flag 语义收敛到服务端**：toggle 等写路径由后端直读配置（权威），
   前端开关只服务乐观层——前端缓存与后端不一致时以服务端行为为准
   （如每日一票的 replacedFrom reconcile）
2. **配置 schema 是代码契约**：键的增删改走发版（Flyway + 常量 + 前端登记），
   值的调整走管理端（即时生效）——禁止管理端造新键
3. **读缓存短 TTL + 写失效**：配置读可能落在高频路径（每次 Reaction toggle），
   缓存必不可少；写路径必须显式失效保证"即时生效"语义
