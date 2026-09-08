# 39 · 门店离线快照接口（Venue Snapshot）

> 维护警告：本文件是 `GET /venues/snapshot` 的后端权威文档。前端消费侧
> （离线包同步/存储/兜底渲染）见 quwuting 仓 `docs/agents/36-offline-resilience.md`。

## 契约

```
GET /venues/snapshot?since=yyyy-MM-dd HH:mm:ss   （公开读，匿名可用）
→ ApiResponse<VenueSnapshotResponse>
VenueSnapshotResponse {
  serverTime: LocalDateTime   // 客户端原样存为下次游标（禁用客户端时钟）
  venues: VenueSnapshotItem[] // 活跃门店静态子集
  removedIds: Long[]          // since 窗口内软删门店 id（全量同步恒空）
}
```

- since 空 = 全量（首次同步，当前 ~1000 行 / ~1MB JSON）；非空 = `updatedAt >= since`
  活跃行 + 同窗口软删 id。
- `VenueSnapshotItem` = VenueResponse **静态字段子集**（id/name/status/statusDisplay/
  imageUrl/description/city/district/address/坐标/营业时间/门票/舞伴费/联系方式/tags/
  defaultTags/sortWeight/updatedAt）——刻意不含动态信号（Reaction 徽标/热度角标/
  浏览量/照片列表）：强时效数据离线无意义，photos 是远程资源断网时无法加载。
- 转换复用 `VenueResponseMapper.toSnapshotItem`（JSON 列反序列化 + 系统标签合并口径
  与 toResponse 一致）；禁改走 toResponse 再抽字段（会把无展示语义的动态字段带进
  快照序列化路径）。

## 协议决策（根因）

- **时间戳游标 vs 全局版本号**：版本号要求每条写路径（创建/更新/回滚/批量导入/别名）
  都记得 bump，漏一处即客户端本地包与库静默漂移；游标靠 `BaseEntity.updatedAt`
  （@UpdateTimestamp）自动维护，零写路径侵入。
- **since 非法 → 按全量处理**：快照是幂等资源，宁可多下发也不让漂移（客户端另有
  连续失败 3 次重置游标的全量自愈）。
- **不做 gzip 压缩配置**：全量仅在客户端首次同步发生一次，增量极小；YAGNI。
- **不建 updated_at 索引**：量级 <10k 行全扫成本可忽略；到十万级再评估（Repository
  注释已注明）。

## 实现位置

- Controller：`VenueController#getVenueSnapshot`（路由 `/snapshot` 字面量优先于
  `/{id}` 变量匹配，与 `/cities`、`/suggest` 同模式，Spring PathPattern specificity 保证）
- Service：`VenueService#getVenueSnapshot`（SNAPSHOT_CURSOR_FORMAT 游标解析）
- Repository：`findAllByDeletedFalse` / `findByDeletedFalseAndUpdatedAtGreaterThanEqual`
  / `findDeletedIdsSince`
- DTO：`VenueSnapshotResponse` / `VenueSnapshotItem`（dto/response）

## 已知边界

- 不含门店别名（VenueAlias 独立表）——离线详情别名行不渲染；搜索离线兜底不含别名
  命中。
- 全量一次性下发无分页；超限（万级）后引入游标分页（幂等资源可平滑追加）。
