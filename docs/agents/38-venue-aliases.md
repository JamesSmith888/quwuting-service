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

## 7. 明确不做（一期）

- **列表卡片不展示别名**——搜索排序已把别名命中顶前（档位 2），卡片空间留给高频信息；
  若后续出现「搜到但认不出」再评估命中别名行。
- 别名**不做**前缀/子串分档（一档足够，数据量小）。
- 认领人（商家）不可维护别名（门店元数据 admin-only，合规口径）。
