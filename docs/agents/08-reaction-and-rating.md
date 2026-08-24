# 评分交互与 Reaction 快速反馈

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 评分交互（taginteraction 模块）

### 设计定位

评分（rating）：1-10 量化评价，"这个维度体验如何"。适用于系统定义的标准评分维度（`RatingDimensions.ALL`），与管理员标签相互独立，用于计算综合评分（门店整体质量比较）。

> **历史沿革**：本模块早期还承载"标签点赞"（对 `Venue.tags` 的二值认同信号）与"现场状况"三个众包评分维度（舞伴氛围/客流热度/舞伴年龄层）。2026-08 上线 Reaction 快速反馈系统后，这两类功能均被替代——见「Reaction 快速反馈系统」章节的根因说明。`qwt_tag_interactions.liked` 列作为历史遗留字段保留在库中不再读写（与 `qwt_users.avatar_url` 同处理原则），`TagInteraction.liked` 字段已从实体中移除。

### 数据模型

`qwt_tag_interactions` 表：一个用户对一个舞厅的一个评分维度至多一行（`UNIQUE(userId, venueId, tag)`）。`tag` 字段存储评分维度名称（服务/环境/音响效果/性价比）。

索引：`(venueId, tag)` 覆盖聚合查询，`(venueId, tag, updatedAt)` 覆盖时间窗口查询。

### 评分维度（RatingDimensions）

系统统一定义的标准维度列表，所有舞厅共享，不依赖管理员是否添加了对应标签。新增维度只需修改 `RatingDimensions.ALL` 常量，前端通过 `tags/stats` 接口的 `dimensions` 字段自动同步。

| 维度 | 锚定文案 | 说明 |
|------|----------|------|
| 服务、环境、音响效果、性价比 | 1 最差 / 10 最好 | 主观质量打分，全量均分参与综合评分计算 |

原"现场状况"三维度（舞伴氛围/客流热度/舞伴年龄层）已被 Reaction 快速反馈系统的对应表情替代（👧年轻舞伴多 / 👴舞伴年龄偏成熟 / 🔥人气旺等）——两者语义重叠，是"标签、评分、热度模块信息混杂"问题的直接根因，详见下一章节。

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/venues/{venueId}/tags/stats` | 公开（软鉴权） | 维度评分（含时间窗口）+ 当前用户评分状态 |
| POST | `/venues/{venueId}/tags/score` | 需登录 | body: `{tag, score}`，upsert 语义（首次=打分，再次=修改覆盖） |

### 防刷机制

评分接口设 60 秒冷却期（`SCORE_COOLDOWN_SECONDS`）：同一用户同一维度在冷却期内重复提交返回 1006 错误。基于 `updatedAt` 判定（改分后自动刷新时间戳）。

### 时效性

评分统计支持三个时间窗口：全部 / 近 30 天 / 近 7 天。基于 `updatedAt`（用户最近一次修改评分的时间）过滤，改分后新分数归入近期窗口。聚合实时计算（当前数据规模无需物化视图）。

### 约束

- 评分仅允许 `RatingDimensions.ALL` 中的维度，历史遗留维度（如旧的"舞伴氛围"）不再被 `isValid` 承认，相关历史行不参与任何聚合计算

---


---
## Reaction 快速反馈系统（venuereaction 模块）

### 设计动机与根因（2026-08 确立）

舞厅场景的用户核心诉求不是"查看传统评论"，而是在不能/不便评论的情况下快速了解：这家店现在值不值得去、舞伴年龄和质量如何、氛围人气如何、服务环境等真实体验。早期实现将这类反馈拆散在三处——描述性标签的"点赞"（`TagInteraction.liked`）、"现场状况"众包评分维度（舞伴氛围/客流热度/舞伴年龄层，1-10 打分）、以及场所热度——三者语义高度重叠但交互形态不一致（点赞 vs 1-10 选择器 vs 被动统计），造成"标签、评分、热度模块信息混杂"，用户需要在多套交互里重复表达同一件事，认知负担重。

**解法**：统一为类似 Telegram Reaction 的表情化标签（emoji + 文字说明），一次点击表达一种态度，替代原"标签点赞"与"现场状况"评分维度：

- 默认只展示 emoji，长按显示文字说明，点击直接 +1（当日再次点击取消）
- 不做"点赞/倒赞"二元对立（不提供 👍👎 成对选项）——采用具体、中性的正负向 Reaction 共存（如 👴 舞伴年龄偏成熟，而非"倒赞：舞伴年龄大"），避免攻击性评价引发商家纠纷；单个语义具体的正向表达（如 👍 值得推荐、🍺 消费合理）允许
- 一个用户对一个场所的一个 Reaction **每天最多一次**（不允许 🔥🔥🔥🔥 刷数据），但允许同时选择多个不同 Reaction

### 每日一记模型（2026-08 核心设计决策，替代旧"toggle 软删 hold 模型"）

**数据模型**：`qwt_venue_reactions` 表 `(id, userId, venueId, reactionCode, reactionDate, createdAt, updatedAt, deleted)`，唯一约束 **`UNIQUE(userId, venueId, reactionCode, reactionDate)`**——同一用户每天只能贡献一次同类型 Reaction。每次点击 = 插入一条 `reactionDate = 今天` 的记录；取消 = **物理删除**当日记录（"取消当天 Reaction"语义，不做软删——按日唯一槽位随日期自然消亡，无软删恢复需求，与 Favorite/StatusReport 的软删模式形成有意差异，原因见下）。

**根因（旧模型的三处缺陷 → 每日一记修复）**：

1. **需求语义违约**：需求要求"每天最多点击一次、当天不可重复增加数量、次日自动恢复可点击状态（可再次 +1）"——Reaction 是"用户近期体验评价"，应允许用户次日重新评价。旧 hold 模型（toggle 软删 + 恢复刷新 createdAt）下用户次日点击是"取消"而非"再次表达"，无法自然完成每日重新评价。
2. **窗口计数本地不可推导 → 双计数 hack**：旧模型下取消可能作用于 createdAt 超窗的旧记录（如持有 40 天后取消），count7d/count30d 的本地 ±1 无法精确推导，迫使前端发明"展示 countAll、排序 count30d"的双计数分离 + 前端只展示 countAll 的妥协。而需求要求列表默认展示近7天、可切换近30天/全部——展示数字必须是所选窗口计数。每日一记模型下取消**只可能作用于当日记录**（今日新增必落在全部窗口内、取消删掉的也必是当日记录），countAll/countToday/count7d/count30d 四个窗口的本地 ±1 **全部精确**，双计数 hack 整体消失，列表窗口切换直接展示窗口计数。
3. **未来扩展被堵死**：周末热门/实时热门/到店权重等扩展都需要"按日记录"（reaction_date 维度）做聚合，旧模型单行无日期维度，历史数据不可回填。每日一记从第一天起就按日天然聚合。

**时间衰减仍然有效**：不做周期性清零——原始记录永久保留，窗口统计实时计算（countToday/count7d/count30d 按 created_at 滚动窗口，锚点"此刻"：今天0点 / now-7d / now-30d，不同于热度模块"截至昨日"的排他上界约定），记录随日期推移自然滑出近期窗口；"全部"窗口承载历史画像层。两层概念（实时层 vs 画像层）的约定不变。

**为何取消用物理删除而非软删**：软删 + 按日唯一约束下，同日"取消后再点"需要恢复旧行（槽位被软删行占用），与"取消即当日贡献移除"语义叠加复杂度；物理删除使按日槽位随日期自然释放，逻辑最简，且本表无历史追溯需求（个人参与历史不对外展示）。与 Favorite/StatusReport 软删模式的区别：后者的唯一槽位是"永久性"的（user-venue 关系），必须靠软删占用防重复；Reaction 的槽位按日自然过期。

### 每日一票（换票语义，2026-08-14 用户驱动 + 根因分析，可配置）

**需求**：列表页 Reaction 表情"每天只能选择唯一一个他认为最好的表情"。根因：原模型唯一约束按 code 维度（`(userId, venueId, code, date)`），同一用户同一天可对同一场所贡献多个不同 code——用户可零成本"全选"（chip 快捷参与 + Picker 多选均无约束），各场所计数同步膨胀、分布趋同，列表 chip 看不出差别（选择退化为确认，信号稀释）。修复 = 把**稀缺性**还给写入模型（一人一店一日一票）。

**设计决策（应用层语义 + 可配置，非 DB 约束）**：

- **一票约束在 `VenueReactionService.toggle` 事务内实现**（不建 DB 唯一约束）：
  - 当日无票 → 插入（replacedFrom=null）；当日票=目标 code → 取消（replacedFrom=null）；当日票≠目标 code → **原子换票**（同事务删旧票 + 插新票，replacedFrom=旧 code）
  - **并发串行化**：`pg_advisory_xact_lock(hashtext('reaction:' + userId + ':' + venueId + ':' + date))` 事务级咨询锁——无 DB 约束兜底时，"查当日票→删→插"不串行化会让两个并发请求同日插入不同 code 破坏一票不变量。锁在事务提交/回滚自动释放
- **为什么不做 DB 唯一约束**：一票规则要能被运营即时开关（feature flag），数据层不硬编码产品规则——开关关闭时退化为原多选语义（`toggleLegacy`，按 code 维度唯一约束兜底）
- **开关**：`qwt_ops_config` 表 `reaction.daily.single`（默认 `'true'`），管理端 FAB「运营配置」页修改即时生效；服务端读走 `OpsConfigService.isEnabled`（Caffeine 短 TTL 缓存 + 写路径失效）
- **契约扩展**：toggle 返回 `ToggleReactionResult{reacted, replacedFrom}`（原 boolean）——replacedFrom 仅换票路径非空，前端据此把旧 code 本地参与态同步为 false（乐观换票的幂等 reconcile，见[前端文档](../../quwuting/docs/agents/08-reaction-system.md) · 「每日一票」）
- **数据卫生（V22 迁移）**：清理历史"同日同用户同场所多行"（保留每 (user_id, venue_id, reaction_date) 的 `max(id)` = 最后一次点击意图）——开启一票制后当日查询至多一行，四窗口计数本地 ±1 恢复精确（换票 = 旧 code -1 + 新 code +1，各窗口自洽）；`findFirstBy...OrderByIdAsc` 防御残留多行不抛 `NonUniqueResultException`
- **四窗口计数精确性保持**：换票在同事务内完成（删旧 +1 插新），对任何窗口净贡献 0 且两侧各自精确 ±1——乐观更新与跨页同步的既有机制零改动继续成立

### Reaction 字典（ReactionCode，后台维护）

Reaction **不允许用户自由创建**——避免色情/攻击/广告/竞对刷评价。字典是后台维护的 Java 枚举（`ReactionCode`），emoji + label 由后端唯一定义并通过接口下发，前端 Picker 的静态字典（`constants/reactions.ts`）是镜像副本需两端同步。

**2026-08-24 常见表情全放开（双层字典架构）**：`ReactionCode` = **legacy 12 项业务语义 code（历史保留）+ 常见表情目录适配**——常见表情（~150 项三极性）单一事实源 = `EmojiCatalog.java`（独立包 `emoji`），本枚举的静态适配器 `allCodes/isValid/emojiOf/labelOf/polarityOf` 在 legacy 之上叠加目录（**禁止把目录项复制成枚举 value**）；域内去重：目录项 emoji 去除 VS16 后与 legacy 撞车即剔除。热度公式极性列表（`positiveCodeNames/negativeCodeNames`）经适配器自动含目录项，NEUTRAL 仅展示不入公式。详见文件末尾「2026-08-24」节。

| 代码 | Emoji | 说明 |
|------|-------|------|
| HOT | 🔥 | 人气旺 |
| RECOMMEND | 👍 | 值得推荐 |
| PRICE_HIKE | ✌ | 舞伴加价（负面；原 VALUE「性价比高」纠偏，见下「2026-08-12 字典瘦身」） |
| VIBRANT_PARTNER | ⭐ | 舞伴有活力 |
| SWEET_PARTNER | 🌸 | 舞伴甜美 |
| MATURE_PARTNER | 💋 | 舞伴成熟 |
| GOOD_SERVICE | 💁 | 服务贴心 |
| QUIET | 🪑 | 人气冷清 |
| BAD_ENV | 😕 | 环境一般 |
| SERVICE_ISSUE | 😡 | 服务问题 |

（2026-08-12 字典瘦身 18 → 10：第一轮删 GOOD_VIBE / GOOD_MUSIC / NORMAL / CROWDED + VALUE → PRICE_HIKE 改负面；第二轮删 FAIR_PRICE / WAITING / HIGH_COST / CLEAN，见下。）

**2026-08-08 视觉升级扩版**（用户驱动 + 根因分析先行）：

1. **根因**：
   - 旧 16 项 OpenMoji 表情在列表卡片 chip 与 Picker 弹窗中**视觉同质化严重**——单色矢量图标缺乏品牌辨识度
   - "年轻舞伴多（👧 15岁）/ 舞伴年龄偏成熟（👴 35岁）"两个维度直接用**具体年龄数字 + 舞伴服务语境**——即使配图是 Q 版虚拟卡通，"具体未成年年龄 + 服务对象"组合的视觉暗示触碰《未成年人保护法》风险
2. **变更**：字典 16 → 18 项
   - **删除**：`YOUNG_PARTNER` / `OLD_PARTNER`（具体年龄 + 舞伴服务）
   - **新增 4 个**（"风格 + 年龄组合"取代"年龄标签"）：
     - `VIBRANT_PARTNER` ⭐ "舞伴有活力" — 替代 YOUNG_PARTNER 的"年轻/活力"语义，去掉具体年龄
     - `SWEET_PARTNER` 🌸 "舞伴甜美" — 原"年轻少女"风格化
     - `MATURE_PARTNER` 💋 "舞伴成熟" — 替代 OLD_PARTNER 的"成熟"语义
     - `VALUE` ✌ "性价比高" — 新增维度，覆盖 20元/曲级别小钱场景；与 `FAIR_PRICE`（消费合理，中端语义）**不重叠**
3. **极性**：VALUE / VIBRANT / SWEET / MATURE 均为 `Polarity.POSITIVE`（计入热度公式，与原 YOUNG/OLD_PARTNER 同族）——VALUE 偏正面（"性价比高"是好的），VIBRANT/SWEET/MATURE 是风格描述（非负向信号）
4. **前端联动**：见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「Reaction 快速反馈系统 → 静态字典」章节的"2026-08-08 视觉升级"纪要（emoji 字符契约保持、emoji 兜底语义、4 个新图命名 / 5 个切图覆盖清单、Picker 4×4 变 4×5 末行 2 个居中）
5. **迁移**：`src/main/resources/db/migration/V3__reaction_code_visual_upgrade.sql`
   - `reaction_code = 'YOUNG_PARTNER'` → `'SWEET_PARTNER'`（"年轻"维度映射到"甜美风"——年轻用户偏好甜美风格，映射误差小）
   - `reaction_code = 'OLD_PARTNER'` → `'MATURE_PARTNER'`（语义直接对应）
   - VIBRANT_PARTNER / VALUE 历史上不存在，无需迁移
   - `DO $$ ... RAISE WARNING` 验证剩余 0 行（防御性，应用启动后可 grep 警告日志）
   - **V3 已进 Flyway 链（target/classes 确认编译）**——正常重启自动跑；**但重置开发库后重新执行 `seed-dev.sql` 会再次引入旧 code**（seed 曾含 YOUNG_PARTNER 数据，已修正为 SWEET_PARTNER）——残留时手动执行下方 UPDATE 兜底

**2026-08-12 字典瘦身（用户驱动，18 → 14）**：

1. **根因**：
   - "人气/氛围/音乐"三维度重叠——GOOD_VIBE（💃 氛围好）与 HOT（🔥 人气旺）讲的都是"现场热闹"，GOOD_MUSIC（🎵 音乐棒）用户无感（来舞厅的动机是舞伴不是音乐）
   - NORMAL（😐 普通）是零信息默认态；CROWDED（👥 人多拥挤）是 HOT 的**负面镜像**——同一事实（人多）正反各一个表情，正负信号互搏
2. **变更**：删除 `GOOD_VIBE` / `GOOD_MUSIC` / `NORMAL` / `CROWDED`；`VALUE` → **`PRICE_HIKE`** 语义纠偏
   - **VALUE 纠偏（✌ 黑话「剪刀手」）**：✌ 实为圈内黑话（当时误记为"10 元场有舞伴临时加价至 20 元时比 V 手势"——**2026-08-13 用户澄清为误读**：剪刀手是**价格差异**（同场舞伴默契收 10 元、个别收 20 元），非中途变卦），是**负面标签**而非"性价比高"。处理三原则（用户定调）：**① 不算正面表情** → 极性 POSITIVE→**NEGATIVE**（退出热度公式、进负面信号单独计数）；**② 不明示"剪刀手"** → label 落中性行为描述、emoji 保留 ✌ 作圈内暗号（不落文字即不得罪人）；**③ 不误伤正常场** → description 数字仅限"同场对比"语境（正常 20 元场无"大多收 10 元"语境不躺枪）
   - **迁移**：`V15__reaction_dictionary_trim.sql`——`VALUE` → `PRICE_HIKE` 重映射（V3 同款 `DO $$ RAISE WARNING` 验证）；已删 4 code 历史数据**保留不删**（无最接近承接 code，强行映射会扭曲信号），前端展示层统一过滤字典外 code（后端聚合查询返回旧 code 时沿用「枚举外 code 防御」规则优雅跳过）
   - **热度公式**：删 4 均不在 POSITIVE（GOOD_VIBE/GOOD_MUSIC 删除后经 positiveCodeNames() 自动退出），唯一公式变化 = VALUE 退出正向（历史 VALUE 记录的正向加权消失，属语义修正）；PRICE_HIKE 进 negativeCodeNames() 单独计数

**2026-08-12 晚 第二轮瘦身（用户驱动，14 → 10）**：

1. **根因**：价格（FAIR_PRICE/HIGH_COST）/ 排队（WAITING）/ 清洁（CLEAN）维度用户实际使用率低——字典收敛到"人气/舞伴风格/服务/环境"核心信号（来舞厅的动机是舞伴，辅助维度从简）
2. **变更**：删除 `FAIR_PRICE` / `WAITING` / `HIGH_COST` / `CLEAN`
3. **与第一轮不同的历史数据策略（用户明确要求）**：**物理清理**——`V16__reaction_prune_codes.sql` `DELETE FROM qwt_venue_reactions WHERE reaction_code IN (...)`，**只删表情数据**（qwt_venue_reactions 无外键引用、reaction_code 无 FK/CHECK，无级联风险；其他表一律不动）。理由：本轮起不再保留孤儿 code，前端无需长期携带"字典外 code 过滤"兼容逻辑（第一轮 4 code 历史数据仍保留 + 前端过滤，两轮策略差异见 V16 注释）
4. **前端联动**：见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「Reaction 快速反馈系统 → 静态字典」章节的"2026-08-12 晚 第二轮瘦身"纪要（Picker 4×3 变 4×2 末行 2 个居中 nth-child(n+9)）

**2026-08-13 字典优化（用户驱动，10 → 12；产品原则「平台裁决事实，不裁决人品；负面聚合可见，个体免于点名；被指涉方有申辩权」——即"陈述事实、不攻击别人"）**：

1. **新增 2 code**：`PARTNER_ABUNDANT`（💃 舞伴充足，POSITIVE——与 HOT 区分：可跳舞伴多 ≠ 场面热闹）；`MISMATCH`（😬 现场不符，NEGATIVE——"到店后发现和照片/介绍不一样"的避坑信号，信息准确性类负面）
2. **label 去判断化**：PRICE_HIKE「舞伴加价」→「收费偏高」（**2026-08-13 语义纠正**：✌ 剪刀手 ≠ 中途临时加价，是**价格差异**——同场舞伴默契收 10 元、个别收 20 元；"收费偏高"为静态价格事实、无变卦暗示，详见 ReactionCode.java PRICE_HIKE javadoc）；QUIET「人气冷清」→「人气偏少」；SERVICE_ISSUE「服务问题」→「服务欠佳」
3. **无 migration**：新增 code 无历史数据（reaction_code varchar(30) 无 FK/CHECK 枚举约束）；label/emoji 是枚举内文案不落库（由 stats 接口下发）——与 V15/V16 不同，本轮无 code 改名/删除
4. **热度公式自动适配**：PARTNER_ABUNDANT 经 `positiveCodeNames()` 自动计入、MISMATCH 经 `negativeCodeNames()` 自动单独计数（极性唯一事实源机制免手工维护）
5. **前端联动**：见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「Reaction 快速反馈系统 → 静态字典」的"2026-08-13 字典优化"纪要（描述重写原则、详情页「近期风险」区块 = 纯前端负面聚合，后端零改动）

6. **枚举外 code 防御（2026-08-08 线上 500 事故教训）**：`GET /venues/{id}` 因 `VenueReactionService.buildTopBadgesFromCounts` 裸 `ReactionCode.valueOf(e.getKey())` 抛 `IllegalArgumentException`（库中残留 `YOUNG_PARTNER`，枚举已删除）→ 详情页 500。**长期规则**：**从聚合/查询结果按 code 转枚举时，必须先过滤或安全转换（`ReactionCode.isValid(code)` filter 或 `valueOfSafe`），禁止裸 `Enum.valueOf`**——枚举删除/改名后，库中旧 code 仍可能被聚合查询返回，必须优雅跳过（与 `getStats` 用 `ReactionCode.allCodes()` 遍历 + filter、`VenueHeatService` 用极性列表流的行为对齐）；枚举内 code 是唯一事实源，库中残留 code 是脏数据，跳过而非让接口崩溃

审核安全说明：字典坚持"具体、中性描述"原则——`BAD_ENV` 未采用需求初稿中的 🤮（呕吐表情），因强烈厌恶语义与"不引入攻击性反馈"的设计原则相悖；`RECOMMEND` 的 👍 是**单一正向表达**而非 👍👎 二元组合，允许使用。新增/调整字典条目时需过审核安全过滤（参照 venuestatusreport 模块 `ReportReason` 命名规避敏感词的先例）。

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/venues/{venueId}/reactions/stats` | 公开（软鉴权） | 字典内全部 Reaction 的四窗口统计 + 当前用户"今日已参与"状态，详情页"大家对这里的感受"+"查看更多"用 |
| POST | `/venues/{venueId}/reactions/{code}` | 需登录 | toggle 语义（2026-08-14 每日一票：今日未参与=参与、今日已参与=取消、已选其他=**换票**），返回 `{reacted, replacedFrom}`；`code` 为路径变量而非请求体（字典固定，路径更简洁） |
| GET | `/venues?...&window=7d/30d/all` | 公开（软鉴权） | 列表接口新增 `window` 参数：控制卡片 Top Reaction 徽标的排序/筛选窗口，默认 `7d`（近7天） |

**toggle 并发**：每日一票模式下同日并发由 `pg_advisory_xact_lock` 事务级咨询锁串行化（见上「每日一票」章节）；多选模式（开关关闭）下同日并发重复插入触发唯一约束冲突 → 幂等视为已参与（`DataIntegrityViolationException` 捕获 + `entityManager.clear()`）。前端每 code 一个 in-flight 守卫（见前端 AGENTS.md）已把同端连点串行化，本防御兜底多端竞态。

### 列表页 Top Reaction 徽标（VenueResponse.topReactions，替代原 tagLikeCounts）

`GET /venues`（按 `window` 参数）、`GET /favorites`（固定默认窗口 7d）等复用 `VenueResponseMapper` 的接口在 `VenueResponse.tags` 之外携带 `topReactions: List<ReactionBadge>`（**完整展示**：所选窗口内所有用户点击过的全部表情，count>0 的 code 一个不落、按所选窗口计数降序，**不做任何截断**）。创建新 Reaction 的入口是前端 Picker 表情选择器（长按卡片触发），不是 count=0 的占位 chips——此决策的根因分析见[前端 AGENTS.md](../../quwuting/AGENTS.md) · 「Reaction 快速反馈系统 → 设计决策 → 展示与创建职责分离」。

**完整展示契约（2026-08-09 需求定稿）**：topReactions 的集合构成 = **所选窗口（默认近7天）内所有用户点击过的全部表情**，不做任何截断。列表页/收藏列表/详情基础响应的默认窗口均为近7天（列表页可经 `window` 参数切换）——需求本义是"查询近7天全部（所有用户）的 reaction 数据，全部展示"。

- **历史错误（2026-08-08 / 2026-08-09 上午引入、2026-08-09 下午撤销）**：① 2026-08-08 为修复"列表卡片 toggle 后被重取抹掉 chip"的交互层状态保持缺陷，错误地把"当前用户已参与的 code 不受 Top N 截断"上升为数据契约（保留 Top 4 截断 + 豁免个人项）——交互层问题错误升级为数据契约；② 2026-08-09 上午"纯 Top N"——仍保留 4 条截断，同样违背"全部展示"需求本义。**撤销后（最终口径）**：返回所选窗口内全部 count>0 的 code，无任何截断——用户刚参与的 code 必在返回中（count>0 即返回），"chip 被重取抹掉"从根上消失；个人参与状态（reactedByMe）仅作徽标标注属性、不参与集合构成
- **长期规则**：topReactions 这类"列表摘要"数据契约**返回所选窗口内所有用户点击过的全部表情（无截断）**，个人状态（reactedByMe）只作徽标标注属性、不参与集合构成；交互层状态保持问题（chip 被重取抹掉等）一律在前端交互层解决（乐观更新 + 幂等 reconcile），**禁止通过修改数据契约豁免**——数据契约只表达数据口径，交互层状态由前端自洽

**三窗口计数语义（2026-08 每日一记模型确立）**：`ReactionBadge` 携带 `countAll` / `count7d` / `count30d` 三个窗口计数 + `reactedByMe`。服务端只做"按所选窗口排序/筛选（count=0 不返回）"，前端展示数字 = 所选窗口计数，切换窗口仅本地重算（无需为每个窗口重复请求）。排序/展示统一所选窗口（不再是旧模型的"排序 count30d、展示 countAll"双计数分离）——每日一记模型下取消只作用于当日记录，三窗口的本地 ±1 全部精确，乐观更新无需回滚校正窗口计数。

- **例外：含个人参与状态（`reactedByMe`）**——这是对项目既有"列表层不含个人状态"惯例（原 `tagLikeCounts` 的设计）的刻意打破。原因是产品规则明确要求"点击 Emoji：未参与→+1，已参与→取消"必须在列表页直接可用，用户点击前必须知道自己是否已参与，否则会造成"点了却不知道是加还是减"的困惑。**注意**：此例外仅指徽标**携带**个人状态字段，不改变徽标**集合构成**（见上"完整展示契约"）。此例外**不违反**「缓存内容的强制约束」——聚合计数仍然缓存共享（`VenueReactionAggregateService`），个人参与状态通过**独立的、不缓存的实时批量查询**（`findTodayCodesByUserAndVenueIds`，一次 `IN` 查询覆盖整页场所）获取，两者未被塞进同一个缓存 key
- **个人状态语义**：`reactedByMe` = "今日已参与"（`reactionDate = 今天` 的记录存在）——次日自动恢复可点击状态，与"每日一记"模型一致
- **批量查询**：`VenueReactionService.batchGetBadges(venueIds, currentUserId, window)` 一次 `IN` 查询（`countByVenueIdsGroupByCode`，单条 SQL 用条件 SUM 同时聚合 countAll/count7d/count30d）覆盖聚合计数、一次 `IN` 查询覆盖个人状态（仅登录用户触发），避免逐场所查询的 N+1

toggle 写操作完成后必须同时失效 `VenueReactionAggregateService`（本模块聚合缓存）与 `VenueHeatService`（Reaction 总量是热度公式输入之一），见 `VenueReactionService.toggle()`。

### 聚合缓存架构

`VenueReactionAggregateService` 与 `TagAggregateStatsService`/`VenueHeatService` 同模式：内嵌 Caffeine `LoadingCache<Long, Map<String, long[]>>`（venueId → 每个 Reaction 代码的 `[countAll, countToday, count7d, count30d]`），`refreshAfterWrite(60s)` + `expireAfterWrite(30min)` + 单飞 + 写路径显式 `invalidate`。个人参与状态（`reactedByMe`）永远实时查询、不缓存，与既有的"缓存内容强制约束"完全一致。

### 迁移与数据说明

- `src/main/resources/db/migrate-reaction-daily.sql`：旧 hold 模型 → 每日一记模型的历史迁移（硬删旧软删行、`reaction_date` 回填 `created_at::date`、唯一约束 (user,venue,code) → (user,venue,code,date)）。**历史脚本（已执行），属旧范式遗留，禁止重复执行**——schema 演进一律走 `db/migration/V{n}` 迁移脚本（见「Schema 演进（自动更新优先）」章节）
- 旧行语义近似：迁移后旧"当前生效"行按 createdAt 日期成为"该日一次点击"，窗口统计语义自洽。

### 与现有模块的关系（保留 / 删除 / 替换）

| 模块 | 处置 |
|------|------|
| 综合评分（RatingDimensions 体验评估 4 维度） | **保留**，用于门店整体质量比较，`taginteraction` 模块继续承载 |
| 场所热度（VenueHeatService） | **保留**，用于门店流量排序；Reaction 近30天总数替代原点赞数作为其中一项输入（查询逻辑不变） |
| 营业状态 / 场所状态上报 | **保留**，用于实时判断是否值得去，与 Reaction 是两套独立信号层 |
| 固定属性标签（Venue.tags，如"禁烟""有舞池""自助存包"） | **保留**，纯展示不可互动；原"点赞"这一互动方式已删除 |
| 标签点赞（`TagInteraction.liked`） | **删除**，替换为 Reaction 快速反馈 |
| "现场状况"评分维度（舞伴氛围/客流热度/舞伴年龄层） | **删除**，替换为对应 Reaction（👧/👴/🔥 等） |

### 后续扩展（P1，未在本次实现范围内）

- 门店画像可视化（基于四窗口数据生成"年轻指数/人气/服务/消费"星级评分展示）
- 热门 Reaction 排序算法优化（当前列表徽标按所选窗口原始计数排序；可演进为"今日×5 + 7天×3 + 30天×1"加权评分以更强调近期活跃度）
- 周末热门 / 实时热门 / 到店用户权重（GPS 到店验证）——每日一记模型已按日聚合，可直接基于 `reaction_date` 实现

---



## 2026-08-24 常见表情全放开（双层字典架构，门店 + 舞伴）

**根因**：Reaction 本质是 Telegram 式情感表达媒介，价值随词汇广度与熟悉度增长；历史上按"决策信号价值/使用率"反复收缩字典（18→14→10→12）是语义定位错误，且"每表情一张 PNG + 双端手动同步"使扩展成本 > 收缩成本，结构性偏向收缩。详见[前端文档](../../quwuting/docs/agents/08-reaction-system.md) · 「2026-08-24 常见表情全放开」。

**架构**：
- **`EmojiCatalog.java`**（新，包 `org.quwuting.quwutingservice.emoji`）= 常见表情共享目录单一事实源：~152 项，每项 `code(EMOJI_<HEX>) / emoji / label(CLDR 中文短名) / description(黄脸/物象描述) / polarity(三极性 59 正/68 中/25 负)`。**不承担舞厅业务语义**（业务信号由 legacy code 承担，禁止给新表情塞业务文案）。源文件 UTF-8 明文，严禁手写反斜杠加 u 的 unicode 转义（Java 注释中的此类序列也会被预处理器解析导致编译失败）。
- **`ReactionCode.java`** = legacy 12 业务 code（历史保留，值/文案零变化）+ 目录适配器：
  `allCodes()`（唯一事实源，getStats 遍历/isValid/极性列表均经此取集合）、`isValid/emojiOf/labelOf/polarityOf`、`positiveCodeNames()/negativeCodeNames()`（自动含目录项；NEUTRAL 不进两者——热度公式只计 POSITIVE、NEGATIVE 单独计数，语义不变）。
  **域内去重**：目录项 emoji 去除 VS16（U+FE0F）后与 legacy 撞车即剔除（`catalogCodes()` 缓存，volatile 惰性）。
- **`DancerTagCode.java`**（舞伴认可）= legacy 7 业务 code + 目录适配器（`allCodes/isValid/emojiOf/labelOf`）；
  **舞伴域约束**：目录仅收 POSITIVE/NEUTRAL（NEGATIVE 不进入真实个人主页）；域内去重同门店。
- **消费方改造**：`VenueReactionService.getStats` 遍历 `allCodes()`（原 `values()`）、徽标构建经 `emojiOf/labelOf`（原 `valueOf`）；`DancerDetailCacheService` / `DancerListCacheService` 标签聚合经 `DancerTagCode.isValid + emojiOf/labelOf`（原 `valueOf`——目录 code 不是枚举 value，裸 valueOf 会 500）。
- **DB 零迁移**：`reaction_code`/`tag` 为 varchar(30)（无枚举约束），新增 code 直接可用；旧数据保留，展示层按字典过滤。
- **双端同步配方**：改目录必须三处同步（后端 `EmojiCatalog.java` → 前端 `constants/emoji-catalog.ts` → `types/emoji.ts` 联合类型）；域枚举/域字典自动适配。

**验证**：`./mvnw -q clean test-compile` 通过；前端 tsc 零新错误 + `check:tokens` 通过；双端目录逐项脚本比对一致。
