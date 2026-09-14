# 48 · 门店状态权威层级（人工判断 vs 每日舞讯）

> 一句话：**门店状态有所有权了。** 人工改状态 = 打一个有时限的「人工锁」，
> 锁内每日舞讯批量写库逐店跳过；锁到期自动回归自动同步；反复冲突的门店升级为永久豁免。
>
> 落地 = V25 迁移 + `VenueStatusGuardService`（判定唯一实现）。
> 配套 Skill = `quwuting-venue-daily-sync`；上游背景见 [`33-venue-sync-skill.md`](33-venue-sync-skill.md)。

## 1. 问题（根因，不是症状）

`qwt_venues.status` 原先是一个**没有所有权**的字段：

- 写入点共 5 个 —— 管理端编辑（`VenueService#updateVenue`）、采纳用户上报
  （`markSuspendedByReport` / `reopenByReport`）、每日舞讯两个批量通道
  （`DailyOpeningService#applyBatch` / `#applyBatchSuspend`）；
- 每个写入点都是**无条件 last-write-wins**，库里没有任何字段记录「这个值是谁的判断」。

于是必然出现两件事：

1. 管理员手工修正的门店状态，**隔天就被每日舞讯冲掉**；
2. 而舞讯是第三方整理、**并不 100% 可靠**（会漏报 / 误报），不能当唯一事实源。

另一个历史包袱：`qwt_venue_status_logs.change_source` 里 `ADMIN` 的实际含义是
**「人工确认了舞讯条目」**（Web 后台同步报告勾选应用），不等于「人工直改状态」——
两个语义挤在一个标签里，无法作为优先级判据。

## 2. 领域不变量（本方案的核心）

```
信息来源权威序（高 → 低）：   人工直改  >  外部舞讯推断

· 人工通道改状态        ⇒ 打「人工锁」（有时限）
· 外部舞讯通道写状态前  ⇒ 必须过门禁：锁内 / 已豁免 ⇒ 跳过
```

**为什么必须长在服务端，而不是采集 Skill 侧**（这是本方案区别于打补丁的关键）：

| 若在 Skill 侧维护「手工改过的店黑名单」 | 后果 |
|---|---|
| Web 后台「同步报告勾选应用」走的是另一条入口（`VenueSyncReportService` → `applyBatch`） | Skill 侧管不到，漏保护 |
| 「锁会过期」的时间语义 | 在 Skill 字典里无处安放 |
| 判定逻辑要在 Skill 里再实现一遍 | 两处实现必然漂移 |

故规则下沉为**服务端不变量**，任何调用方都受约束；Skill 侧只负责展示与如实汇报。

## 3. 数据模型（V25，`qwt_venues` 加 4 列）

| 字段 | 类型 | 语义 |
|---|---|---|
| `status_source` | varchar(20) | 状态值**归谁所有**：`MANUAL` 人工直改 / `SYNC` 外部舞讯推断 / `NULL` 旧数据或系统默认。仅表达所有权，与日志 `change_source`（表达**通道**）分工不重叠 |
| `status_locked_until` | datetime(6) | 人工锁到期时刻（NULL = 无锁）。非空且在将来 ⇒ 外部通道禁止覆盖 |
| `daily_sync_exempt` | tinyint(1) | 永久豁免（默认 0）：不参与舞讯白名单 / 未上榜差集推断 |
| `sync_note` | varchar(200) | 人工备注（改状态 / 设豁免的原因），后台可读 |

**不做历史回溯**：存量行 `status_source` 全部留 NULL（= 无锁），不用 status log 反推。
反推会把几个月前的一次性人工修正全部锁上，制造大量停在旧人工值的僵尸状态。
本机制**只对未来的修改生效**。

## 4. 门禁判定（`VenueStatusGuardService`，唯一实现）

```
外部通道逐店判定（applyBatch / applyBatchSuspend）：
  ① 已豁免（daily_sync_exempt）          → 跳过 EXEMPT（不写库/不通知/不发公告）
  ② 人工锁未过期（status_locked_until）  → 跳过 LOCKED，返回锁到期时刻
  ③ 其余                                 → 允许，写后接管所有权（statusSource=SYNC）+ 清锁
```

要点：

- **判定顺序放在「确需动作」之后**：`applyBatch` 只处理 CEASED/SUSPENDED、
  `applyBatchSuspend` 只处理 OPEN。本就不需要动作的门店不该被算成「门禁跳过」，
  否则汇报口径被污染（用户会以为锁拦掉了一大片）。
- **豁免先于锁判定**：豁免是「事实维度的例外」，锁是「时间维度的优先」，
  前者不可能被时间解除。
- **人工确认的同步条目可越锁**：`source="ADMIN"`（管理端在同步报告里勾选应用）是
  人工背书 —— **人类明确的动作永远能推翻前一个人工判断**；但同样清锁并把所有权交回
  自动同步（= 人工主动放弃人工优先权）。
- **落库后必须清锁**：否则「一次人工锁 + 一次合法的外部覆盖」会留下悬空锁，
  让后续轮次被无谓跳过。
- **调用面唯一入口**：三个字段只在 `export` 里作为**只读展示**下发，
  Skill 侧不得据此再判一遍（两处逻辑必然漂移）。

## 5. 人工锁时长：为什么不对称

锁的本质是**有时限的优先权**，不是永久黑名单 —— 人工判断也会过期（店可能真的改了），
到期即自动失效，无需清理任务。时长按人工设定到的目标状态**不对称**，
因为**错判代价不对称**：

| 人工设成 | 背后的意图 | 错判代价 | 锁时长 |
|---|---|---|---|
| `OPEN` | 舞讯漏了它 / 当日临时恢复 | 用户看到「营业」白跑一趟 | **3 天**（短） |
| `SUSPENDED` / `CEASED` | 这家确实关了 / 搬了 | 用户白跑 + 平台失信 | **7 天**（长） |
| `CLOSED` / `RENOVATING` | —— | —— | **天然免疫，锁只是无害兜底** |

最后一行值得强调：两个外部通道只动 `OPEN ↔ {CEASED, SUSPENDED}`
（`applyBatch` 只反转 CEASED/SUSPENDED、`applyBatchSuspend` 只暂停 OPEN），
所以人工改成 `CLOSED` / `RENOVATING` 的门店**根本不会被碰**，不需要额外保护。

## 6. 反复冲突 → 永久豁免（不是加长锁）

若同一家店每隔几天就被人工改一次，说明问题**不在时间维度**——它要么不在舞讯的覆盖
范围内，要么被舞讯系统性漏报。此时继续加长锁是打补丁：**周期性返工**照旧。
正确处置是升级为 `daily_sync_exempt`，把「反复冲突」一次性转成**事实声明**。

- 触发信号：同一门店在不同日期被人工修正 ≥ 2 次（由运维/管理员判断）；
- 撤销：管理端一键撤销豁免，恢复参与舞讯推断。

## 7. 接口

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/admin/venue-sync/guard/query` | 读权威层级状态（编辑页展示 / 列表徽标），body `{venueIds:[…]}`（≤100） |
| POST | `/admin/venue-sync/guard/unlock` | 恢复自动同步：提前释放人工锁（幂等，返回实际解锁家数） |
| POST | `/admin/venue-sync/guard/exempt` | 设置/撤销永久豁免，body `{venueIds, exempt, note}`（幂等，返回实际变更家数） |
| POST | `/venues/{id}/update` | **人工通道**：状态变更自动打锁。请求体新增可选 `changeSource`；传 `"AGENT_BATCH"` = 程序化外部写库，改为受门禁约束（被拦时**资料照改、状态不动**） |
| POST | `/admin/venue-daily-openings/batch`、`/batch-suspend` | **外部通道**：返回体新增 `skippedLocked` / `skippedExempt` / `skipped[]`（含 venueId、门店名、原因、锁到期时刻） |
| GET | `/admin/venue-sync/venues/export` | 条目新增 `statusSource` / `statusLockedUntil` / `dailySyncExempt` / `syncNote`（只读展示用） |

**`POST /venues/{id}/update` 为什么要认 `changeSource`**：Agent/Skill 也会经此接口做
程序化写库（`CLOSED → OPEN` 恢复营业、批量回填营业时段）。若不给它声明通道的途径，
这个接口就成了绕过人工锁的后门。未声明一律按人工处理（默认落在更保守的一侧，
前端编辑表单不传该字段 → 天然走人工通道）。

## 8. 运营配置（`qwt_ops_config`，可热更新）

| 键 | 默认 | 语义 |
|---|---|---|
| `venue.status_lock.enabled` | `true` | 总开关（应急）：`false` = 回到无条件覆盖 |
| `venue.status_lock.human_open_days` | `3` | 人工置 OPEN 后的锁时长（天）；`0` = 该方向不打锁 |
| `venue.status_lock.human_closed_days` | `7` | 人工置非营业中后的锁时长（天）；`0` = 该方向不打锁 |

## 9. 明确不做（避免过度设计）

- **不回溯历史**（理由见 §3）。
- **首版不做「迟滞推翻」**：锁过期后要求「连续 ≥2 个不同 reportDate 的舞讯轮次结论一致」
  才允许覆盖人工值 —— 这一层能压掉单轮偶发漏报，但需要一张观测表累计「被锁期间舞讯怎么说」。
  先跑 2~4 周看真实冲突数据，需要再加，不提前上三张表。
- **不做 Skill 侧黑名单**（理由见 §2）。

## 10. 边界与副作用

- 门禁跳过**不写库、不发关注者通知、不产生数据更新公告**（本就无状态变更）。
- 幂等重跑安全：锁是状态而非一次性标记，重复提交同一批仍跳过。
- 两个方向互不干扰（`applyBatch` 只做恢复、`applyBatchSuspend` 只做暂停）。
- `VenueGuardAdminService` 的三个操作（query / unlock / exempt）都**不改 status 本身**，
  故不写状态变迁日志、不发通知，也不触碰列表/详情缓存（两个缓存都不含权威层级字段）。

## 11. 涉及文件

| 层 | 文件 |
|---|---|
| 迁移 | `db/migration-mysql/V25__venue_status_authority.sql` |
| 判定 | `venue/service/VenueStatusGuardService.java`、`venue/enums/VenueStatusSource.java`、`venue/dailyopening/enums/GuardSkipReason.java` |
| 外部通道 | `venue/dailyopening/service/DailyOpeningService.java`（两通道加门禁）、`BatchApplyResult` / `BatchSuspendResult` / `SkippedByGuardDetail` |
| 人工通道 | `venue/service/VenueService.java`（`updateVenue` / `markSuspendedByReport` / `reopenByReport` 打锁）、`venue/dto/request/CreateVenueRequest.java`（新增 `changeSource`） |
| 管理端 | `venuesync/controller/AdminVenueSyncGuardController.java`、`venuesync/service/VenueGuardAdminService.java` + 3 个 DTO |
| 展示/汇报 | `venuesync/dto/response/VenueExportItem.java`、`venuesync/service/VenueSyncDataService.java` |
| 配置 | `opsconfig/service/OpsConfigService.java`（键常量 + `getInt`） |

## 12. 沿革

- **2026-09-14 建立**（用户拍板：「别人的舞讯也不是 100% 靠谱」）：
  方案三选一取「人工锁 + 永久豁免」，不对称 3/7 天；
  **关门方向保留「≥2 源覆盖才自动写库」的多来源确认门**（未提及即关门是**语义**，
  不是跳过确认门的授权）；
  迟滞推翻与历史回溯明确不做。后端已落地，`./mvnw -q -s settings-central.xml clean test-compile` 通过。
- 待办：Web 管理后台（`quwuting-admin-web`）门店编辑页的状态来源徽标 / 锁到期日 /
  两个操作按钮尚未接入（接口已就绪）。
