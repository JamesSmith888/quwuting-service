# 40 · 消费账本服务端（Spend Ledger）

> 维护警告：本文件是 `qwt_spend_entries` 与 `/spend/*` 三个接口的**后端权威文档**。
> 域需求评估、功能取舍与前端消费侧（本地为源、仲裁、重试）见 quwuting 仓
> `docs/agents/44-spend-ledger.md`（**域权威**；本文件只写服务端契约与判据）。

## 契约

| 接口 | 说明 |
|---|---|
| `POST /spend/entries/sync` | 批量幂等上报（≤200 条/批）。同 `(userId, clientEntryId)` 存在即 UPDATE，否则 INSERT；`deleted=true` 为软删（客户端删除经墓碑携带原始 ts/amount）。**部分成功不整体回滚** |
| `GET /spend/overview?month=yyyy-MM` | 月度总览一次聚合（summary / 固定 6 类含 0 值 / 门店 TOP5 / 未关联桶 / 近 6 月趋势补零） |
| `GET /spend/entries?cursor=<epoch ms>` | 游标增量拉取（`updated_at >= cursor`，**含软删行**，单页 500；nextCursor = 本批最大 updatedAt 毫秒） |

全部接口**需登录**，`userId` 恒取登录态（禁客户端传入——数据"用户自己可见"，无公开分发口径）。

`SpendSyncResponse { accepted, rejected, rejectedIds }`：`rejectedIds` 是 2026-09-11
追加字段（见下）。**它是协议的一部分而非便利字段**——没有逐条归因，客户端只能把
"部分被拒"整批当成功处理（事故实现原因见 44 号 §21.3 ③）；向后兼容（旧客户端忽略）。

## 枚举协议字面量（本域最贵的一课）

- 落库列是 `varchar` + `@Enumerated(EnumType.STRING)`，取值 = **枚举名全大写**
  （`SpendSource { DANCE, MANUAL }` / `SpendCategory { TICKET, PARTNER, DRINK,
  SNACK, TRANSPORT, OTHER }`）。
- 上行解析**唯一入口 = `spend/enums/WireEnums.parse`**（trim + `Locale.ROOT` 大写后
  按枚举名匹配；非法返回 null ⇒ 该条判非法，**禁猜默认值**）。
- **为什么宽容读必须在服务端**：客户端版本与服务端必然存在时间差，老版本小程序
  仍在发小写 `"dance"` 且无法强制升级；只修客户端 = 存量用户永远上不了云。
  服务端是唯一能被双方即时收敛的汇聚点。
- **根因（2026-09-11）**：旧实现直接 `SpendSource.valueOf(item.source())`，把小写
  判成非法 ⇒ **每一条账目 100% 被拒** ⇒ 生产库长期 0 行；客户端又把 rejected 当
  已处理清队、状态行显示"已同步" ⇒ 用户看到的是"账本数字 0.1 秒后归零"。
  交叉门禁：`quwuting` 仓 `npm run check:protocol`（比对 TS 协议映射 ↔ Java 枚举名）。

## 表与索引

`qwt_spend_entries`（V16）：`user_id` + `client_entry_id` 幂等键，生成列
`client_dedupe = IF(deleted=0, CONCAT(user_id,':',client_entry_id), NULL)` + 唯一索引
（MySQL 无部分唯一索引的全库既有模式）；索引 `(user_id, deleted, ts)` /
`(user_id, venue_id, deleted, ts)` / `(user_id, updated_at)`。`ts` 为业务发生时刻
（结算=停止时刻、手动=记账时刻），Java 传 `LocalDateTime`（`ZoneId.systemDefault()`），
**禁 DB now()**；无 FK、无 CHECK（全库约定）。

## 判据沉淀

1. **写入侧被全量拒绝 = 静默故障的最高危形态**：接口返回 200 + `accepted=0`，
   全链路无异常、无 5xx、无告警。因此 sync 对任何 `rejected > 0` 都 **WARN 留痕**
   （含 userId 与 id 明细前 5 条）——本次事故能存活一天，就是因为没有任何一条信号。
2. **新域上线后必须观测"产物表行数"**：表长期 0 行是可被 1 天内发现的显性事实
   （本次没有做，代价是一天）。
3. **契约漂移要在两侧各留一道机器门禁**：服务端侧靠单测断言"小写/混合大小写必须被接受"
   （`SpendServiceTest`），客户端侧靠跨仓协议门禁；只留一侧都拦不住另一侧先改。

## 文件

```
spend/
  controller/SpendController.java    三个接口（@RequestMapping("/spend")）
  service/SpendService.java          sync 逐条归一化+归因 / overview 聚合 / entries 游标
  enums/WireEnums.java               协议字面量解析唯一入口
  enums/SpendSource.java             DANCE / MANUAL
  enums/SpendCategory.java           固定 6 类
  entity/SpendEntryEntity.java
  repository/SpendEntryRepository.java  原生 SQL 聚合（user_id 恒在 WHERE 首位）
  dto/*                              请求/响应 record
src/test/java/.../spend/service/SpendServiceTest.java   9 项（含小写载荷回归）
```

## 验证

`./mvnw -s settings-central.xml test -Dtest=SpendServiceTest`（Mockito，不依赖数据库）
——9 项断言覆盖：小写与混合大小写接受、未知枚举拒绝、非正金额/非法 ts/超长 id 拒绝、
部分拒绝逐条点名、空载荷与 null 请求、幂等更新、软删载荷携带原始 ts/amount。
