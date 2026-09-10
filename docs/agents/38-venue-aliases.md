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

### 4.1 「命中即解释」展示契约（2026-09-10，用户拍板方案 B）

**问题**：别名搬出店名后，命中别名时卡片上**找不到用户输的那个词**。判据 = **匹配可自证**：
搜索结果的每一条，用户都应该能在卡片上自己找到他输的词。搜「静安」地址行有「静安区」、
搜「龙女」标签行有「龙女」，都自证成立；别名命中（搜「百乐宫」→ 夜上海歌舞厅）此前
**不可自证**——搜不到用户以为平台没收录，搜到了看不懂用户会认为平台数据错了。
且**修好 4.0 会立刻放大它**：以前搜别名零结果，现在出结果却零解释。

**方案取舍**（用户拍板 B）：

| 方案 | 卡片变化 | 可自证 | 噪音 | 结论 |
|---|---|---|---|---|
| A 全量展示别名行 | 每张卡多一行 | ✔ | 中（99% 场景纯噪音、行高参差） | ✗ 与「低频高价值、展示端弱化」冲突 |
| **B 命中才展示** | 仅命中门店多一行 | ✔ | 零（不命中=零布局变化） | ✓ **采用** |
| C 只修 4.0 | 无 | ✗ | 零 | ✗ 放大问题 |
| D 回填进店名括号（复古） | 店名变长 | ✔ | 高 | ✗ 标题换行、多别名更糟、与详情/分享/公告名字不一致 |

**实现（三层，单点可控）**：

1. `VenueResponse.matchedAlias`（**单值** + 条件下发）：仅本次 keyword 命中该店某条别名时非 null，
   取录入顺序（id ASC，与详情页 aliases 同口径）第一条命中者。全仓只有一处 `new VenueResponse(...)`
   （`VenueResponseMapper`），加十参重载即可。
2. `VenueService#loadMatchedAliases`：keyword 非空时对当页 ≤20 店**一次 IN** 取别名，Java 侧按
   `searchTerms` 逐个做**字面包含**判定（与 reactions/viewCounts/photos 同一批量装配模式，无 N+1）。
   **只补展示、不改结果集**——命中集仍由 KW_MATCH 决定，判定不中的最坏后果仅「少一行解释」，
   绝不漏店。
   - **口径一致性关键**：`searchTerms` 已由 `escapeLikeLiteral` 转义，故必须把候选别名用
     **同一个** `escapeLikeLiteral` 转义后再比较，才能与 `LIKE ... ESCAPE '!'` 对齐
     （否则别名含 `%`/`_` 时展示层与结果集口径分叉）；比较大小写不敏感（对齐 MySQL ci 排序规则
     与前端 highlightSegments）。
3. 前端 `components/venue-card`：名称与元信息行之间插**条件渲染**行（caption 22rpx muted，
   「别名 · X」，命中片段复用同一个 `highlightSegments` 染色）。详见 quwuting 仓
   `42-venue-aliases.md` §5。

**红线**：

- 🚫 `matchedAlias` **只能来自 `qwt_venue_aliases`**，绝不回退到 `VenueSyncAlias.sourceName`
  —— sync alias 是管线匹配配置（信息源原始店名，可能带城市前缀/渠道后缀的脏数据），
  **用户可见红线之外**。
- 🚫 主标题**不得**替换成别名——身份唯一性：公告、分享卡片、收藏、详情页标题必须一致。
- 🚫 列表**不下发** `aliases` 数组（那是详情专属字段，用于身份核验），只下发命中的那一条
  ——守住 §5「列表零带宽」口径。
- ⚠️ **运营顺序**：存量店名里的「（别名）」若要清理，必须**先上 4.0 修复、再删名称括号**；
  反过来的中间窗口里用户搜老名会 100% 搜不到。

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
