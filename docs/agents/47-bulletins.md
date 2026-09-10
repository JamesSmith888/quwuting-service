# 47 · 行业快讯（bulletins）

> 2026-09-10 设计定稿并落地；**同日二次定稿**：信息架构由「列表 + 详情」改为 **TG 频道式信息流**
> （列表内联全文），并开放**表态**（一人一条恒一个表情）。
>
> ⚠️ **本文件在两仓各存一份**（`quwuting/docs/agents/` 与 `quwuting-service/docs/agents/`），
> 内容保持完全一致，改动须双向同步（同 skill 双位置约定的理由：两仓是独立 Git 仓库）。
>
> 后端实现：`org.quwuting.quwutingservice.bulletin` 包；数据结构复用公告表
> （`qwt_announcements` + `category='FLASH'`）；迁移 `V18__bulletins.sql`（内容字段）
> + `V19__bulletin_reactions.sql`（表态表）。
> 姊妹文档：`34-announcements.md`（公告域，接口契约冻结、本域不改动它）。
> 发布 Skill：`quwuting-bulletin-publish`。

## 一、领域定位与信息架构（最重要的一节）

快讯与公告是**两个语义域**，靠一条判据分界：

> **分界线 = 是否需要平台为这条内容的真实性背书。**

| 维度 | 公告（NOTICE / DATA_UPDATE） | 快讯（FLASH） |
|---|---|---|
| 内容性质 | 平台权威内容（数据更新 / 新功能 / 规则） | 行业情报（停业 / 开闭店 / 时段调整） |
| 发布者 | 平台官方 | 平台编辑整理（转载性质） |
| 频率 | 低频 | 高频 |
| 触达 | 强：首页悬浮公告条 + 红点 + 已读回执 | 弱：tabBar 入口 + 信息流，**无红点无已读** |
| 平台背书 | 有（平台担责） | 无（列表尾部标注"仅供参考"） |
| 排序 | pinned 优先 | 纯时间流，无置顶 |
| **信息架构** | **列表（索引）→ 详情（本体）** | **列表即本体（全文内联气泡）**，详情 = 长文深读 / 分享落地 |
| 互动 | 无（平台单向） | 表态（一键表情，平台固定字典，**永久一人一票**） |
| 生命周期 | 建议设 offlineAt 防霸屏 | 自然沉底，建议 24–72h 自动下线 |

### 1.1 信息架构：为什么是"信息流"而不是"列表 + 详情"（2026-09-10 二次定稿）

**现象**：一期把快讯做成「列表（城市标签 + 时间 + 标题）→ 点进详情（towxml 渲染全文）」，
与公告中心 1:1 同构。真实内容一进来立刻暴露问题——**用户点开详情，只为看一句话或一张图**；
每次消费都是"多一次跳转 + 多一次请求 + 一次返回"。

**当时的决策依据（不是随手抄的，是有理由的错误决策）**：

1. 复用最大化：复用公告实体（`qwt_announcements`）、状态机、towxml 渲染管线，
   连公告的**读取结构**一起继承了；
2. 内容模态假设：快讯 = 一句话的服务可得性情报（"XX 舞厅自 X 日起暂停营业"），
   **标题即内容**，列表足够表达，正文只是补充说明；
3. 合规假设："与公告同构 = 不新增审核面"，结构越像公告越安全。

**这三条错在哪里（根因，逐层）**：

| # | 根因 | 说明 |
|---|---|---|
| 1 | **内容模态判断错误** | 行业情报天然带媒体：公告截图、门口照片、营业时段表、现场视频。"标题 + 点开看正文"这种**文字优先**结构承载不了图片/视频为主导的内容模态——用户进详情只为看一眼图。 |
| 2 | **把 IA 当成了可复用资产** | 公告是**被推送、被读一次、要落档**的权威内容（列表 = 索引，详情 = 本体）；快讯是**被浏览、被扫视、连续消费**的时间流（列表**本身**就是本体）。**IA 由消费方式决定，不因"数据表长得像"而相同**。可复用的是实体/存储/渲染管线（技术资产），不是 IA 与交互形态（产品决策）。 |
| 3 | **"同构即零审核面"是错误归因** | 决定审核面的是**用户能不能生产内容**（投稿 / 评论 / 自由文本 / 转发到列表），不是页面数量与结构。为了规避想象中的结构风险而牺牲 IA 合适性，等于把合规问题当结构问题解。 |
| 4 | **"零互动"红线被过度扩大解释** | 一期快讯写了"零评论点赞"，本意是守"无 UGC"，实际执行成"无任何交互"——同一 App 的门店列表卡片早已有 Reaction（平台固定字典、无自由文本、一键表态），快讯开放同款并不新增"用户生成内容"这一类能力。代价是快讯一夜之间没有任何反馈信号。 |

**修复（本轮落地）**：

- `pages/bulletins` = **信息流**：每条快讯是一枚气泡，**在列表内完整呈现**
  （城市/时间 + 标题 + markdown 正文 + 表态行），不再有"只有标题、内容在别处"的中间态；
- 列表接口下发 `content` 全文（`BulletinFeedItemResponse`），并附带该条表态徽标；
- `pages/bulletin-detail` **保留**，但定位收窄为两条专用通道：**长文深读**（正文很长时
  单页阅读优于流内滚动）与**分享落地**（分享卡片直达单条，比"落到信息流某处"更稳）。

**长期判据（防复发，写进本文件即约束）**：

> 1. **IA 判据（新内容域先回答一句话）**：用户是"**读一条**"还是"**刷一屏**"？
>    读一条 → 列表（索引）+ 详情（本体）；刷一屏 → **列表即本体**（内联全文/媒体），
>    详情只承担"长文深读 / 分享落地 / 单条直达"。
> 2. **复用边界判据**：可以复用**实体 / 存储 / 渲染管线 / 组件**；**不复用信息架构与交互形态**
>    ——后者必须按本域的消费方式重新决策，并写进本文档。
> 3. **合规判据**：审核面 = 用户能否**生产内容**（投稿 / 评论 / 自由文本）。平台固定字典的
>    **一键表态不构成内容生产面**；反之，任何"用户写点什么上去"的改法都是死线（见 §2）。

### 1.2 🚨 内容边界（比技术实现更重要）

**快讯只描述「服务可得性」**——哪家店 / 哪个时段 开或关。

- ✅ 「XX 舞厅自 9 月 10 日起暂停营业，恢复时间待定」
- ✅ 「近期 XX 城区多家门店营业时间调整为 19:00 起」
- ❌ 「XX 舞厅因××被查」/「XX 舞厅停业整顿」
- ❌ 「XX 舞厅发生××事件」

理由：平台是**发布者**，失实内容直接构成名誉权风险（《民法典》1024/1025 条）；
事件类信息必然牵涉执法/治安，属审核高危区，叠加舞厅行业属性（见
`MEMORY-REVIEW-COMPLIANCE.md` 第六节）等于自我加码。**原因不明就不写原因；涉单店
负面信息一律不发。**

> 按用户 2026-09-10 拍板：**不引入自动敏感词拦截**（词表化会把风控责任转移到代码，
> 且易产生漏网的虚假安全感），改由发布前人工把控 + 管理端界面常驻边界提醒
> （`BulletinListView` 顶部 policy-tip / `BulletinEditView` 正文区 md-policy）。

### 1.3 审核合规前提（2026-09-10 修订口径）

个人主体小程序曾被驳回「涉及用户自行生成内容的发布/分享/交流，属社交范畴」
（详见 `MEMORY-REVIEW-COMPLIANCE.md`）。快讯域**合规成立的前提**：

- 小程序端**零输入控件、零评论、零投稿、零转发到列表**——用户不能产生任何内容；
- 全部**内容**写操作只在 admin-web 与 Agent 接口；
- 用户侧唯一的写操作是**表态**：一键选中平台固定字典里的表情，**没有自由文本**，
  与门店列表卡片的 Reaction 完全同构（该能力已随 App 在线上运行，不是新增的交互品类）；
- 形态是「官方单向发布的内容展示 + 一键情绪反馈」，**不新增内容生产面**。

⚠️ 任何"开放投稿 / 用户爆料上墙"的改法都会立刻把本域变成 UGC，**禁止**；
同理，**禁止**给表态加自由文本（评论 / 备注 / 自定义表情）。

## 二、数据模型

### 2.1 内容（复用 `qwt_announcements`，V18）

新增三列 + 一个 Agent 幂等唯一键（公告条目三列恒为 NULL，零影响）：

| 列 | 类型 | 用途 |
|---|---|---|
| `city` | varchar(32) | 城市标签（气泡头部展示；**一期仅展示，不做筛选/定向**） |
| `venue_id` | bigint | 关联门店（可空；服务端校验真实性） |
| `dedup_key` | varchar(64) | Agent 幂等去重键（非空才参与唯一约束） |

```sql
-- 生成列唯一索引：仅对「未软删 + FLASH + dedup_key 非空」生效（V7 DATA_UPDATE 同款先例）
uk_key_qwt_idx_ann_flash_dedup = IF((deleted=0) AND (category='FLASH') AND (dedup_key IS NOT NULL),
                                    MD5(dedup_key), NULL) STORED
CREATE UNIQUE INDEX qwt_idx_ann_flash_dedup ON qwt_announcements (uk_key_qwt_idx_ann_flash_dedup);
CREATE INDEX qwt_idx_ann_category_status_publish ON qwt_announcements (category, status, publish_at);
```

枚举扩值（varchar 存储，**无需迁移**）：

- `AnnouncementCategory` += `FLASH`
- `AnnouncementSource` += `AGENT`（Agent 投放通道专用，服务端固定，请求体不可指定）

### 2.2 表态（新表，V19）

| 列 | 类型 | 说明 |
|---|---|---|
| `user_id` | bigint | 表态人 |
| `bulletin_id` | bigint | 快讯 id（`qwt_announcements` 中 `category='FLASH'` 的条目） |
| `reaction_code` | varchar(30) | 字典 code（`EMOJI_<HEX>`） |

```sql
CONSTRAINT qwt_uk_br_user_bulletin UNIQUE (user_id, bulletin_id)   -- 一人一条恒一个表情
CREATE INDEX qwt_idx_br_bulletin ON qwt_bulletin_reactions (bulletin_id, deleted);
```

- **唯一键就是语义维度**：换票 = `UPDATE` 同一行的 code，取消 = 物理删除该行
  （同门店域"取消即硬删"口径）。因为硬删，键**不含 deleted**；
  **将来若改软删，必须同步改键**（否则"删除后重投"会撞键）。
- **为什么不需要门店域那种 `SELECT ... FOR UPDATE`**：门店"每日一票"是应用层语义
  （唯一约束按 `(user, venue, code, date)` 建，一票维度落不到键上），并发下必须加锁；
  本域的键正好落在语义维度上，写入走单条 `INSERT ... ON DUPLICATE KEY UPDATE`
  （`reaction_code`/`updated_at` 二次绑定参数，不用 8.0.20 起废弃的 `VALUES()`），
  并发只会收敛到同一行——**用键表达不变量，就不用锁去模拟键**。
- 不建外键（全库无 FK 约定）；时间戳由 Java 传 `LocalDateTime`（禁 DB `now()`）。

## 三、与公告域的互斥契约

**两个域共用 `AnnouncementRepository`，靠 `category` / `excludeCategory` 双向隔离：**

| 查询方法 | 公告侧 | 快讯侧 |
|---|---|---|
| `findVisiblePage` | `category=null, excludeCategory=FLASH` | `category=FLASH, excludeCategory=null` |
| `findPageByFilters` | 同上 | 同上 |
| `countUnread` | `excludeCategory=FLASH`（**快讯无已读回执，绝不能计入公告未读数**，否则红点永不收敛） | 不使用 |

服务端还有一道防御：`AnnouncementService#rejectFlashCategory` —— 公告管理端
`create`/`update` 若收到 `category=FLASH` 直接 400。反向同理：
`BulletinLookupService#requirePublished/requireAny` 校验 `category == FLASH`，
非快讯条目一律 404，**不能借快讯接口读写公告**。

**可见性规则单一事实源（2026-09-10 抽离）**：`BulletinLookupService` 承载
「FLASH + PUBLISHED + 已生效 + 未软删」的判据，读接口（列表/详情）、写接口（表态）、
管理端（任意状态）统一从这里取条目。**根因**：表态服务需要可见性校验、而快讯服务需要
表态数据（列表下发表情）——若双向依赖即循环依赖；生产级解法是把领域规则抽成下层共享
组件（同门店域 `VenueLookupService` 先例），**不是** `@Lazy` 打补丁。

> 实现取舍：`BulletinService` 与 `AnnouncementService` 存在少量重复代码（内容校验 /
> 调度窗口校验 / 状态流转）。这是**有意为之**——两个域可各自独立演进，不因改一个
> 而牵动另一个；且**公告域接口契约（含已上线的 `quwuting-announcement-publish` skill）
> 全程零改动**，只改了 Repository 查询条件。

## 四、接口契约

### 4.1 用户端（需登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/bulletins?page=&size=` | 信息流（PUBLISHED + 已生效，`publishAt DESC, id DESC`）；**每项含 `content` 全文 + `reactions`** |
| GET | `/bulletins/{id}` | 详情（markdown 原文 + `reactions`；未发布/已下线/已删/非 FLASH → 404） |
| POST | `/bulletins/{id}/reactions/{code}` | **表态 toggle**（参与 / 取消 / 换票） |

- 列表项 DTO = `BulletinFeedItemResponse`（**原 `BulletinSummaryResponse` 已删除**：
  它是"只带标题的摘要"，在信息流里语义已不成立）；与详情 DTO 的差别只剩
  "是否携带 `publishedAt`"。
- **无已读回执**（刻意）：不进红点、不计未读数，新鲜度由列表的相对时间表达。
- 表态路由形状与门店域 `/venues/{venueId}/reactions/{code}` 一致（code 走路径、无请求体）。
- **一期不支持城市筛选**：`city` 随条目返回，仅作气泡头部展示标签。

### 4.2 管理端（需 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/bulletins?status=&source=&page=&size=` | 列表（id 倒序，恒只含 FLASH） |
| GET | `/admin/bulletins/{id}` | 详情 / 编辑回显 |
| POST | `/admin/bulletins/create` | 建草稿（source 固定 MANUAL） |
| POST | `/admin/bulletins/{id}/update` | 更新（状态机见下） |
| POST | `/admin/bulletins/{id}/publish` | 发布（body 可带 `publishAt` 定时；缺省立即） |
| POST | `/admin/bulletins/{id}/offline` | 下线 |
| POST | `/admin/bulletins/{id}/delete` | 软删除 |

### 4.3 Agent 通道

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/bulletins/agent-publish` | **一步 create + publish**（agent 场景不需要草稿态），source 固定 AGENT，`dedupKey` 幂等 |

请求体：`{title, content, city?, venueId?, dedupKey?, publishAt?, offlineAt?}`

- `content` = markdown（**含媒体语法，见 §六**）；
- `publishAt` 未来时刻 → 定时（状态 DRAFT，由既有 `@Scheduled` 30s 调度强转 PUBLISHED）；
  缺省/过去时刻 → 立即发布；
- `source` 由服务端固定 `AGENT`，**请求体不接受指定**；`operator_id` 记调用管理员审计。

### 4.4 状态机（与公告域同构）

```
DRAFT --publish(立即/定时)--> PUBLISHED --offline / offlineAt 到点--> OFFLINE
  ^                                                                    |
  +--------------------- publish（唯一复活通道） -----------------------+
任意状态 --delete--> 软删
```

- **DRAFT**：全字段可改（含 `publishAt`）。
- **PUBLISHED**：除 `publishAt` 外全字段可改并即时生效。
- **OFFLINE**：禁改，需 `publish` 复活。
- 重新发布时会**清空过期的遗留 `offlineAt`**（否则 30s 调度会立刻再度下线）。

## 五、幂等设计（Agent 契约）

两层防护，与公告域 DATA_UPDATE 同日防重同款：

1. **查询前置**：`findFirstByDedupKeyAndDeletedFalseOrderByIdDesc(dedupKey)` 命中 →
   直接返回已存在条目，**不改写其任何字段**。
2. **唯一索引兜底**：并发撞键由 V18 生成列唯一索引拦下。

**为什么命中不改写字段**：重跑语义是「确保这条存在」，不是「覆盖」——
采集源事后纠错应由人工走管理端 `update`，不能被重跑悄悄盖掉。

**约定**：`dedupKey` 格式 `{来源}:{日期}:{主题键}`，例 `xianbao:2026-09-10:cq-stop`。
内容有更新时要么换新 key（发第二条，并列显示），要么走 `update` 原地改——
**改内容却复用旧 key 会静默不生效**（最常见的"发了没反应"）。

## 六、快讯表态（2026-09-10 新增）

### 6.1 语义：一人一条内容恒一个表情（永久一票）

| 点击前 | 点击 | 结果 | 返回 |
|---|---|---|---|
| 未表态 | 任意 code | 参与 | `{reacted: true, replacedFrom: null}` |
| 已表态（同 code） | 同 code | 取消（硬删该行） | `{reacted: false, replacedFrom: null}` |
| 已表态（异 code） | 新 code | **换票**（原地改 code，计数此消彼长） | `{reacted: true, replacedFrom: 旧 code}` |

- **"永久"而非"每日"**：门店 Reaction 是"每日一票"（次日可再评一次现场）；快讯是**一次性内容**，
  没有"次日再来评一次"的场景，故一票终身。这既是产品语义，也让"一人一条"能被 DB 唯一键直接承载（§2.2）。
- **首条表态与换票共用一条原子语句**（`INSERT ... ON DUPLICATE KEY UPDATE`），无应用层锁。
- **计数不缓存**：列表按整页 id 一次 `IN + GROUP BY` 聚合、一次 `IN` 取个人表态，
  无 N+1、无聚合缓存（快讯表态量级远低于门店，缓存收益低于陈旧风险）。
- **只下发 `count > 0` 的 code**（展示 = 真实用户行为；创建入口在前端 Picker）——
  最后一人取消后该 chip 从行中消失，与门店 Reaction "至少一人参与才显示"同一不变量。
- 排序：人数降序，**并列按字典声明序**（确定性——同样的数据每次返回同一顺序，前端不抖动）。

### 6.2 字典：快讯专属 10 枚通用情绪（**不是门店字典的子集**）

| 极性 | 表情 |
|---|---|
| 正向 6 | 👍 赞 / ❤️ 红心 / 🔥 火 / 🎉 派对彩带 / 👏 鼓掌 / 🙏 双手合十 |
| 中性 2 | 😮 吃惊 / 🤔 思考 |
| 负向 2 | 😢 大哭 / 😡 发怒 |

- **为什么不用门店字典**：门店字典 = legacy 业务信号（机车 / 龙女 / 极品 / 收费偏高 /
  场内禁烟…）+ 常见表情目录，语义全部锚在"**这家店**怎么样"；快讯是行业情报，
  把「场内禁烟」粘到一条停业快讯上语义不通。按 `08-reaction-system.md`
  「常见表情层与业务信号层分离」的分层，快讯表态属**纯情感表达层**。
- **实现形态 = 域适配器**：后端 `BulletinReactionCode` 只**挑选** code 引用共享目录
  `EmojiCatalog`（`emojiOf/labelOf` 直接取目录值），**不复制条目、不自造 emoji**
  （同舞伴域 DancerTagCode 先例）；前端 `constants/bulletin-reactions.ts` 从
  `EMOJI_CATALOG` 派生条目——两端各只有**一份 code 列表**需要手工同步。
- **为什么是小集合**：门店 Picker 有「展开全部」承载 100+ 项；快讯是**信息流**，
  表态行紧贴内容、要一瞥可辨（TG 频道的 reaction 同样是固定小集合），故不做展开。
  10 项 = Picker 4×2 + 末行 2 居中。
- **单调性护栏**：零依赖测试 `BulletinReactionCodeTest` 断言"每个 code 必须存在于
  `EmojiCatalog` 且 emoji/label 非空 + 无重复 + 规模锁定 10"——目录项被删时**测试红**，
  而不是运行时下发 `emoji: null` 让前端渲染空白格。

### 6.3 前端链路

- **展示**：复用 `components/reaction-chips`（emoji + 计数 chips；`bordered=false`）。
- **选择**：复用 `components/reaction-picker`，新增 `variant="bulletin"`——
  字典源 / 能否「展开全部」/ 是否显示「表情说明」入口三项**全部收在模块级 `PICKER_SOURCES` 表**里，
  WXML 零分支。快讯域无展开（集合本就收窄）、不显示说明页入口（说明页展示的是门店字典）。
- **语义单点**：`services/bulletin.ts` 的 `planBulletinReactionToggle(prevCode, code)`
  是"点同款 = 取消 / 点新款 = 换票（旧票同时 -1）"的**唯一实现**（纯函数，返回要应用的
  `(code, reacted)` 序列）；列表页与详情页各自用本页的状态原语执行——**禁止两页各写一套迁移规则**
  （同 `08-reaction-system.md`「同一语义只许一个实现」）。
- **乐观更新**（Telegram 式，同门店 Reaction 契约）：登录门禁在乐观应用**之前**
  （拒绝登录不改 UI）→ 按内容 id 串行化（在途守卫）→ 本地立即 ±1 → 请求 →
  成功按服务端真相**幂等重放** → 失败回滚（用点击前状态再调一次幂等原语，原语自逆，
  不需要快照）。
- ⚠️ **一处必须与门店域不同（勿照抄）**：门店 reconcile 的"`replacedFrom` 为空但本地做过
  换票 → 把旧票补回"分支，是因为门店"每日一票"是**应用层开关**语义（开关关闭时多选合法，
  服务端返空可能只是开关状态），故要按服务端补回本地旧票；**本域一人一票是 DB 唯一键**
  （§二 2），服务端返空即"确无旧票"——此时补回会造出**两枚高亮 chip**、破坏一人一票不变量。
  故本域该分支只做"确认旧票为未参与"的幂等 no-op。
  **判据：乐观更新的 reconcile 规则由"不变量的载体"决定（DB 键 vs 应用层开关），
  不能按"看起来是同类交互"照搬。**
- **性能约束（信息流特有）**：表态只 `setData` **该条的路径**
  （`rows[i].reactions`），**不整表重发**——信息流每条都挂着 towxml 节点树，
  整表重发等于把全文再传一遍。
- **长按 chip 看说明**：页面根托管 popover（锚定长按触点，`utils/popover.ts`），
  与门店列表同构；与门店的差异是"守卫在页面自身"（chips 直接长在页面上，
  少一层跨组件寻址，天然不会漏复位）。

## 七、小程序端

### 7.1 tabBar 第三个 Tab（首页 / 快讯 / 我的）

- `custom-tab-bar/index.ts` 的 `list` 插入第 2 项（index=1）；
- `app.json` 的 `tabBar.list` 同步（双处镜像）；
- 各 tab 页 `onShow` 上报选中位：首页 0、**快讯 1**、**我的 2**（原为 1，后移）；
- 图标：TDesign 官方 `sound` / `sound-filled` 进 `scripts/generate-icons.js`
  白名单并重跑 `npm run gen:icons`（产物 `components/qwt-icon/icons.js` 禁手改）。

⚠️ **胶囊宽度保持 360rpx 不变**，三格平分——`left: calc(50% - 180rpx)` 减数、
`TAB_BAR_HEIGHT_RPX`（124rpx）、`timer-float` 锚定位几何全部无需改动。

### 7.2 页面

| 页面 | 说明 |
|---|---|
| `pages/bulletins` | **信息流**（tab 页）。气泡 = 城市标签 + 相对时间 + 「详情」入口 + 标题 + markdown 正文 + 表态行；触底分页；尾部免责声明 |
| `pages/bulletin-detail` | 长文深读 / 分享落地。towxml 渲染 markdown + `normalizeAnnouncementLinks` 归一化 `venue://`；含同一套表态行 |

### 7.3 内容渲染（图片 / 视频 / markdown / 动态）

**全部走 markdown 原生语法，零新增字段**（管理端 bytemd 编辑器与 Agent 通道直接可用）：

| 类型 | 写法 | 小程序侧实现 |
|---|---|---|
| 文本 / 标题 / 列表 / 表格 / 代码 | markdown | towxml（与公告详情同一管线） |
| 图片 | `![说明](图片地址)` | towxml `img` 组件（点击可预览大图） |
| 视频 | `<video src="地址" poster="地址"></video>` | towxml `wxml` 白名单直通原生 `<video>` |
| 门店锚点 | `[店名](venue://门店ID)` | `normalizeAnnouncementLinks` → 门店详情页（外链降级纯文本） |

- 读取链路复用公告详情踩过的两个 towxml 覆盖（本页 wxss 内已覆盖）：主题自带
  `text-align: justify`（中文两端对齐事故）→ 页面级改 `left`；`.h2w__main` 自带
  `margin/padding` → 归零（边距所有权归气泡容器）。
- 一页多枚 towxml 实例（每条内容一枚）：覆盖规则是**页面级单类**，不逐条重复配置。
- **待办（一期不做）**：管理端媒体**直传**（需新增 `FileCategory` + 上传入口）。
  一期媒体 URL 由 Agent 外链或运营粘贴；只要 URL 可公开访问即可。

### 7.4 容器语言：气泡（与「无框化列表」的关系）

`38-list-cardless.md` 定义的是**列表类页面**的容器语言（白带 + 灰缝）。信息流的
一条内容有**完整自洽的边界**（自带的城市/时间、正文、表态），不是"列表中的一行"，
故本页用**气泡**（圆角卡片 + 12px 间距）——用容器边界表达"这是两条独立内容"，
比无缝白带的拼接更准确。**该规范不适用于信息流，本页不是违规，是边界外的另一种形态。**

**⚠️ 画布前提（2026-09-10 事故修复，`38-list-cardless.md` §0 第 0 条）**：气泡能被看见的前提
是**画布与气泡异色**——`page` 默认底色 `--color-bg` 浅色下与气泡底色 `--color-card-bg`
**同为 #ffffff**，只写气泡、不声明画布 ⇒ 白落白，用户实测反馈"每条内容都区分不出来"。
故：
- 本页显式声明灰画布（`page, .bulletins-page { background-color: var(--color-bg-secondary) }`）；
- 气泡表面用全局 `.surface-card`（底色 + hairline + 圆角 + 阴影的唯一入口，深色下靠 hairline
  浮出），页面 WXSS 只写内容边距，**禁重复声明表面五件**；
- 同源缺陷（快讯详情、公告中心、公告详情、管理端登录确认页）一并修复，并由
  **`npm run check:surface`** 门禁防复发（自绘白面 + 同色画布 = 构建失败；豁免须显式登记）。

### 7.5 性能与滚动

- 每页 10 条（`PAGE_SIZE`）：每条内联渲染 markdown，单条 setData 载荷远大于普通列表行，
  页大小直接决定首屏耗时——10 条 ≈ 一屏半；
- 页面级滚动（无 scroll-view）+ `onReachBottom` 触底加载；状态区块与信息流互斥渲染
  （页面自身即滚动容器且恒在，不会出现"空态失去重拉出口"）；
- 全站刷新模型 = `onShow` 重载 + 错误态自带「重新加载」（**本项目无下拉刷新**，不新引入）。

### 7.6 合规复查清单（提审前逐条勾）

- [ ] 小程序端快讯相关页面**无任何 input/textarea/发布按钮**
- [ ] 无评论、无转发到列表的入口
- [ ] 表态**只能**从平台固定字典选（无自由文本、无自定义表情、无备注）
- [ ] 表态是快讯域**唯一**的写接口（`services/bulletin.ts` 只有 `toggleBulletinReaction` 一个写方法）
- [ ] 页面 `onShareAppMessage` 只分享"板块"或"单条内容"，**不鼓励二次传播**（有意不定义 `onShareTimeline`）

## 八、管理端（quwuting-admin-web）

- 菜单：「快讯管理」（`AppLayout.MENUS` 登记，图标 `newspaper-o`）。
- 路由：`/bulletins`、`/bulletins/edit/:id?`。
- 服务：`src/services/bulletin.ts`。
- 列表页顶部常驻**内容边界提醒**；编辑页正文区常驻同类提醒 + **媒体语法提示**
  （`![](图片地址)` / `<video src="…">` / `[店名](venue://ID)`，2026-09-10 新增）。
- 城市用 `/venues/cities`（公开词表）做 action-sheet 选择，保证与门店城市口径一致。
- 列表展示 `dedupKey`，便于排查「为什么没重复发」。

## 九、红线汇总

1. **内容只读、表态可写**：小程序端零内容生产入口（无投稿/评论/自由文本）；
   写操作只有"对已有内容的一键表态"与后台/Agent 的内容发布。
2. **只写服务可得性**：不写事件经过与原因；涉单店负面信息一律不发。
3. **不引入用户内容**：任何"投稿 / 爆料上墙 / 表态加文字"的改法都是审核死线。
4. **IA 判据先于复用**：新内容域先答"读一条还是刷一屏"，再决定 IA；
   可复用实体/存储/渲染管线，**不复用** IA 与交互形态。
5. **公告契约冻结**：本域不修改公告域接口/DTO/状态机语义。
6. **`venueId` 必须真实**：服务端已校验（不存在 → 400）。
7. **Agent 必带 `dedupKey`**：重跑保护；改内容必须换 key 或走 update。
8. **迁移只在 `db/migration-mysql/`**（V18 内容 / **V19 表态**），PG 目录冻结。
9. **一人一票的唯一不变量载体是 DB 唯一键**；若改为软删，必须同步改键（§2.2）。

## 十、验证状态与遗留

**已完成（静态层面，按验证深度红线止步于此）**：

- 后端 `./mvnw -q clean test-compile` 通过；`BulletinReactionCodeTest` 4/4 通过（零依赖字典镜像断言）；
- 小程序 `npx tsc --noEmit` 0 错误；`npm run check` 全绿（tokens 76 / positioning 24 / detailparams）；
  `.ts → .js` 产物按"临时 outDir + 只回拷改动文件"回拷；
- wxml 标签栈配对校验通过（三个改动文件）；
- 管理端 `vue-tsc --noEmit` 0 错误。

**待用户真机验证**：

- [ ] 迁移 V18/V19 在本地库执行成功（Flyway 启动时应用）
- [ ] 信息流气泡：markdown 段落/图片（点击预览）/视频（内联播放）/`venue://` 跳转
- [ ] 深色主题下 towxml 正文与气泡底色
- [ ] 表态：参与 → 换票 → 取消；`count>0` 消失行为；跨页（列表 ↔ 详情）计数一致
- [ ] Picker `variant="bulletin"`：10 枚网格、无「展开全部」、无「表情说明」入口
- [ ] 长按 chip 说明弹层锚定位置
- [ ] 管理端新建 → 发布 → 小程序可见全链路；Agent 幂等（同 key 连发只生一条）
- [ ] 公告域回归：公告中心/首页公告条不出现快讯，未读红点不受影响

**遗留（用户可在需要时开启）**：

- 管理端媒体直传（新 `FileCategory` + 上传入口）——一期用外链 URL；
- 城市筛选 / 按用户城市定向（字段已就绪，一期不做）；
- 表态的运营侧统计（谁点了什么，用于选题）——接口未开，`qwt_bulletin_reactions` 已留数据；
- 阅读统计（快讯无已读回执，暂无阅读率口径）。
