# 平台授权的资源级权限体系

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件是资源授权的唯一权威；详细规则写在这里，AGENTS.md 只保留一行索引。

---

## 定位与边界

权限分为两层：

1. **全局角色**：`UserRole.ADMIN / USER`。`ADMIN` 是真实平台超级管理员，拥有平台治理能力。
2. **资源授权**：普通 `USER` 经超级管理员授权后，只能管理指定 `VENUE / DANCER` 的明确能力。

禁止新增 `VENUE_ADMIN`、`DANCER_ADMIN` 等全局角色。被授权用户仍是 `USER`，前端展示头衔
“平台资料管理员”只是展示文案，不进入 `qwt_users.role`，也不能进入审核、用户管理、运营配置、
授权他人等平台治理入口。

资源授权表示“允许调用某项资源接口”，不自动等于资源所有者、收益人或舞伴本人。联系方式解锁
豁免、收益归属、通知接收者、自赠判断等业务规则不得因具备编辑权限而自动改变。

## 数据模型

PostgreSQL `V62__resource_access.sql` 与 MySQL `migration-mysql/V2__resource_access.sql` 同步新增：

- `qwt_resource_grants`：用户、资源类型/ID、状态、来源、有效期、授权/撤销信息；同一
  `(subject_user_id, resource_type, resource_id)` 唯一，重新授权复用原行。
- `qwt_resource_grant_permissions`：授权包含的具体能力，主键 `(grant_id, permission_code)`。
- `qwt_resource_grant_audits`：只追加审计，保存授权、调权、重新授权、撤销前后快照，不软删。

生效条件统一为：`status=ACTIVE`、未软删、`valid_from` 已到且 `valid_until` 未到。到期是读时
派生状态，不依赖定时任务改行。撤销必须填写原因。

`Venue.claimedBy` 仅表示认领摘要，`Dancer.createdBy` 仅表示创建来源/历史责任人；二者都不是
访问控制事实源。V62 已将存量认领人和历史非管理员舞伴创建人回填为 grant。

## 能力矩阵

| 能力 | 资源 | 允许动作 | 不包含 |
|------|------|----------|--------|
| `VENUE_PROFILE_EDIT` | 门店 | 编辑基础资料，详情 `canManage` | 新建、审核、平台治理 |
| `VENUE_POST_MANAGE` | 门店 | 维护门店动态 | 其他门店或平台公告 |
| `VENUE_PHOTO_DELETE` | 门店 | 删除该店照片 | 照片审核 |
| `DANCER_PROFILE_EDIT` | 舞伴 | 编辑资料及联系方式 | 创建、状态审核、信息核验 |
| `DANCER_MEDIA_MANAGE` | 舞伴 | 上传/删除相册与视频 | 媒体审核 |
| `DANCER_SERVICE_MANAGE` | 舞伴 | 增改下架服务范围 | 其他舞伴服务 |
| `DANCER_GATE_MANAGE` | 舞伴 | 设置资料相关积分门槛 | 资产归属；必须与资料或媒体能力同授 |
| `DANCER_DEMAND_RECORDS_READ` | 舞伴 | 查看邀约明细 | 联系方式、openId、平台邀约工作台 |

管理台默认包：门店仅授 `VENUE_PROFILE_EDIT`；舞伴默认授资料、媒体、服务、门槛，邀约明细作为
敏感权限单独勾选。数据库始终保存具体能力，不保存权限包名。

## 服务端入口

- `ResourceAccessService` 是资源权限判定唯一入口：ADMIN bypass；普通用户查询当前有效 grant。
- `ResourceGrantService` 负责管理员授权、整体替换能力、重新授权、撤销和审计。
- 所有安全检查必须在 Service 层。前端能力字段和按钮隐藏只负责体验，不能代替后端 guard。
- `/admin/resource-grants`：分页查询、POST 创建/调整；`/{id}/revoke` 撤销；`/{id}/audits` 审计。
- `/user/me/managed-resources`：当前用户有效授权的批量资源摘要和 capabilities。
- `/dancers/{id}/services...`：资源级服务范围写接口；旧 `/admin/dancers/...` 保留兼容并调用同一 Service。

门店认领审核通过时，必须先以 `PESSIMISTIC_WRITE` 锁定门店行，再将 `claimedBy` 摘要和
`source=CLAIM` grant 在同一事务落库，防止并发批准生成两份有效授权。授权表切换后禁止再以
`claimedBy/createdBy` fallback 放行，否则撤权会失效。

## 缓存与性能

权限是用户态，禁止进入门店/舞伴公共缓存。`ResourceAccessService` 使用 30 秒本地 Caffeine
快照，快照携带 `validFrom/validUntil` 并在每次命中时按当前时间判定，预约生效和自然到期没有
TTL 越权窗口；授权、调权、重新授权、撤销均在事务提交后按 userId 精确失效。详情能力由用户态路径下发；
“我维护的资料”批量查询门店/舞伴，禁止逐条 N+1。

当前服务为单实例，本地失效可立即收敛；扩展为多实例前必须换成共享缓存或广播失效，不能仅依赖 TTL。

## 合规文案

前端管理视角使用“平台资料管理员”“我维护的资料”“已获平台授权”，不使用“商户后台”“店主”
“个人主页管理员”，也不把普通用户伪造成真实 `ADMIN`。

如果被授权人实际是外部门店人员或舞伴本人，单纯更换头衔不能改变“用户直接管理公开内容”的审核
事实。此类主体应扩展为 `REVIEW_REQUIRED` 变更申请，由平台审核后发布；当前直接维护模式仅面向
平台授权内容人员。创建资源、公开审核和平台治理能力始终只属于真实 ADMIN。

## 验证

- ADMIN bypass；有效、未生效、到期、撤销；跨资源/跨能力拒绝。
- 相同授权请求幂等，不重复写审计；撤销原因必填，撤销后立即拒绝。
- 认领审批原子写 grant；V62 回填后原认领人/历史创建人行为等价。
- 直接调用门店更新、舞伴资料/媒体/服务/门槛/邀约明细必须按 capability 隔离。
- `./mvnw test -Dspring.profiles.active=dev` 验证 PostgreSQL Flyway、Hibernate validate 与 SchemaIntegrityChecker；
  MySQL profile 至少运行应用上下文 + SchemaIntegrityChecker，验证独立迁移目录同步演进。