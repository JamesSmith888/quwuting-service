# 运营配置（opsconfig 模块，feature flag 设施）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 定位

可热更新的动态产品规则（区别于 `application.yaml` 的部署期静态配置）——运营经
管理端修改后**即时生效（缓存失效），无需发版**。当前首个消费方 = Reaction
「每日唯一表情」开关（`reaction.daily.single`，见 [`08-reaction-and-rating.md`](08-reaction-and-rating.md) · 「每日一票」）。

## 数据模型（qwt_ops_config，V22 迁移）

| 列 | 类型 | 说明 |
|----|------|------|
| id | bigint IDENTITY PK | 主键沿用项目惯例（SchemaIntegrityChecker 统一校验 IDENTITY） |
| key | varchar(64) NOT NULL，唯一约束 qwt_uk_ops_config_key | 配置键（代码契约） |
| value | varchar(255) NOT NULL | 配置值（布尔开关存 `"true"` / `"false"`） |
| updated_by | bigint 可空 | 最近修改人（管理端用户 ID；seed 默认行为 NULL） |
| updated_at | timestamp(6) 可空 | 最近修改时刻 |

**键即代码契约**：新增配置键必须同时——① Flyway 迁移插入默认行；②
`OpsConfigService` 定义常量；③ 前端 `services/opsConfig.ts` + 管理页描述表登记。
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

## 长期规则

1. **feature flag 语义收敛到服务端**：toggle 等写路径由后端直读配置（权威），
   前端开关只服务乐观层——前端缓存与后端不一致时以服务端行为为准
   （如每日一票的 replacedFrom reconcile）
2. **配置 schema 是代码契约**：键的增删改走发版（Flyway + 常量 + 前端登记），
   值的调整走管理端（即时生效）——禁止管理端造新键
3. **读缓存短 TTL + 写失效**：配置读可能落在高频路径（每次 Reaction toggle），
   缓存必不可少；写路径必须显式失效保证"即时生效"语义
