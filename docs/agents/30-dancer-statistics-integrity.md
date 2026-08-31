# 舞伴统计一致性与需求热度下钻

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载舞伴统计的跨写路径一致性与受保护下钻约定；新增细节写到这里，禁止写回 AGENTS.md。

---

## 解锁统计事实（2026-08-31）

### 根因

联系方式的直连解锁由 `PointsService` 写入 `qwt_points_unlocks`，邀约中转则在
`DemandRelayService` 的获批、自动发放、代找替代三条路径中直接写入同一张表。
早期每个服务各自手抄缓存失效逻辑，新增路径时只覆盖了部分缓存矩阵，形成了
“直接解锁正常、邀约发放后统计或排序暂时陈旧”的路径漂移。

### 长期约定

- `qwt_points_unlocks` 是“用户实际已获得联系方式”的唯一事实源；`PENDING` 邀约不得写入，获批/自动发放/替代发放的真实插入必须写入。
- 所有成功插入（`save` 或 `insertIfAbsent` 返回 `1`）必须调用 `DancerUnlockCacheInvalidator#afterUnlockWrite(dancerId)`；它在提交后统一逐出详情族（含 `DancerStatsService`）和该舞伴的列表缓存条目。
- 幂等跳过没有新统计输入，不调用失效器。
- 禁止新增解锁写路径后自行复制 `afterCommit` 代码；先复用失效器，并把该路径加入 `DemandRelayServiceTest` 或 `PointsServiceTest` 的“真实写入 → afterUnlockWrite”契约组。

### 展示口径

- 「解锁信息」的联系方式只计用户首次成功获取；同一用户再次获取命中幂等，不重复计入。
- 「需求热度」每次成功提交邀约都计入；同一用户再次提交也计入，用于反映持续服务意向。

## 需求热度下钻（2026-08-31）

“需求热度”是可公开的类别聚合；逐条邀约则属于用户行为记录，两者不能共享可见性。

- `GET /dancers/{id}/demand-records?category=&page=&size=` 仅具备 `DANCER_DEMAND_RECORDS_READ` 的资料管理员或 `ADMIN` 可读；服务端是最终权限边界。
- 返回 `DancerDemandRecord`：发起者昵称/头像、邀约验证消息、状态和时间，展示结构与「解锁信息 - 联系方式」详情一致；不返回联系方式或 openId。
- 类别筛选通过 `service_ids` 与 `qwt_dancer_services` 的 `JSON_TABLE` 关联完成，兼容既有逗号串模型；原生查询是 JPQL 无法表达该结构化拆解的唯一例外。
- 前端统计页只负责导航。权限失败由后端统一返回，禁止把公开统计响应扩展为明细或在客户端缓存明细。
- 服务级回归测试必须覆盖：管理员可按类别读取并批量装配用户展示资料、非管理者在执行记录查询前被拒绝、NULL 状态的直接邀约显示为“已获取联系方式”。