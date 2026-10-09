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
4. **校验必须与存储一样窄（2026-10-01 毒丸修复）**：旧校验只覆盖「金额 > 0 / ts > 0 / id ≤ 32」，
   而真正的约束在 DDL（`amount decimal(10,2)`、`venue_name varchar(100)`、`source_ref_id varchar(32)`）。
   校验比存储宽 ⇒ 越界条目通过校验、落库时抛异常 ⇒ **整批事务回滚 500** ⇒ 客户端「本地为源」重放同一批 ⇒
   该设备之后的账目**永远上不了云**，本地却一切正常。现在：约束唯一声明处 `spend/SpendEntryLimits`
   （实体 `@Column` 与 `normalize()` 共用；`SpendEntryLimitsMirrorTest` 与 V16 DDL 逐项比对），越界一律
   **逐条**进 `rejectedIds`、绝不抛异常拖垮整批；金额先按列精度四舍五入到 2 位再校验（客户端表达式求和的
   浮点尾差是正常账目，不能因小数位多被拒），四舍五入后为 0 判非法。客户端协议常量
   `constants/spendWire.ts SPEND_AMOUNT_MAX` 由 `check:protocol` 与 `SpendEntryLimits.AMOUNT_MAX` 比对，
   记账键盘的整数位上限由它派生。

## 管理端口径：使用事实 vs 账面金额（2026-10-07 根因修复）

**症状**：用户删除一条记账记录后，admin「计时 · 账本使用」的记账用户数 / 场次 /
活跃 / 分类 / 门店排行**集体下跌**。

**数据其实没丢**：删除走软删（墓碑携带原始 ts/amount，`SpendService#upsert`
只置 `deleted=true`），行连同金额、分类、门店、时刻**完整保留**在
`qwt_spend_entries`；全库无任何硬删路径。丢的是**统计可见性**。

**根因不是"少写了一个条件"**，而是把两种不相容的语义塞进了同一列：

| 语义 | 回答的问题 | 是否含软删 | 常量 |
|---|---|---|---|
| **使用事实** | 有多少人**用过**、用��多少次 | ✅ 含 | `SpendStatsSql.FACT_ENTRY` |
| **账面金额** | 他**当前账面**实际花了多少 | ❌ 仅未删 | `SpendStatsSql.LEDGER_ENTRY` |

用户删除一条账目，撤回的是**数据**，撤不回**行为**（与既有约定同源：
`UserBehaviorEvent#VENUE_FAVORITE` 明写"事实口径 = 收藏动作发生过，取消收藏
不改写历史"）。但金额口径下"删除"恰恰是在表达"这笔不算"。旧实现让**计数也走
账面口径**，于是用户的正常纠错动作被误读成使用行为的否定——且**完全静默**
（SQL 正常执行、无异常、无告警，数字只是悄悄变小）。

**结构性缺陷**：判定"算不算"的谓词以**文本抄写**散落在 7 条查询里，无声明、
无命名、无门禁。这与 `UserStatsSql` 建立前「`USER_SCOPE` 被抄 5 份」**同构**
（同一个病，第二处发作）。故本次不只改条件，而是把口径下沉为编译期常量。

### 新增统计消费方的两条不变量

1. **计数走事实口径**（`FACT_ENTRY`）——用户撤回数据不会让"用过没有"消失；
2. **金额走账面口径**（`LEDGER_ENTRY`）——撤回的金额不进入消费总额。

两列回答不同问题，**同屏出现"笔数 ≥ 金额覆盖面"是设计意图而非缺陷**；
差异量由 `retractedEntries` 显式暴露在汇总里，避免明细条数与汇总对不上时
无人能解释。

### ⚠️ 两处易错（都是本次实际踩到的）

- **分组聚合必须 `LEFT JOIN`，不可 `CROSS JOIN`**：分类 / 门店 / 用户列表的
  账面侧派生表是**分组**的，若某分类/门店的条目**全部**被软删，该组在账面侧
  没有行——`CROSS JOIN` 会让这一组**整个消失**，即本次要修的现象换个维度复发。
  必须 `LEFT JOIN` + `COALESCE(金额, 0)`。汇总查询的两个派生表是**无 GROUP BY
  的标量聚合**，恒各返回一行，`CROSS JOIN` 才安全。
- **事实口径禁写成恒真条件**（`1 = 1`）：必须显式写 `deleted IN (0,1)`，
  否则无法区分"有意包含软删"与"忘了写过滤"，门禁也失去反查能力。

### ⚠️ 文本块拼接事故（2026-10-07，同日二次踩坑，**必读**）

改造上述查询时踩到：**接口 500、前端「计时 · 账本」查不出数据，而编译 + 既有 4 项
门禁 + tsc 全绿**。

**根因**：Java **文本块会剥掉结束定界符前的那个换行**（incidental whitespace）。
于是：

```java
// 源码看着完全正常
WHERE e.user_id = :userId AND""" + " " + LEDGER_ENTRY + """
ORDER BY e.ts DESC
```

拼接结果是 `... AND e.deleted = 0ORDER BY e.ts DESC` —— **token 粘连 → SQL 语法
错误 → 接口 500**。同理 `AND e.deleted IN (0, 1)) c`（粘连到 `)`）。

**为什么既有门禁没拦住**：所有门禁都断言「SQL 里**是否引用**了口径常量」，
而 `@Query.value()` 在**编译期**已完成字符串拼接——没有任何一处校验
**拼接之后**的文本长什么样。

**结构性修复**：把换行**放进常量本身**（`FACT_ENTRY = "\n" + ... + "\n"`），
让「拼接处必须补换行」从 N 个调用点的隐性责任，变成常量的一条**显式契约**。

**新增门禁判据**（`SpendStatsScopeMirrorTest`，7 项）：
- `scopeConstantsCarrySurroundingNewlines` —— 两常量必须自带首尾换行；
- `noTokenGlueInAssembledSql` —— 断言运行期真实值无 token 粘连。

> 已做变异自测：把常量改回不带换行 ⇒ 两项立刻红，其中
> `noTokenGlueInAssembledSql` 直接点出 `sumUserSummary 拼接后出现 token 粘连`，
> 即事故本体可被静态拦住。

**推广判据（凡「文本块 + 常量」拼 SQL 的地方都适用）**：
> 拼接点的前一行**不得以裸 `AND` 等 token 结尾**；若以 token 结尾，必须由常量自带
> 换行来兜。**验证深度要加一档**：编译绿不等于拼接结果对，须断言**拼接后的文本**
> （反射读 `@Query.value()` 即运行期真值），不要只读源码。

### 口径边界（勿越界）

本口径**只管 admin 的「使用盘子 / 账面」**。用户自己的 `/spend/overview`、
`/spend/entries` **不受约束**——那里是"我的账本"，用户撤回数据后理应不可见。
两类消费方语义本就不同，**禁把 admin 口径倒灌回用户侧**。

单用户明细 `listUserEntries` 亦维持账面口径（只回未删）：它回答的是
"账上现在有什么"，是**明细读取**而非"用过没有"的聚合；与双口径汇总并存时，
`entryCount > entries.size()` 应读作"记过又删了"。

## 一同计时的人快照（2026-10-09，V47）

> 需求（前端 quwuting 仓 44 号 §41 / 59 号 §十四）：经二维码同步的计时结算后，账本流水要能回答「这一笔是和谁一起跳的」。

**列**：`qwt_spend_entries.companions_json varchar(4096) NULL`（V47，纯增量；NULL = 没有同行者：手动账目 / 单人计时 / V47 之前的存量行）。
JSON 数组 `[{"nickname":..,"avatarUrl":..,"relation":"HOST|JOINER"}]`；`HOST` = 对方是出示二维码的人，`JOINER` = 对方是扫码加入的人
（枚举 `SpendCompanionRelation`，协议值 = 常量名，小程序 `check:protocol` 逐值比对）。

**为什么是快照列、不是联查分享会话**：账本是「本地为源、云端为镜」，联查意味着每行一次往返（弱网退化成空白）；token 是 bearer 凭据，
永久存进账目行 = 把凭据长期留在另一张表；展示昵称头像本来就该是"当时的样子"——与 `venue_name` 同构（对方事后改名不改写历史）。

**不存什么（合规收敛点）**：对方 userId（不给跨账目串联同一个人留通道）、年龄 / 性别 / 城市（从不经计时分享通道下发）、对方金额（账务隐私）。
读：与整张表一致，接口全部 user-scoped，仅账目所有者本人读得到；管理端用量统计（`AdminSpendStatsService`）**不投影本列**。

**总原则：同行者是元数据，永远不能拒掉一笔账**（`SpendEntryLimits` 毒丸教训的同族应用）。`SpendCompanions`（纯静态、可单测）：
- `normalize`：关系认不出的项整项丢弃、昵称去控制字符 / 首尾空白 / 超长按字符截断（不拆代理对）、头像只收 `https` 且 ≤ 512，否则置 null、人数封顶 6（= 单会话加入上限 5 + 主持方 1）；
- `serialize`：序列化后超过 4096（引号转义膨胀的极端载荷）时**从尾部丢人直到放得下**，任何输入都产出可落库结果，从不抛；
- `parse`（读侧）：坏 JSON / 形状不对 → 空列表 + WARN（不放大成 500，也不拖垮整页账目拉取），读出的每项再过一遍 `normalize`。

**写语义**：请求 `companions` **缺省（null）= 保留库里已有值**；非 null（含空数组）= 整体替换（空 → 列 NULL）。账目是 last-write-wins，
但"老版本客户端重传同一条账"不应抹掉新版本写下的同行者。下行 `GET /spend/entries` 的 `companions` 恒非 null（无则 `[]`）。

**三个护栏常量**在 `SpendEntryLimits`（`COMPANIONS_JSON_MAX_LENGTH` / `COMPANION_MAX_COUNT` / `COMPANION_NICKNAME_MAX_LENGTH` / `COMPANION_AVATAR_URL_MAX_LENGTH`）：
`SpendEntryLimitsMirrorTest` 把前者与 V47 DDL 逐项对齐；小程序 `check:protocol` 把后三者与客户端 `constants/spendWire.ts` 逐值比对。

**验证**：`SpendCompanionsTest` 8 项（null 与空数组可区分 / 关系宽容与丢弃 / 人数封顶 / 昵称清洗与不拆 emoji / 头像 https 与超长置空 / 往返 / 极端载荷仍可落库 / 读侧宽容）；
`SpendServiceTest` +4（落库为快照 JSON / 非法同行者不拒账 / 缺省保留与空数组置空 / 增量拉取恒非 null）。

## 门禁

`SpendStatsScopeMirrorTest`（零依赖，7 项，与 `UserStatsSqlMirrorTest` /
`UserBehaviorCatalogMirrorTest` 同族）：断言两常量显式表态 deleted、**常量自带
首尾换行**、**拼接后无 token 粘连**、计数查询引用事实口径、金额查询引用账面口径、
**扣除常量与 `USER_SCOPE` 后无内联 `e.deleted`**、分组查询禁 `CROSS JOIN`。

> 内联检测只查 `e.deleted` 前缀：`USER_SCOPE` 本身合法含 `u.deleted = false`，
> 按裸 `deleted` 检测会把合规引用误判成抄写——**门禁误报一次，团队就会整体
> 忽略它**，故必须精确到不可能误报。

`./mvnw -s settings-central.xml test -Dtest=SpendStatsScopeMirrorTest`

## 文件

```
spend/
  controller/SpendController.java    三个接口（@RequestMapping("/spend")）
  controller/AdminSpendStatsController.java  管理端三个只读统计接口
  service/SpendService.java         sync 逐条归一化+归因 / overview 聚合 / entries 游标
  service/AdminSpendStatsService.java管理端统计服务（双口径映射 + retractedEntries）
  repository/SpendStatsRepository.java  admin 统计查询（双口径，引用常量）
  repository/SpendStatsSql.java     ★ 口径单一事实源（事实口径 / 账面口径两常量）
  enums/WireEnums.java              协议字面量解析唯一入口
  enums/SpendSource.java            DANCE / MANUAL
  enums/SpendCategory.java          固定 6 类
  enums/SpendCompanionRelation.java HOST / JOINER（同行者关系，V47）
  SpendCompanions.java              ★ 同行者快照 规整 / 序列化 / 读侧解析（元数据永不拒账）
  SpendEntryLimits.java             存储约束唯一声明处（含同行者护栏）
  entity/SpendEntryEntity.java
  repository/SpendEntryRepository.java  原生 SQL 聚合（user_id 恒在 WHERE 首位）
  dto/*                             请求/响应 record
src/test/java/.../spend/service/SpendServiceTest.java        15 项
src/test/java/.../spend/repository/SpendStatsScopeMirrorTest.java  7 项（新增）
```

## 验证

`./mvnw -s settings-central.xml test -Dtest=SpendServiceTest`（Mockito，不依赖数据库）
——15 项断言覆盖：小写与混合大小写接受、未知枚举拒绝、非正金额/非法 ts/超长 id 拒绝、
部分拒绝逐条点名、空载荷与 null 请求、幂等更新、软删载荷携带原始 ts/amount。

## 待评审项（显式登记，避免沉默）

**账本使用行为尚未纳入 `UserBehaviorEvent` 事件目录**，故不进大盘活跃/留存
口径（用户 2026-10-07 拍板「暂不纳入，本次只修 admin 统计口径」）。
后果：用户行为**轨迹里看不到「记过账/用过计时器」**这一行为。
纳入会改变大盘历史 DAU / 留存数字（按 `UserBehaviorEvent` 判据第 3 条须立项
评审）。此项登记在此，使「未纳入」从沉默变成显式。
