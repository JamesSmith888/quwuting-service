# 38 · 门店别名域（venue-aliases）

> 2026-09-07 新增。前端展示层权威文档 = quwuting 仓 `docs/agents/42-venue-aliases.md`。

## 1. 需求与定位

舞厅经常改名，用户只记得**老名字/圈内叫法**时搜不到店、进了详情页也认不出。
门店别名 = 管理员维护的用户可见身份属性：

- **搜索可命中**（列表 KW_MATCH 命中通道 + 相关度排序独立档位 + suggest 联想）；
- **详情页展示**（标题浮层名称正下，身份核验位）；
- **admin 维护**（Web 管理后台「门店别名」页，UGC 红线：不开放给用户/认领人）。

**权重设计结论（用户拍板）**：别名是低频高价值的「身份核验信息」——99% 场景无人看，
搜别名进来的用户靠它对上号。因此搜索端压过地址/描述/标签（身份级匹配），展示端
弱化到 caption（需要时一眼可见，不需要时不打扰）。

## 2. 与同步映射别名（VenueSyncAlias）的语义分离

| | qwt_venue_sync_aliases（V64/V6） | qwt_venue_aliases（本域，V12） |
|---|---|---|
| 语义 | 管线匹配配置：信息源店名 → 平台门店 | 门店身份属性：曾用名/俗称/圈内涵称 |
| key | (city, source_name) | (venue_id, alias) |
| 消费方 | 管线 matcher（export → aliases.json） | 用户搜索 + 详情页展示 |
| 可见性 | 用户不可见 | 用户可见 |

**两表严禁混用**：把用户别名塞进 sync alias 会污染管线导出（matcher 按店名匹配）。

## 3. 数据模型（MySQL V12）

```
qwt_venue_aliases(
  id bigint PK AUTO,
  venue_id  bigint NOT NULL,          -- qwt_venues.id
  alias     varchar(100) NOT NULL,
  deleted   tinyint(1) DEFAULT 0, created_at, updated_at
)
uk_key_qwt_idx_venue_aliases_unique = IF(deleted=0, MD5(CONCAT_WS('#', venue_id, alias)), NULL) STORED  -- 生成列部分唯一
INDEX qwt_idx_venue_aliases_venue(venue_id)
```

- **PG 轨道冻结**（V65 后停更，V10/V11 起仅 MySQL 双轨）——本域只写 MySQL V12。
- 幂等：同店同名至多一条有效记录；**删除走软删，重新添加同名复活重用**
  （`findByVenueIdAndAlias` 不筛 deleted，upsert 置 deleted=false）。

## 4. 搜索链路接入（VenueRepository）

- **KW_MATCH**：加第 8 个命中分支 `EXISTS (VenueAlias va ... va.alias LIKE :keyword ESCAPE '!')`
  —— `findIdsByKeyword`（拆词 AND 行集探测）自动继承，单词/多词全通。
- **RELEVANCE_KEYS**：名称前缀(0) > 名称子串(1) > **别名命中(2)** > 其余字段/同步别名(3)；
  keyword=null 时 CASE 恒等（ELSE 3 / ELSE 1）退化纯热度零变化。
  档位依据：别名=身份级匹配，必须压过「描述里顺带提到」。
- **suggestByName**：别名中缀命中分支（与 sync alias 并列）——联想接口保留，命中口径
  随 KW_MATCH 同步演进（「命中载体一致」契约）。
- LIKE 字面转义纪律沿用（Service 层 escapeLikeLiteral + ESCAPE '!'）。
- HQL 语法回归：`VenueListQueryHqlSyntaxTest`（CASE WHEN EXISTS 已过 ANTLR grammar 校验）。
- **镜像回归门禁（2026-09-10 新增）**：`VenueAliasMatchMirrorTest` —— 纯字符串断言 + 注解反射
  （零依赖、不连库），锁住「别名载体的三处镜像都在」。§4.0 那类事故（只改注释与另两处、
  漏谓词本体）编译与 HQL 语法测试**都发现不了**，只能靠它拦住。**局限**：只验载体存在、
  不验谓词写法（EXISTS/软删/ESCAPE 仍需人工 + 语法测试把关）——但"整个分支消失"这一最
  常见的漏改形态可被完全拦住。增删命中载体时若本测试报红，先回去核对三处谓词本体。

### 4.0 🚨 2026-09-10 补漏：KW_MATCH 曾漏掉别名分支

**这是本域落地时的一次真实事故，写在这里防止再犯。**

2026-09-07 引入本域时，别名被接进了 **RELEVANCE_KEYS（档位 2）** 与 **suggestByName（中缀兜底）**，
`KW_MATCH` 的 **javadoc 也改成了「六字段 + 双别名载体」**——但**谓词本体漏了**，实际只有 7 个
分支（六字段 + sync alias）。后果链：

| 环节 | 结果 |
|---|---|
| 列表搜索（单词） | `keywordPattern → KW_MATCH` → 搜不到别名 |
| 列表搜索（多词） | `findIdsByKeyword`（继承 KW_MATCH）→ 同样搜不到 |
| 联想 suggest | 能命中，但**前端浮层 2026-09-02 已下线** → 用户侧零可用路径 |
| RELEVANCE_KEYS 别名档 2 | 别名门店根本进不了结果集 → **死代码** |

**红线（新增）**：`KW_MATCH` / `RELEVANCE_KEYS` / `suggestByName` 是**同一契约的三处镜像**，
增删命中载体必须三处同改；其中 **`KW_MATCH` 是唯一的结果集决定方**（另两处只管排序/联想）
——只改后两处 = 用户完全搜不到，而排序档位再"正确"也无从生效。
改任一处的注释时，**必须回头核对谓词本体**（本次事故正是「注释改了、SQL 没改」）。

### 4.1 「命中即解释」展示契约（2026-09-10 立，2026-09-16 **通用化**）

**判据 = 匹配可自证**：搜索结果的每一条，用户都应该能在卡片上自己找到他输的那个词。
搜不到用户以为平台没收录，搜到了却在卡片上找不到输入词，用户会认为平台数据错了。

#### 4.1.1 2026-09-16 触发与修订（本节的根因，勿删）

用户报障：**「通过详细地址匹配到的门店直接出现在结果列表，用户一脸懵逼——列表 item 不展示地址详情」**。

复盘结论：判据没错，**落地时对「哪些载体需要解释」的盘点错了**——只盘点了「别名」一个不可自证
载体，其余想当然当成"已自证"。旧版原文证据（本次修订前）：「搜『静安』地址行有『静安区』、
搜『龙女』标签行有『龙女』，都自证成立」——这句话把 `district`（位置行「上海 · 静安区」）
**误当成了"地址行"**；而 **门牌/路名级的详细地址（`address`）从来就没在卡片上出现过**。
同类漏盘还有两个：`description`（卡片无简介行）与 `VenueSyncAlias.sourceName`（卡片无收录名行）。

#### 4.1.2 载体全集（`KW_MATCH` 八项 × 卡片可见性）

**改任何一处前先看这张表**——它是「某载体该怎么处置」的唯一判据来源：

| # | 命中载体（KW_MATCH） | 卡片落点 | 自证 | 处置 |
|---|---|---|---|---|
| 1 | `v.name` | 标题行（`nameParts` 高亮） | ✅ | 染色，**不占**解释行 |
| 2 | `VenueAlias.alias` | 无 | ❌ | 解释行 `ALIAS` |
| 3 | `VenueSyncAlias.sourceName` | 无 | ❌ | 解释行 `SYNC_ALIAS`（裁剪后） |
| 4 | `v.address` | 无（位置行只到区级） | ❌ | 解释行 `ADDRESS`（城市级类型脱敏，见 4.1.4） |
| 5 | `v.description` | 无 | ❌ | 解释行 `DESCRIPTION`（命中片段） |
| 6 | `v.city` | 位置行「上海 · 静安区」 | ⚠️ 可见无标记 | `locationParts` 染色 |
| 7 | `v.district` | 位置行「上海 · 静安区」 | ⚠️ 可见无标记 | `locationParts` 染色 |
| 8 | `v.tags`（JSON 列） | 标签 chip 行 | ⚠️ 可见无标记 | `tagParts` 染色 |

> 补充事实：`venue.default.tags` YAML 2026-08-20 已置空 ⇒ tags 的「匹配对象（DB JSON 列）
> ≡ 展示对象（merge 后数组）」，当前无口径分叉（若将来恢复默认标签，**表 8 行就地失真**，
> 因为 `KW_MATCH` 匹配的是 DB 列、不匹配配置注入的默认标签）。

**处置判据（本节的派生规则，新增载体时照此回答）**：

- 卡片上**找不到**该载体 → **出解释行**（职责 = 补上卡片缺失的那段文本）；
- 卡片上**看得见**该载体 → **对既有行染色**，🚫 **禁止新增行**（同一事实两处表达 + 卡片行预算；
  「可见但无标记」仍是缺陷——用户看得到词却认不出「这是你搜的」，故必须染色）。
  → 这条正是 `VenueMatchField` 枚举**只有四个值**的原因（见该枚举 javadoc）。
- 命中载体**已能被更靠前的载体覆盖**时短路：`name/city/district/tags` 里能找到的输入词
  视为已自证，不进解释行判定（`isSelfEvidentOnCard`）。

#### 4.1.3 实现（三层 + 一个脱敏点，全单点）

1. `VenueResponse.matchedHint`（`VenueMatchHint{field, text}`，**单值** + 条件下发）：
   按 **ALIAS > SYNC_ALIAS > ADDRESS > DESCRIPTION** 取第一个命中的不可见载体。
   全仓只有一处 `new VenueResponse(...)`（`VenueResponseMapper`），十参重载末位承载。
   > **原 `matchedAlias` 已被取代，未保留兼容双通道**（ALIAS 命中即由 `matchedHint.field=ALIAS`
   > 等价表达）——保留会让「同一事实两字段」长期化。代价：未更新的老客户端会失去别名解释行，
   > 需随本次改动重新发版。
2. `VenueService#loadMatchHints`：keyword 非空时对当页 ≤20 店**一次 IN** 取两张别名表，
   Java 侧按**原始词**逐个做**字面包含**判定（与 reactions/viewCounts/photos 同一批量装配模式，
   无 N+1）。**只补展示、不改结果集**——命中集仍由 KW_MATCH 决定，判定不中的最坏后果仅
   「少一行解释」，绝不漏店。
   - **口径一致性（本次修订了旧版结论）**：旧版要求「候选与词都先 `escapeLikeLiteral` 再比较」。
     实际 `LIKE '%词%' ESCAPE '!'` 在词为字面量时与 `contains` **完全等价**——转义只改变 SQL
     通配符语义（把 `%`/`_`/`!` 降级为字面字符），两边同时字面化后包含关系不变。故本层用
     **原始词**：既与结果集同口径，又让 `indexOf` 的位置能直接映射回原串切片
     （**转义会改变串长**，位置映射即失真——描述片段截取必须用原始词的根本原因）。
     拆词点也因此收敛为全仓唯一 `splitRawSearchTerms`：**转义是 SQL 通道的事，展示层一律用原始词**。
   - 比较大小写不敏感（对齐 MySQL ci 排序规则与前端 `highlightSegments`）。
3. 前端 `components/venue-card`：名称与元信息行之间插**条件渲染**行（caption 22rpx，
   「{载体标签} · {命中原文}」，命中片段复用**同一个** `highlightSegments` 染色；
   行槽沿用 2026-09-10 的别名行，卡片行预算零增长）。三个可见载体改为对既有行分段染色。
   详见 quwuting 仓 `42-venue-aliases.md` §5。
4. **脱敏点**：`VenueResponseMapper#demaskMatchHint`（见 4.1.4）。

#### 4.1.4 城市级地址类型的连带处理（2026-09-16 用户拍板）

**冲突**：`KW_MATCH` 在 **SQL 层**匹配实体里的完整 `address`，而地址脱敏闸门
`VenueResponseMapper#cityOnlyAddress` 在**响应层**把 district/address 置 null。两条路径不同层 ⇒
歌友会（城市级地址）**会被详细地址搜到，却连区县都看不到**，永久无法自证；若为解释而回传地址，
又直接击穿「只公开到城市级」的口径。

**处理**：保留命中，把 `text` 降为 **null**——「这家店是因为地址被搜到的」这一**事实**可以说明，
具体地址**不可以**；前端渲染「需联系获取」（`VENUE_MATCH_WITHHELD_TEXT`）。
`text` 为 null **不得**被当作「无解释」而整行不渲染，那会让用户回到完全懵的状态。

**为什么写在 Mapper**：本类是全仓唯一的实体→响应映射点，地址可见性判定只此一处
（与 district/address 置 null 同一闸门）。**禁止在 Service 或前端另开第三个判定**——
本次要修的正是「SQL 层匹配 × 响应层脱敏」分层的产物，不该再叠一层分叉。

#### 4.1.5 红线

- 🚫 `matchedHint.field` 只允许取**卡片上不可见**的四个载体（`VenueMatchField` 枚举值域）；
  给 `name/city/district/tags` 补解释行 = 与染色机制重复表达。
- 🚫 `SYNC_ALIAS` 展示前**必须**经 `sanitizeSyncName` 裁剪。
  > ⚠️ **旧禁令修订（2026-09-16）**：本节旧版写「🚫 `matchedAlias` 只能来自 `qwt_venue_aliases`，
  > 绝不回退到 `VenueSyncAlias.sourceName`——那是管线匹配配置（信息源原始店名，**可能带城市前缀/
  > 渠道后缀的脏数据**）」。**实证推翻**：源侧 323 条真实 `source_name` 中渠道后缀 **0** 条；
  > 以城市名开头的仅 2 条且均为**店名本体**（「湖州梦境文化俱乐部」「秦皇岛莎莎舞」）。
  > 该顾虑是防御性假设，按假设剥前缀会误伤合法店名（**用一个不存在的风险换真实的坏数据**）。
  > 故改为：保留该载体并**保守裁剪**（trim / 空白折叠 / 尾部空括号），且**明确不剥城市前缀**。
  > 将来源侧真出现脏前缀，先治数据、再谈裁剪。**副作用（可接受）**：搜圈内叫法命中的门店，
  > 卡片会多一行源店名——这恰是「匹配可自证」要的效果。
- 🚫 地址可见性判定**唯一**归 `VenueResponseMapper#demaskMatchHint`；Service 只取原文，前端不做判断。
- 🚫 主标题**不得**替换成别名/收录名——身份唯一性：公告、分享卡片、收藏、详情页标题必须一致。
- 🚫 列表**不下发** `aliases` 数组（那是详情专属字段，用于身份核验），只下发命中的那一条
  ——守住 §5「列表零带宽」口径。
- ⚠️ **运营顺序**：存量店名里的「（别名）」若要清理，必须**先上 4.0 修复、再删名称括号**；
  反过来的中间窗口里用户搜老名会 100% 搜不到。

#### 4.1.6 已知边界（本次有意不做，避免误以为漏了）

- **多词部分自证**：`matchedHint` 是**单值**。「词 A 命中 name、词 B 命中 address」时只解释
  优先级最高的那一个，另一个仍可能不自证。判据 = 结果集相关度已把 name 命中顶前，
  且多词结果量小；若真机出现「两个词都找不到」的抱怨，再评估升级为列表载荷（前端需改多行槽）。
- **`description` 片段**：取命中词前后各 8 字（`MATCH_SNIPPET_PAD`），两端越界补省略号；
  整行单行 ellipsis。简介是弱解释性载体，不为其放开换行。

## 5. 详情下发（VenueDetailResponse.aliases）

- `List<String> aliases` 加在 **VenueDetailResponse 顶层**（详情专属，列表 VenueResponse
  零改动零带宽）；录入顺序（id ASC）。
- 归属**公共部分缓存体**（`VenueDetailPublic`，Caffeine refresh-ahead 30s）：与请求用户
  无关的门店身份事实——冷启动 +1 查、缓存命中零往返；**管理端写路径必须
  `venueService.invalidateDetailPublic(venueId)`**（VenueAliasService#upsert/#delete 已接）。

## 6. 管理端接口（AdminVenueAliasController，仅平台管理员）

| 接口 | 说明 |
|---|---|
| `GET /admin/venue-aliases` | 已配置别名的门店聚合列表（组=门店+别名列表，组序=最近配置在前，悬空门店跳过） |
| `GET /admin/venue-aliases/venue-search?keyword=` | 门店候选（名称模糊 ESCAPE 字面口径，不限状态——停业店也可能配曾用名；keyword 空 = 最近收录 20 家兜底） |
| `POST /admin/venue-aliases` | 幂等 upsert `{venueId, alias}`（同店同名复活），返回 `{id, alias}` |
| `DELETE /admin/venue-aliases/{id}` | 软删 |

Web 管理后台 = 「更多 → 门店别名」独立视图（VenueAliasView.vue + services/venueAlias.ts），
与「门店同步 → 映射管理」入口分离。

## 7. 明确不做 / 已改判

- ~~**列表卡片不展示别名**~~ → **2026-09-10 改判**：当时理由是「搜索排序已把别名命中顶前
  （档位 2），卡片空间留给高频信息；若后续出现『搜到但认不出』再评估命中别名行」。
  **该触发条件已出现**，且实测暴露「档位 2 是死代码」（见 §4.0）——现已按 §4.1 落地
  **「命中即解释」**：不是全量展示（方案 A 仍否），而是**仅命中别名时**在名称正下方
  复现用户输的那个词。既有结论「别名是低频高价值的身份核验信息、展示端弱化到 caption」
  **不变**，命中行正是 caption 档的条件化实现。
- 别名**不做**前缀/子串分档（一档足够，数据量小）——不变。
- 认领人（商家）不可维护别名（门店元数据 admin-only，合规口径）——不变。
- 别名**不进热度/排序权重**（身份属性非行为信号）——不变。
