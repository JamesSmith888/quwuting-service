# 53 · 门店热度统计口径（venue crowd stats，2026-10-07）

> 后端权威文档。业务与产品决策 = quwuting 仓 `docs/agents/27-venue-crowd-report.md`（含「改判登记」表）与
> `docs/agents/58-crowd-stats-and-likes.md`（前端呈现、点赞、根因复盘）；本文只写**服务端口径、接口与防复发**。
> 迁移 = `db/migration-mysql/V41__crowd_report_business_date.sql`（头注含完整根因）。
> 代码落点 = `venuecrowd/stat/`（`CrowdPolicy` 口径常量 / `BusinessDay` 营业日 / `CrowdConsensus` 统计判定 /
> `CrowdHeadline` 折叠头摘要 / `CrowdBaselineBuilder` 常态人气 / `CrowdTimeText`）+ `CrowdTrustService`（可信度）。
>
> ⚠️ **V41 上线后禁改一字节**（Flyway checksum 红线）。后续口径修订只改 Java / 本文档。
> ⚠️ **V41 不向后兼容应用**：`business_date` 是 NOT NULL 且**刻意无默认值**（营业日没有合理的默认，静默落成自然日
> 会把本次要封堵的根因原样放回去）。迁移后若回滚到旧版应用，旧版 upsert 不写该列 ⇒ **提交热度上报会失败（loud）**，
> 其余功能不受影响；回滚应用时须同时评估是否接受「上报暂不可用」至重新前滚。

## 1. 为什么重做（三条根因，勿重蹈）

| # | 症状 / 隐患 | 底层原因 | 长期方案（本文 §） |
|---|---|---|---|
| 1 | 诚实的人报相邻档（约80 / 约100）被判「说法不一」；3 人时一个 4.5 倍权重账号占 69% | 口径照搬门店**突发事件**（分类信号）的「众数占比」套路，而人数档位是 **8 档有序量表**；并且**从未按真实样本量校验**：生产任意 6h 窗口独立人数历史最大仅 2 | §3 一人一票 + 加权下中位数 + ±1 档一致性 + 权重 n≥5 才启用且封顶；§7 「口径准入三问」 |
| 2 | 同一个人同一夜可投两票（23:50 一票、00:10 又一票），两行同进 6h 窗口被重复计票，确认积分按两个行 id 各发一次 | 唯一键用**自然日**，而营业时段跨午夜（约 1/3 上报在 23:00~01:00）——「一夜」是业务概念，自然日是日历概念 | §2 `business_date`（05:00 分界）成为唯一键；`report_date` 保持自然日 |
| 3 | 「确认态」有两份实现（`resolveTier` 与 `WindowSnapshot.confirmed()`），只靠注释「必须一致」维系；「认领人不享受加成」只有注释、从未实现 | **决策依据存在于 N 处，一致性由人记得**；文档承诺没有代码与测试对应 = 从未存在 | §3 唯一判定函数；§5 认领人排除；§7 `CrowdDomainSingleSourceTest` 机器门禁 |

## 2. 营业日与 V41

- `BusinessDay.of(t) = (t − 5h).toLocalDate()`；分界 `CrowdPolicy.BUSINESS_DAY_START_HOUR = 5`。
  依据（生产只读取证 2026-10-07）：有效上报按小时 00-02 点 4 条、12-23 点 33 条、**03:00~11:00 零条** ⇒ 分界在死区。
- **两个日期各管各的坐标系**：`business_date`（营业日）= 唯一键 `(venue_id, user_id, business_date) WHERE deleted=0`；
  `report_date`（自然日）= 行为统计口径的日列（`UserStatsSql` / `UserBehaviorSql` / `UserBehaviorEvent`），**不改语义**——
  改成营业日会让 00:00~05:00 的上报在活跃天数 / 留存口径里整体前移一天，与其它事件表错位。
- 迁移：加可空列 → 回填 `DATE(created_at − 5h)`（created_at 为空依次回退 updated_at / report_date 当日 12:00）→ 同人同店同营业日多行时**保留 created_at 最新的一行、其余软删** → 删旧生成列与旧唯一索引 → `business_date` 收紧 NOT NULL → 建新生成列 + 唯一索引。
- **验证记录**：本地一次性 MySQL 8.0.41 上用含冲突样本跑真实迁移文件——跨午夜双行保留 id2 软删 id1；created_at 为空的回退正确；
  迁移后 UPSERT 四种语义全部符合（跨午夜同夜命中 ON DUPLICATE KEY 更新原行且 `report_date` 保持首次自然日 / 04:59 与 05:00 分属两个营业日 / 软删后同夜可重报 / 不同人互不冲突）。
  生产只读预演：存量 37 行**冲突 0 组**。⚠️ 未在生产执行（本地 mysql profile 连生产，Flyway 随应用启动，部署时才会跑）。

## 3. 统计判定（`CrowdConsensus`，纯函数）

1. **一人一票**（`evaluate` 的前置条件，重复投票人直接抛 `IllegalArgumentException`——静默去重会藏住「忘了折票」的 bug）：
   `latestPerVoter`（今晚窗口：同一人取最新一张）/ `typicalPerVoter`（常态：折成其全部上报的下中位档）。
2. **统计量 = （加权）下中位数 + 四分位**：有序量表、顶档「约300+」无上限、档间不等距 ⇒ 不求均值；中位数本身就是「排除最高最低」且结果永远是真实档位。
   累计权重首次 ≥ p×总权重的那一档（等权时 = nearest-rank，偶数样本取较低档）。
3. **权重**：独立人数 < 5 等权；≥ 5 才启用可信度权重，并截断到 `[1.0, 2.0]` ⇒ 单人权重占比 ≤ 1/3（`CrowdPolicyInvariantTest` 证明）。
   已知边界：两个资深账号合谋在 n=5 时可压过 3 个普通账号——防线在权重门槛（5 次被采纳 + 10 天打卡）与管理端「说法不一 / 高频修改」可见性，不在公式里（`twoVeteranColluders…documentedBoundary` 把它写进测试）。
4. **一致性**：落在中位数 ±1 档内的权重占比；`CONFIRMED` = ≥3 人且占比 ≥ 0.6；`CONFLICT` = ≥2 人且占比 < 0.6。
5. **唯一判定出口**：`CrowdVerdict`。详情页置信度分层、**确认积分受奖人**（`agreeingVotes` 自带代表行 id = 幂等键）、折叠头摘要、常态人气、管理端「说法不一」标记全部读它，没有第二份实现。
   确认积分口径随之从「档位 == 众数」变为「落在中位数 ±1 档内」（同一个 ≥3 人 + 占比门槛，故积分只会在更宽松地认定「一致」时发放，发放对象仍是独立账号）。

## 4. 接口

| 接口 | 说明 |
|---|---|
| `GET /venues/{id}/crowd-reports` | 摘要新增 `headlineText`；`female/male.level` 改为中位档，`count` = 该维度独立投票人数，`share` = ±1 档一致占比；明细行新增 `likeExpiresInSec`（剩余可赞秒数，≤0 = 已过窗口）；认领人行 `badgeText = 店家` |
| `GET /venues/{id}/crowd-reports/baseline` | 常态人气：前 7 / 30 个**营业日**（不含今晚所在营业日）；每窗口按 `SampleTier` 分档出文案（NONE 邀请 / SPARSE 原值 / LIMITED 中位+最低~最高 / SOLID 中位+四分位）；`deviationText` 仅在今晚 ≥2 人、常态 ≥3 人、偏差 ≥2 档（或「差不多」）时下发；`noteText` 口径小字 |
| `GET /venues/{id}/crowd-reports/{reportId}/likers` | 谁觉得有用：上报者本人 / ADMIN ⇒ `FULL`（完整名单，最近点赞在前）；其他人（含未登录）⇒ `SUMMARY`（人数 + 分层汇总，名单恒空）。不受 6h 窗口限制；行不存在 / 已删 / 串店 ⇒ 1019 |
| `POST …/like` / `…/unlike` | 行为不变；**自赞放开**（2026-10-07 用户再次确认），赞数永不进算法；被赞通知改为未读合并 |

## 5. 认领人（门店主）

商家自报有营销动机。其上报**照常落库、照常在明细里展示（标「店家」）**，但不进中位数 / 确认积分 / 列表角标 / 最新上报行：
统计侧在取票处剔除（`votesOf`），列表侧在 SQL 层 `JOIN qwt_venues … claimed_by`（两条原生 SQL 已在生产库只读执行验证；「最新上报」的相关子查询内外两层都带条件）。
生产现状：`claimed_by` 非空的门店 0 家——本项是**先立不变量**，当前零影响。

## 6. 被赞通知合并

`MessageService.createOrMergeUnread`：同收件人、同类型、同业务关联、**仍未读**、创建于 `mergeSince` 之后 ⇒ 就地更新正文（创建时间与未读态不变 ⇒ 徽标数不涨），否则新建。
被赞通知的 `mergeSince = 这条上报的创建时间`（此前的未读被赞通知属于更早的夜）；正文携带累计赞数。已读后再被赞 ⇒ 新建。并发两次可能各建一条（无锁）——接受。

## 7. 防复发（机器门禁）与「口径准入三问」

| 门禁 | 守什么 |
|---|---|
| `CrowdConsensusTest`（表驱动 15 场景 ×2 + 对抗） | 单人高权重 / 两人合谋（已知边界）/ 跨午夜双投 / 偶数样本 / 全同值 / 双峰 / 输入顺序无关 / 重复投票人抛错 |
| `CrowdPolicyInvariantTest` | 口径常量之间的耦合（单人 ≤1/3、确认至少 LIMITED 样本、比较阈值 > 一致性容差……） |
| `BusinessDayTest` / `CrowdHeadlineTest` / `CrowdBaselineBuilderTest` | 05:00 边界、相对日期措辞、窗口包含关系、不含今晚、文案逐字 |
| `CrowdReportServiceTest` / `CrowdReportLikeServiceTest` / `MessageServiceMergeTest` | 营业日与自然日分坐标写入、认领人不进统计不拿奖、确认积分与 CONFIRMED 同源、管理端冲突同判定、分层披露、自赞不通知、合并 |
| `CrowdDomainSingleSourceTest` | 数值口径常量只许在 `CrowdPolicy`（例外 fail-closed 登记）；包内禁 `LocalDate.now()` 与窗口字面量；一致性阈值只在 `CrowdPolicy`/`CrowdConsensus` 代码中出现；旧的第二份判定实现的标识符不得回流；唯一键 / 「我的上报」走营业日；列表 SQL 排除认领人（**已做变异验证**：注入 3 种违规均变红） |
| `CrowdReportQuerySqlTest`（`-Drun.db.tests=true`，可选） | 新原生 SQL / 派生查询在真库可执行。⛔ 加载上下文会跑 Flyway——**V41 上线前运行 = 把 V41 直接跑到生产**，只在部署完成后作上线验收 |

**口径准入三问**（改任何聚合 / 阈值 / 打分规则之前必须回答，写进 PR 描述或本文件）：
1. **数据类型**是什么——分类、有序、连续？规则是否与类型匹配（有序量表不用「精确同档」，无上限的量不求均值）？
2. **真实样本量分布**是多少——先用只读 SQL 看 n 的分布（本次：窗口内独立人数最大 2），阈值按它设计，宁可诚实地说「样本少」；
3. **谁能注水**——单人 / 两人 / 新号各能把结果推多远？上界是否有不变量测试守住？

## 8. 非目标 / 未验证

- 常态人气只做女舞伴主信号；男客维度首版不进基线。不分午 / 晚场（约三成上报在 12-18 点，文案写明「含午场」，样本够了再按 `business_hours` 分）。
- 内部 / 测试账号**参与统计**（2026-10-07 用户拍板：也是真实数据）；小样本的保护交给分档诚实展示，而不是把人踢出统计。
- **未验证**：未在真机 / 开发者工具看布局与交互；V41 未在生产执行；`CrowdReportQuerySqlTest` 未运行（见上）；
  后端全量测试套件见交付说明中的实际运行结果。
- 23:00~01:00 为上报高峰只是 37 条数据的观察（推断，样本小）。

---

> ⚠️ **维护警告**：本文件是 agents 文档，任何 Agent 修改前必须遵守 AGENTS.md「⚠️ 维护规则」（渐进式披露）。
> 口径常量只改 `CrowdPolicy`，并同步跑 `CrowdPolicyInvariantTest` 与 `CrowdDomainSingleSourceTest`。
