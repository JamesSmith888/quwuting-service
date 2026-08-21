# 舞伴生态与积分系统

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 舞伴生态体系（dancer 模块，2026-08-06 新增；2026-08-10 升级：本人编辑 + 相册与照片审核）

### 设计定位（根因）

「去舞厅」的核心竞争力不是黄页，而是"帮助用户找到值得去的舞厅，以及优秀舞伴"。舞伴体系是
**独立业务域（Dancer Domain）**，与场所体系解耦：

```
舞厅
 ├── 用户体验：VenueReaction（评价"场所"）+ taginteraction 评分 + venuefeedback
 └── 舞伴生态：dancer 模块（认可/标签评价"个人"）
```

**产品边界（刻意收窄，避免直播/打赏化）**：本模块**不使用**打赏、礼物、虚拟币、充值积分等概念，
统一使用"认可/支持/点赞/鼓励"。当前阶段无排行榜/认证体系/推荐算法——但数据模型已为这些
扩展预留（见「后续扩展」）。用户对舞伴的公开影响 = **认可 + 字典标签**，无金钱交易语义。

**隐私与真实性是第一约束（根因）**：舞伴是**真实个人**。禁止默认创建大量未授权人物主页——
所有新资料必须经人审核后才进入公开可见区（先认证、后展示，而非先展示、后治理）。
负面评价真实个人存在诽谤/骚扰风险且难以核验——负向体验走场所 feedback 等既有通道，
舞伴标签字典**全部为正向信号**。

**来源分层（2026-08-10 修订，根因：早期"一刀切禁止照片"的约束未区分上传者身份）**：
普通用户对舞伴唯一可写公开影响 = 认可 + 字典标签（禁传照片/禁编辑）；舞伴本人（createdBy 匹配）
与管理员可编辑资料、上传相册照片（照片逐张 PENDING 审核后公开，见「相册与照片审核」）。

### 数据模型（8 张表，全部继承 BaseEntity；2026-08-07 起 Flyway 迁移 + validate，禁 ddl-auto 演进）

| 表 | 职责 | 关键约束 |
|---|---|---|
| `qwt_dancers` | 舞伴实体（昵称/头像/简介/性别可选/常驻城市/状态/创建人） | status 默认 `PENDING`（@ColumnDefault），createdBy 必填 |
| `qwt_dancer_venues` | 舞伴↔舞厅关系（多对多） | UNIQUE(dancerId, venueId, relation)；HOME 常驻 / APPEARANCE 出现 |
| `qwt_dancer_recognitions` | 认可记录（每日一记模型） | UNIQUE(userId, dancerId, recognitionDate) |
| `qwt_dancer_recognition_tags` | 认可携带的标签 | UNIQUE(recognitionId, tag)；dancerId/userId 冗余便于聚合 |
| `qwt_dancer_photos` | 舞伴相册照片（V7 迁移，2026-08-10） | status 默认 `PENDING`（@ColumnDefault）；照片必须**逐张**审核，JSON 列无法表达逐张状态故独立成表 |
| `qwt_dancer_ad_views` | 创作者收益-广告支持记录（V25，2026-08-14） | UNIQUE(userId, dancerId, viewDate) 每日一次防刷 |
| `qwt_dancer_verification_logs` | 信息核验审计日志（V26，2026-08-14） | 每次状态变迁一行（from→to + operator + reason）；认证唯一历史事实源 |
| `qwt_dancer_favorites` | 舞伴收藏（V27，2026-08-14） | UNIQUE(userId, dancerId)；软删 + restore 复用；**无趋势输入故无取消时刻列/无频控** |

- **不强绑定单一舞厅**：一个舞伴可在多个舞厅出现、随时间变化（HOME 可多个、APPEARANCE 随时间增删）。
  `Dancer.city` 仅作列表按城市筛选的冗余字段，不构成绑定。
- **性别开放但可选**（业务需求决定）：`gender` 可空，null = 未声明，前端不展示。
- **相册照片**（`DancerPhoto`）：dancerId / url(500) / status(PENDING→PUBLIC/REJECTED) / createdBy /
  sortOrder（上传序；列表封面 = sortOrder 最小的一张 PUBLIC）。照片与资料可见性联动：
  舞伴非 NORMAL 时主页不可见 → 照片天然不公开（详情先校验舞伴可见性）。

### 本人编辑（2026-08-10 新增）

- `PUT /dancers/{id}`（本人 canManage 或管理员）：请求体复用 `UpsertDancerRequest`
  （原 CreateDancerRequest 更名，创建/编辑同一领域对象——与 venue 域 CreateVenueRequest
  复用于 create/update 的模式一致）；全量覆盖 nickname/avatarUrl/bio/gender/city，
  status/createdBy 不可由本接口变更。
- **状态机**：编辑不重置公开状态（NORMAL 保持）；**REJECTED 编辑后自动 → PENDING 重新送审**
  （兑现驳回通知"可修改资料后重新提交"的产品承诺，2026-08-10 补齐）。
- **HOME 关系 = 完整替换语义**：homeVenueId null = 清除全部 HOME；传新值软删旧 HOME 并幂等建新 HOME
  （编辑是"常驻舞厅变更"而非"追加"，防多次编辑累积多个"常去"）。
- **权限判定**：`DancerService.canManage()`（本人或 ADMIN），编辑/传照/删照均先过此校验。

### 相册与照片审核（2026-08-10 新增）

- 接口：`POST /dancers/{id}/photos`（本人/管理员，body {urls}，插入即 PENDING，单次 ≤9）、
  `DELETE /dancers/{id}/photos/{photoId}`（本人/管理员，软删）、
  `GET /admin/dancers/photos?status=`（仅 ADMIN，按上传时间倒序）、
  `PUT /admin/dancers/photos/{photoId}/status`（仅 ADMIN：PENDING→PUBLIC/REJECTED，reason 可选仅审计日志）。
- 详情 `photos` 服务端按身份过滤（非本人仅 PUBLIC；本人/管理员全量含待审态，编辑页回显状态徽标）；
  列表 `coverPhotoUrl`（`DancerPhotoRepository.findCoverUrlsByDancerIds` 批量 IN 查询，N+1 规避）。
- 照片驳回**不新增站内信**（编辑页可见状态，本人自行删除重传；低风险 + 可自查，避免消息域扩散）。
- `FileCategory` 新增 `DANCER_PHOTO` / `DANCER_AVATAR`（Supabase 直传凭证分类）。

### 认可模型（每日一记 → 2026-08-15 单票换票 + 可配置多选，复用 Reaction 的 anti-刷票设计）

与 VenueReaction「每日一记」模型完全同源（2026-08 确立，见「Reaction 快速反馈系统」）：

- 每次点击认可 = 插入一行 `recognitionDate = 今天`；取消 = **物理删除**当日记录 + **删除**其标签
- UNIQUE(userId, dancerId, recognitionDate)：同一用户每天只能认可同一舞伴一次，次日自动恢复
- **2026-08-15 单票换票**（认可交互从"标签选择器确认 0-3"改造为 Reaction 风格表情 chip
  单票）：新客户端每次认可携带**单个 tag**（= 今日唯一票）——未认可 → 参与（写入该标签）；
  今日同标签 → 取消；今日异标签 → **原子换票**（旧标签删除 + 新标签写入，`replacedFrom`
  返回旧票；认可记录本身不删，四窗口计数不变）。**每日一票由运营开关
  `dancer.recognition.daily.single`（V31 迁移，默认 true）控制**：关闭 = 多选模式
  （每枚表情独立 toggle：累加 / 移除；今日标签清空 → 删除认可记录）。旧客户端 `tags`
  列表（0-3）走兼容路径（未认可 → 参与写列表标签；已认可 → 取消）。
- **并发与删除（2026-08-15 根因修复 + 2026-08-20 幂等确定性化）**：单票路径以 `pg_advisory_xact_lock`
  （"recognition:"+user+":"+dancer+":"+date）串行化同键并发（对齐 VenueReactionService）；
  标签/认可删除一律 **@Modifying(clearAutomatically=true) 批量删除**（幂等、无实体管理
  状态——旧 Spring Data 派生删除 SELECT+em.remove 延迟实体删除在事务内 flush + 同键并发
  场景产生 StaleObjectStateException，见前端 09 文档「认可链路晚二轮」）；**认可/标签插入
  一律原子 upsert**（`DancerRecognitionRepository.upsertRecognition` / 
  `DancerRecognitionTagRepository.upsertRecognitionTag`，`INSERT ... ON CONFLICT DO NOTHING`，
  2026-08-20 替代「save + catch 23505 + clear + 同事务回查/继续循环」——PG 语句失败后
  事务中止（25P02），旧 catch 路径在并发下必然 HTTP 500，见 15-governance 错误表）。
  缓存失效 = 内联（响应统计新鲜）+ afterCommit/afterCompletion（回滚清污染）。
- 窗口统计（countAll/countToday/count7d/count30d）锚点 = `createdAt`（真实"此刻"，与 Reaction
  同口径）；`recognitionDate` 只承载"每日唯一"语义；「最近认可」动态（昨天 +3 前天 +5）按
  `recognitionDate` 自然日聚合——两套时间语义职责分离，同 VenueReaction 的 reactionDate/createdAt 约定
- 排序时间属性优先：公开列表按 **count7d 倒序**而非 countAll——"被认可的历史总量"不应让
  活跃度低的旧资料长期霸榜（舞厅/舞伴场景具有明显时间属性）

### 标签字典（DancerTagCode，后台维护）

标签来源 = 用户认可行为（2026-08-15 起**单票模型 = 每日一枚表情 chip**，每次认可恰写一个标签；
legacy 多标签记录保留不迁移）。与 ReactionCode 同模式：枚举是唯一事实源（emoji/label），
前端静态镜像 `constants/dancer-tags.ts`，修改须两端同步。**全部正向**（产品定位 + 真实个人
保护）。用户**不可自由创建**标签（防色情/攻击/广告/竞对刷评价）。**表情不复用（2026-08-15
明确）**：本字典 emoji 与 venue `ReactionCode` 枚举互斥（调整：DANCE 💃→🩰、GOOD_VIBE 🔥→🎉；
emoji 由枚举派生不下库，无迁移）。**2026-08-15 晚 窗口化**：标签聚合 DancerTagStat 增加
countToday/count7d/count30d（count = countAll 兼容列表 topTags），详情页认可 chip 默认
展示近7天、可切近30天/全部。

### 可见性规则（隐私边界）

| 状态 | 公众列表/详情 | 创建人本人 | 平台管理员 |
|---|---|---|---|
| NORMAL | ✅ | ✅ | ✅ |
| PENDING（默认，主动注册） | ❌ | ✅ | ✅ |
| REJECTED（审核驳回，2026-08-08 新增） | ❌ | ✅ | ✅ |
| HIDDEN（管理员下架） | ❌ | ✅ | ✅ |

`DancerService.canView()` 是读可见性唯一判定点（Controller 无权限逻辑）；`getDetail` / `getTags` /
`toggleRecognize` 均先过可见性校验。认可目标须对当前用户可见。写操作（编辑/传照/删照）
走 `canManage()`（本人或 ADMIN），与 canView 分层——普通用户对舞伴唯一可写公开影响 = 认可 + 标签。

### 接口

| 接口 | 鉴权 | 说明 |
|---|---|---|
| GET /dancers | 软鉴权 | 列表（仅 NORMAL；city 可选；按 count7d 倒序分页；登录含 myRecognizedToday + **myTags（2026-08-19 今日投票 code，列表 reaction 区域 chip 活跃态）**；topTags **全量下发**（2026-08-19 去截断）；含 coverPhotoUrl） |
| GET /dancers/cities | 软鉴权 | 常驻城市词表（聚合真实数据，2026-08-10 激活列表页城市筛选） |
| POST /dancers | 登录 | 舞伴主动注册 → PENDING；返回新建 ID |
| GET /dancers/{id} | 软鉴权 | 详情（可见性校验；登录含 isMine + myRecognizedToday + **myTags（2026-08-15 今日认可携带标签，chip 活跃态数据源）** + **favorite（2026-08-14 服务端权威收藏态）** + 四窗口统计 + 近7日每日认可 + **标签聚合（2026-08-15 四窗口：countAll/countToday/count7d/count30d）** + 常去/出现舞厅 + 相册 photos（按身份过滤）） |
| PUT /dancers/{id} | 本人/管理员 | 编辑资料（全量覆盖；REJECTED → 自动 PENDING 重审；HOME 关系完整替换；返回更新后详情） |
| GET /dancers/{id}/tags | 软鉴权 | 标签聚合（**2026-08-19 扩展为 `DancerTagsResponse{tags, myTags}`**：tags 四窗口用户无关走详情公共缓存；myTags = 当前用户今日认可携带标签（个人态恒实时，明细页行活跃态数据源，镜像门店 ReactionStatsResponse.reactedByMe 语义）；可见性校验） |
| POST /dancers/{id}/recognitions | 登录 | 认可 toggle（**2026-08-15 单票换票：body.tag 单字典标签**——未认可参与 / 同标签取消 / 异标签原子换票；开关 dancer.recognition.daily.single 关闭 = 多选（累加/移除，清空删认可）；旧客户端 body.tags 0-3 列表走兼容路径；返回 RecognizeResponse{recognized, replacedFrom, myTags, stats, tags(四窗口)}） |
| POST /dancers/{id}/photos | 本人/管理员 | 上传相册照片（body {urls}，插入即 PENDING，单次 ≤9） |
| DELETE /dancers/{id}/photos/{photoId} | 本人/管理员 | 删除照片（软删） |
| GET /users/me/dancer-recognitions | 登录 | 我的认可记录（同舞伴只取最近一条，按认可时间倒序） |
| GET /users/me/dancers | 登录 | 我的舞伴主页（创建人视角，含 PENDING/HIDDEN/REJECTED + status） |
| GET /admin/dancers | 管理员 | **审核列表**（含全部状态，status 可选筛选，按提交时间倒序；LEFT JOIN qwt_users 带注册人昵称/头像） |
| POST /admin/dancers | 管理员 | 后台创建（可信来源直通 NORMAL） |
| PUT /admin/dancers/{id}/status | 管理员 | 状态切换（PENDING→NORMAL 审核通过 / PENDING→REJECTED 驳回 / NORMAL↔HIDDEN 下架恢复；body.reason 可选操作说明，**状态变化即向创建人发送站内信**，2026-08-08 新增，见「站内信（消息中心）」） |
| GET /admin/dancers/photos | 管理员 | 相册照片审核列表（status 可选，按上传时间倒序，2026-08-10） |
| PUT /admin/dancers/photos/{id}/status | 管理员 | 照片审核（PENDING→PUBLIC/REJECTED；reason 可选仅审计日志，2026-08-10） |
| GET /dancers/favorites | 登录 | **我的收藏列表**（按收藏时间倒序，仅当前公开 NORMAL 舞伴；2026-08-14 舞伴收藏，见下） |
| POST /dancers/{id}/favorite | 登录 | **收藏舞伴**（幂等：已收藏忽略，软删行 restore 复用；仅 NORMAL 可收藏，纵深防御） |
| POST /dancers/{id}/favorite/remove | 登录 | **取消收藏**（幂等软删；行保留——HIDDEN 下架后恢复 NORMAL 自动重现） |
| GET /dancers/{id}/stats | 仅本人/管理员 | **舞伴统计**（六组近30天趋势：认可/收藏/礼物价值/分享/浏览+浏览来源；<b>舞伴是自然人，统计属精细行为数据，仅本人+管理员可查看</b>——未登录 401/非本人 1003；缓存 60s refresh-ahead，2026-08-14） |
| POST /dancers/{id}/view | 软鉴权 | **浏览埋点**（body {source} 可空；按天按来源去重，匿名 60s IP 频控，2026-08-14） |

### 舞伴收藏（2026-08-14：能力平权，根因驱动）

**根因**：门店收藏体系（qwt_favorites + FavoriteService）是**场所域特化**的——表结构
venue_id NOT NULL + UNIQUE(user_id, venue_id)、接口返回 VenueResponse。舞伴域从未获得
「收藏」能力（无表/无接口/详情页无星标），因此舞伴列表页结构上不可能有「收藏」Tab——
「收藏 = 门店专属」从 qwt_favorites 诞生起就成为**事实平台决策而非显式设计**，单点特化
演变为隐式全局决策。**系统性防复发**：用户级资源能力（收藏/浏览归因等）上线时必须审视
是否适用于全部内容域（venue + dancer），领域能力设计阶段即抽象「每域一张表 + 域内接口 +
详情页入口 + 列表页消费」四件套。

**设计决策**（与门店收藏的关键差异，见 V27 迁移注释）：
- **独立表 `qwt_dancer_favorites` 而非多态化 qwt_favorites**：门店收藏表与热度趋势 SQL
  （unfavorited_at 按日分组、收藏总数/近30天新增为热度公式输入）及 VenueResponse 深度耦合，
  多态化改造（target_type 列 + 唯一约束迁移 + 趋势 SQL 过滤）风险远大于收益。
- **无 unfavorited_at / 无 Caffeine 频控**：门店收藏的取消时刻列与 60s 阈值频控根因是
  "取消收藏每次真实写入会刷高取消趋势折线"；舞伴收藏不输入任何趋势图，无膨胀风险，
  只需幂等（唯一约束 + 软删恢复）。
- **收藏列表仅含当前公开舞伴**（d.status='NORMAL' AND d.deleted=false）：HIDDEN 是可见性
  开关（下架=退出公众视野），被收藏后下架的舞伴自动淡出收藏列表（行保留，恢复 NORMAL
  后自动重现）——收藏的是"当前可见的内容"。
- **详情收藏态 = 服务端权威字段**（DancerDetailResponse.favorite，登录实时判定）——替代
  venue 详情页用 URL fav 参数传递收藏态的 hack（分享深链等无参数入口会丢失状态）。
- **列表摘要构建复用**：listPublic 的行内 Object[] → DancerSummaryResponse 逻辑抽取为
  buildSummaries 私有方法，收藏列表（findFavoriteDancersByUserId 返回同构行）共用——DRY。
- **列表摘要下发累计浏览量 viewCount（2026-08-15）**：DancerSummaryResponse 追加 viewCount
  （全量历史 PV，含匿名——qwt_dancer_views 行数，与 DancerStatsService viewTrend 同源
  同口径的全量版）；buildSummaries 与 listMyDancers 均经 `DancerViewRepository#countByDancerIds`
  一次 IN + GROUP BY 批量填充（镜像门店 VenueViewRepository#countByVenueIds 模式，
  避免逐条 COUNT 的 N+1）；驱动前端舞伴列表卡片右下角「👁 浏览数」展示。

### 舞伴官方认证（2026-08-14：「信息已核验」标识，V26 迁移）

**治理定性**：认证 = 「身份与公开信息经平台人工核验属实」的**信息真实性背书**（裁决事实、
不裁决人品）；与 DancerStatus（先认证、后展示的隐私闸门）**显式分离**——审核通过 ≠ 认证，
绝无"审核通过自动带认证"；**不参与排序**（避免"官方钦定优先"二等化非认证舞伴）。

**数据模型（V26）**：dancer 表追加 `verification_status varchar(20) NOT NULL DEFAULT 'UNVERIFIED'`
（枚举类列禁 CHECK）+ `verified_at timestamp(6)` + `verified_by bigint`（后两列仅 VERIFIED 时
有值 = 当前快照）；`qwt_dancer_verification_logs` 审计表（**不继承 BaseEntity**——审计日志
只追加无软删，同 qwt_venue_status_logs 模式：dancer_id/operator_id/from_status/to_status/
reason/created_at + 索引 dancer_id）。

**状态机（可回退 = 第一约束）**：
- `UNVERIFIED → VERIFIED`：admin 授予（`PUT /admin/dancers/{id}/verification`，action=VERIFY）；
- `VERIFIED → PENDING_REVIEW`：**舞伴本人**编辑资料自动降级（护栏：防"认证挂在过期信息上"；
  管理员直改不触发，避免待办噪音）；
- `PENDING_REVIEW → VERIFIED`：admin 复核确认（action=VERIFY，恢复）；
- `PENDING_REVIEW/VERIFIED → UNVERIFIED`：admin 撤销（action=UNVERIFY，**reason 必填**——
  撤销必须留痕理由，随站内信通知舞伴，被撤销舞伴可查原因）；
- **曾认证被撤销后再次编辑** → 同样进入 PENDING_REVIEW（闭环"撤销 → 修改 → 复核恢复"，
  曾认证判定 = verificationLogRepository.existsByDancerIdAndToStatus(VERIFIED)）。

**接口与通知**：
- `PUT /admin/dancers/{id}/verification`（仅 ADMIN）：body `{action: VERIFY|UNVERIFY, reason?}`；
  目标状态相同幂等返回；每次实际变迁写审计日志（transitionVerification 唯一出口）。
- 站内信 `MessageType.DANCER_VERIFICATION`（同事务、幂等）：授予「信息核验通过」/ 撤销
  「信息核验标识已移除」（附原因 + 提示可修改资料后重新申请核验）。
- 响应下发：DancerSummaryResponse.verificationStatus / DancerDetailResponse.verificationStatus
  + verifiedAt / AdminDancerResponse.verificationStatus + verifiedAt（PENDING_REVIEW = 管理端待办）。

**测试**（DancerServiceTest 42 例含）：授予留痕+通知、撤销必填原因、幂等、本人编辑降级待复核、
管理员直改保持、从未认证编辑不触发、曾认证撤销后编辑重新待复核。

### 舞伴统计（2026-08-14 第一期：趋势时间序列，V29 浏览埋点）

**背景**：舞伴详情页参考门店热度页（venue-heat）做统计图（用户需求"舞伴详情页也需要
做一个统计图，第一期先做核心关注点"）。与门店的关键差异 = ① **舞伴域无热度公式**（列表
排序已有认可数/收礼信号，综合指数属后续扩展），第一期只做**趋势时间序列**六张图：
认可 / 收藏 / 礼物价值 / 分享 / 浏览 + 浏览来源；② **可见范围仅本人 + 管理员**（门店热度
页公开，舞伴是自然人——逐日时间序列/来源拆解属精细行为数据且与创作者收益计划敏感度
耦合，对齐 hide_contact 隐私默认最小化先例；详情页头部已公开的聚合信号"328 人认可"等
足以支撑他人评估，不需要开放逐日明细）。前端独立页 `dancer-stats`（对齐 venue-heat 的
charts 数组模板 + chart-brush + 图例开关 + 空图恒渲染 + y 轴全量锁定）。

**接口**：
- `GET /dancers/{id}/stats`（**仅本人 + 管理员**）：`DancerStatsResponse{recognitionTrend,
  favoriteTrend, pointsTrend, shareTrend, viewTrend, viewSourceTrend, unlockStats,
  statsAsOfDate}`——
  六组近30天每日时间序列（含今日，骨架 31 天，generate_series 补零；与门店 countDailyTrends
  同骨架，见下）+ **解锁信息分类聚合**（unlockStats，2026-08-21 追加，横向条形图，见后）。
  **鉴权**：Controller `requireAuth()`（未登录 → HTTP 401，前端触发登录）
  + `DancerService.checkStatsAccess`（已登录非本人/非管理员 → 业务错 1003「仅舞伴本人可查看
  统计数据」）。缓存 = 内嵌 Caffeine LoadingCache（60s refresh-ahead / 30min 过期，对齐
  VenueHeatService 模式）。
- `POST /dancers/{id}/view`（软鉴权，fire-and-forget）：浏览埋点，body `{source}` 可空，
  null/非法值兜底 OTHER（枚举类列无 CHECK，应用层防御；与门店 POST /venues/{id}/view 同模式）。

**浏览埋点（V29 迁移 `qwt_dancer_views`）**：独立表而非多态化 qwt_venue_views（对齐
舞伴收藏独立表的既有决策）；唯一索引 `(dancer_id, user_id, view_date, source)` 按天按来源
去重（匿名 userId=NULL 不去重 + 60s IP 频控兜底，对齐门店 V18/V21 教训：upsert 必须用
ON CONFLICT 列清单推断，勿用 ON CONSTRAINT）；source 复用门店 `ViewSource` 枚举（跨域
复用先例 = DancerShare 复用 venueshare.enums.ShareEventType）。

**趋势口径（对齐门店 2026-08-13 实时化）**：`DancerStatsService` 窗口上界 = 请求时刻 now
（含今日）；骨架 [今天-30, 今天] 31 天；DATE 列（认可 recognition_date / 浏览 view_date）
按 [sinceDate, 明天0点) 过滤、timestamptz 列（收藏/礼物/分享 created_at）按 [windowSince,
now) 过滤。聚合为单条 mega-query（`DancerStatsRepository.countDancerDailyTrends`，骨架
generate_series 显式 ::timestamp 重载 + ::date 收口——门店时区链缺陷教训）。

**各序列数据源与图型**：
- 认可趋势：`qwt_dancer_recognitions`（每日一记，按 recognition_date 分组；取消=物理删除）
  → 单折线；
- 收藏趋势：`qwt_dancer_favorites.created_at`（deleted=false；**无 unfavorited_at 取消线**——
  舞伴收藏软删无取消时刻，趋势为"新增"单序列，与门店双线不同）→ 单折线；
- 礼物价值趋势：`qwt_points_transactions`（target_type='DANCER' AND delta<0，按日
  SUM(-delta)，与门店 pointsTrend 同口径）→ 单折线；
- 分享趋势：`qwt_dancer_shares`（event_type='SHARE' 主动分享事件，不含 OPEN 回流）→ 单折线；
- 浏览趋势 / 浏览来源：`qwt_dancer_views`（V29；viewSource 的 other = 全量 − list − share
  − search 减法派生，前端只画 list/share/search 三线）→ 单折线 / 三折线。

**写路径缓存失效矩阵**（对齐门店「写路径缓存逐出」约定；refresh-ahead 仅兜底）：
浏览 recordView（真实插入后 afterCommit，VenueViewService 同款）/ 认可 toggleRecognize /
收藏 add·removeFavorite / 分享 recordShare / 礼物赠送 gift（DANCER 分支 afterCommit）——
全部调用 `DancerStatsService.invalidate(dancerId)`；幂等无写入分支（已收藏 return、匿名
频控命中）不失效。

**来源判定（前端单一判定点 = dancer-detail.onLoad）**：share_from 参数 → SHARE；from=list
参数（dancers.onTapDancer 列表页跳转携带）→ LIST；from=venue 参数（门店详情页「同城舞伴」
入口跳转携带，2026-08-21 新增）→ VENUE；其余（深链/收藏 Tab）→ OTHER。舞伴域**无搜索
入口与快照机制**——LIST 判定走 URL 参数而非门店式 storeVenueSnapshot。

**历史局限**：浏览埋点自 V29 上线日起积累（存量无浏览数据）；分享趋势自 V20（分享事件
日志）起积累。

### 城市值一致性契约（2026-08-21 根因修复 V38）

**根因**：门店 city 恒为标准行政区划名（picker region 输出「南通市」），而舞伴城市存在
历史手填形态「南通」（2026-08-14 城市选择器改造前存量，主城市与子表均受影响）。列表
筛选/同城匹配是字符串精确匹配（`d.city = :city OR 子表 EXISTS`），「南通市」≠「南通」
→ 同城筛选与门店详情页「同城舞伴」入口查 0 条（2026-08-21 线上实证）。

**三层修复（禁止只修表面）**：
1. **存量数据归一（V38 迁移）**：数据驱动、零硬编码城市名——存在门店城市
   `v.city = 舞伴城市 || '市'` 时，把舞伴主城市与子表城市归一为门店标准行政区划名
   （映射从 qwt_venues 推导）；幂等（已带「市」恒不命中）。
2. **规范化键 `qwt_city_key(city)`（V38 建函数，IMMUTABLE）**：仅去掉**尾部**「市」
   （`CASE WHEN RIGHT(city,1)='市' THEN LEFT(city,-1) ELSE city END`）——禁 REPLACE
   全替换（'津市市' 会被 REPLACE 全删成 '津'，必须尾部去一）。
3. **匹配/词表防御（DancerRepository）**：`findPublicPage` 城市条件升级「精确相等 OR
   qwt_city_key 相等」（主查询 + countQuery 同源）；`findPublicCities` 按 qwt_city_key
   GROUP BY 去重 + `MAX(city)` 优先保留带市形态（JPQL → nativeQuery，JPQL 无法引用
   PG 自定义函数）。未来绕过表单的写入（管理端 API/直写库）再次产生不带「市」值，
   匹配仍不失效、词表仍只出一个 chip。

**写路径锁死（前端）**：dancer-edit 城市唯一入口 = city-picker（数据源 /venues/cities
标准行政区划名），管理端无独立创建舞伴入口。**新增任何舞伴/门店城市写入通道前，必须
评估形态一致性**（同款 picker 或入库前经 qwt_city_key 校验），否则防御层只兜底不根治。

### 解锁信息统计（2026-08-21 追加：横向条形图，非时间序列）

**需求**：舞伴统计页加"用户解锁信息"统计图——积分解锁内容 = 照片/联系方式（`PointsGateTargetType`
可扩展），要求最合理图型不一定是折线图。

**图型选型**：**横向条形图**。解锁 = 分类（内容类型）× 累计语义（"哪类内容最受用户付费解锁"），
对比而非趋势；解锁低频离散事件，折线图大量为 0 视觉空洞；类别少标签横排可读、条长直观对比。
条宽 = 解锁人次归一化（行为热度主指标），每类并排展示人数（按用户去重覆盖）与当前门槛积分
（价值辅助）。

**数据契约**：`DancerStatsResponse.unlockStats = List<DancerUnlockStat>`（新增字段）——
`DancerUnlockStat{targetType, label, unlockCount, uniqueUsers, cost}`：
- `unlockCount` = 累计解锁人次（`qwt_points_unlocks` 行数；照片每张一个 target_id，同人多张
  照片计多次行为）；`uniqueUsers` = 累计解锁人数（COUNT(DISTINCT user_id)）；
- `cost` = 当前门槛积分（LEFT JOIN 未软删且 cost>0 的 gate，MAX(cost)；软删=清除门槛→历史
  解锁计数保留、cost 回落 0）；
- `label` 后端枚举映射（"照片"/"联系方式"，未知枚举回退枚举名）——**新增内容类型仅需加枚举
  值 + 映射，前端零改动**；
- 仅返回人次 > 0 的类别（无记录类别不上行，前端空态=「暂无用户解锁记录」）；人次降序。

**聚合**：`DancerStatsRepository.countDancerUnlockStats` 单条 SQL（一条 DB 往返，Supabase
远程库往返昂贵）：照片 target_id IN (SELECT id FROM qwt_dancer_photos WHERE dancer_id=:id)
归集到舞伴、联系方式 target_id=:id 直连；GROUP BY target_type。

**缓存失效**：解锁写路径入统计失效矩阵——`PointsService.unlock` **真实写入后**（幂等分支
无新数据不失效）afterCommit 经 `DancerDetailCacheService.invalidate(dancerId)` 级联失效
（`invalidateDancerStatsAfterCommit`：DANCER_CONTACT → targetId 即 dancerId 直连；
DANCER_PHOTO → `DancerPhotoRepository.findByIdAndDeletedFalse` 回查 dancerId）。

### 聚合缓存（DancerAggregateService）

与 VenueReactionAggregateService 同模式：内嵌 Caffeine LoadingCache（60s refresh-ahead /
30min 过期），只缓存**与用户无关**的舞伴级四窗口统计；个人"今日已认可"永远实时查询。
写路径（认可/取消）完成后必须 `invalidate(dancerId)`；并发唯一键冲突幂等为已认可
（每日一记模型的防连点约定，同 VenueReactionService.toggle）。

### 批量查询约定（N+1 规避）

- 列表页：单条分页 SQL 内联计数 → 一次 IN 查询（Top 标签）+ 一次 IN JOIN（常驻舞厅名）+
  一次 IN 查询（我的今日认可态）+ 一次 IN 查询（封面照片 url）——见 `DancerRepository.findPublicPage` /
  `fetchTopTags` / `fetchHomeVenueNames` / `fetchMyTodayIds` / `fetchCoverPhotoUrls`
- 列表计数 SQL 用 `COUNT(*) FILTER (WHERE created_at >= ...)` 单遍聚合三个窗口
- **Spring Data 派生查询参数类型必须与实体字段类型一致**（2026-08-10 线上修复固化）：
  枚举字段（如 `DancerVenue.relation`）的派生查询方法签名必须用枚举参数（`DancerVenueRelation`），
  禁止声明为 String——Spring Data 按字段类型校验绑定参数，String 会抛
  "argument [HOME] is not assignable to DancerVenueRelation"（该缺陷在 createDancer 因前端
  不传 homeVenueId 长期未被触发，updateDancer HOME 替换首次真实暴露；单测 mock 掩盖此类问题，
  新增 Repository 方法后必须真实启动验证）。

### 与既有模块的关系

| 既有能力 | 关系 |
|---|---|
| VenueReaction（评价场所） | **保留并存**，二者语义分层（场所 vs 个人），互不干扰 |
| taginteraction 评分 | 保留；评分对象是舞厅维度，与舞伴认可不交叉 |
| TextSanitizer | 昵称/简介/城市/性别入库前统一清洗（长度上限按字段语义传入） |
| VenueLookupService | 创建时校验 homeVenueId 存在性（缓存层 <1ms） |
| SchemaIntegrityChecker | 自动纳入 4 张新表（基于 JPA 元模型，零改动） |

### 后续扩展（数据模型已预留，未实现）

- **舞伴排行榜**：基于四窗口统计（今日/7天/30天）加权，可与列表排序演进为同一 SQL
- **舞伴认证**：DancerStatus + createdBy 已承载"认证人"语义，可扩展 verifiedBy/verifiedAt 字段
- **标签统计**：recognition_tags 已按 dancerId+tag 建索引，支持任意窗口聚合
- **推荐算法**：用户认可记录（userId+dancerId+日期）即"行为矩阵"，可直接喂协同过滤
- **积分体系**（条件性）：当前无积分系统；若引入，认可可作积分消耗项，但积分不可购买/提现/兑换

### 测试（DancerServiceTest）

Mockito 单测覆盖：创建（PENDING 默认/NORMAL 后台/空白昵称/常驻舞厅关联）、可见性规则
（NORMAL 公开 / PENDING/HIDDEN/REJECTED 仅创建人+管理员）、认可 toggle（插入+标签 /
取消+级联删标签 / 标签字典校验 / 超过 3 个拒绝 / 去重）、我的认可同舞伴去重、
管理端状态切换（通过/驳回/隐藏 → 站内信通知创建人、幂等无通知、REJECTED→HIDDEN 不通知、
审核列表映射含注册人占位回退）。

### 种子数据

`src/main/resources/db/seed-dancers-dev.sql`（依赖 seed-dev.sql 的用户/场所）：
3 位舞伴（NORMAL×2 + PENDING×1）+ HOME/APPEARANCE 关系 + 跨日认可 + 标签，
演示每日一记聚合、近7天排序、审核中资料不可见三种场景。

---


---
## 积分系统（points 模块，2026-08-10 V2）

### 领域边界（第一约束：资产 ≠ 态度表达）

积分是**资产模型**（账户 + 流水 ledger），与 Reaction（态度表达，每日一记）**完全分离**，只在展示层（独立区块，见前端 AGENTS.md）与排名层（热度公式输入项）交集。**禁止把积分做成 reaction_code 的特殊 code**——reaction 的领域不变量是"每日每 type 一次"，积分的领域不变量是"余额守恒（挣 = 赠 + 余）"，两者不可合并（根因分析见 `docs/积分系统-需求设计-V2-2026-08-10.md` 第三章）。

### 数据模型（V9 迁移）

- `qwt_points_accounts`：一用户一行（`user_id` 唯一），`balance` 读写快照 + `earned_total/spent_total` 冗余累计——高频读（详情/赠送校验）不 SUM；
- `qwt_points_transactions`：**只追加、不可变**的流水，`balance_after` 快照支持日终对账（`SUM(delta)` vs `balance`）；挣取（delta>0）必带 `source_type + source_id`，部分唯一索引 `(user_id, source_type, source_id) WHERE delta > 0 AND source_id IS NOT NULL` 兜底并发（SQLState 23505 幂等返回已有流水）；赠送（delta<0）必带 `target_type + target_id`（`PointsTargetType`：VENUE/DANCER，可扩展）+ **`gift_code`（V13 新增，2026-08-12 礼物化：记录"送了什么"，GiftCatalog 枚举名，仅赠送流水非空；存量 V2 积分赠送为 NULL）**；
- `qwt_daily_checkins`：`UNIQUE(user_id, checkin_date)` 保证"一天一次"业务语义，与流水唯一键（一次打卡只发一次分）职责分离；
- **实体不继承 BaseEntity**（与 `qwt_venue_status_logs` 同模式）：账务/锚点记录无软删、流水无 updatedAt——建表与实体列必须逐列对齐（`ddl-auto=validate` 启动期即校验，2026-08-10 曾因实体继承 BaseEntity 而表无 deleted 列启动失败，见当日日志）。

### 账务规则（防刷闭环）

- **余额守恒**：`balance = earned_total - spent_total`；赠送扣减用**原子条件更新**（`PointsAccountRepository.deductBalance`：`UPDATE ... SET balance = balance - :amt WHERE user_id = :id AND balance >= :amt`，affected=0 即余额不足抛 1011）——无锁防并发超扣；
- **只读事务禁写（2026-08-10 生产实证）**：`@Transactional(readOnly = true)` 的接口（概览/流水/统计）内**禁止任何可能写库的调用**——懒创建账户的写副作用只允许出现在可写事务（checkIn/gift/earn/adjust）；概览在无账户时返回零值而非创建（Postgres 对只读事务内 INSERT 报 "cannot execute INSERT in a read-only transaction"，且该契约错误仅真实请求首次触发时才暴露，见当日日志）；
- **挣取幂等**：打卡（checkin_id）/ 采纳（feedback_id）/ 管理调整（ADMIN_ADJUST）三源各自唯一键；`earn()` 撞唯一键时清 entityManager 幂等返回已有流水 `balanceAfter`（本事务回滚，无副作用）；
- **赠送防刷（V2，2026-08-12 礼物化后口径不变）**：上限按礼物价格折算积分价值——单次 ≤`app.points.gift.max-per-gift`（默认 10，单礼最贵 5 防御性保留）、每日总额 ≤`max-per-day`（默认 20）、单目标每日 ≤`max-per-target-day`（默认 5）、**自赠检测**（`venue.claimedBy` / `dancer.createdBy` == 本人 → 抛 1015）、目标可见性（venue 未软删 / dancer NORMAL）；赠送成功后 **afterCommit 失效 venueHeat 缓存**（与 reaction toggle 同模式，2026-08-08 根因：提交前失效存在竞态窗口）；
- **上报采纳奖励**：`VenueFeedbackService.adoptReport` 状态流转与发分**同一事务**（原子，杜绝"状态已采纳但积分未发"）；**reward 开关（2026-08-10）**——请求体 `reward` 缺省/true = 采纳并奖励（ADOPTED + 发分）；false = 采纳不奖励（ADOPTED_NO_REWARD，不发分）；匿名上报（userId null）采纳不发；**不设每日条数上限**（V2 决策：防刷由管理员采纳人工把关——奖励只发生在 ADOPTED，而 ADOPTED 是管理员逐条人工判定，垃圾上报不被采纳即拿不到分）；
- **配置唯一事实源**：`config/PointsProperties`（`app.points.*`：check-in-reward=2 / feedback-reward=5 / heat-weight=2 / gift 上限）。**禁止业务硬编码任何积分参数**。

### 礼物赠送（2026-08-12 礼物化，根因驱动）

**根因**：直接赠送积分 = 资产转移语义（delta<0 从 A 到 B），① 触碰"可流转准货币"合规红线（小程序虚拟货币流通监管）；② 收礼方只见数字、无情感载体，不符"表达支持"产品定位；③ 流水只记"送了多少"不记"送了什么"，跨页无法聚合展示礼物。系统性方案：积分退化为"获取礼物的代币"，赠送 = **购买礼物并一次性送出**（礼物不可回收、不可再流转——彻底切断资产转移链条）。

- **礼物字典唯一事实源 = `GiftCatalog` 枚举**（`code/emoji/displayName/price` 四元组，`points/enums`）：**价格放枚举而非配置**——价格需与前端镜像同步展示，放配置会造成"展示价 ≠ 实扣价"不一致（前端镜像 `constants/gifts.ts` 与后端枚举同步，改礼物走与 Reaction 字典同款三处同步流程：后端枚举 + 前端镜像 + `scripts/fetch-gift-assets.py` 补 png 资源）；`fromCode()` 解析未知 code 返回 empty 抛 1001（禁直接 `valueOf` 抛 500）；
- **载荷**：`POST /points/gift` body `{targetType, targetId, giftCode}`（替代 amount）；`GiftResponse` = `{balance, giftId, giftCode, giftName}`（giftName 后端权威下发，前端 toast 零映射）；
- **聚合（礼物墙）**：`receivedGifts()` 按 `gift_code` GROUP BY 件数降序（部分索引 `qwt_idx_pts_tx_target_gift_code`，V17 由 `(target_type, target_id)` 升级为含 `gift_code` 前置列），下发 `List<GiftCountResponse>`（code+count，前端查镜像字典渲染图片）——挂 `VenueHeatResponse.giftsReceived` / `DancerDetailResponse.giftsReceived`；与 `receivedTotal/receivedSince`（价值，热度输入项）**同源不同维**；
- **赠送者列表（2026-08-12 V17 性能优化）**：`GET /points/gifters`（礼物墙点击弹层/详情页）按 `(target_type, target_id, gift_code)` 精确过滤并按 `user_id` 聚合——查询**先按 user_id 聚合再 JOIN 用户表**（热路径索引扫描 + 聚合不触碰用户表，JOIN 只发生在聚合后小结果集上），走 `qwt_idx_pts_tx_target_gift_code` 部分索引（旧 `qwt_idx_pts_tx_target_gift` 是其严格前缀子集，V17 直接替换防写放大）；
- **循环依赖（VenueHeatService → PointsService）**：PointsService 依赖 VenueHeatService（赠送后失效缓存），VenueHeatService 回源需读礼物聚合——构造器注入 PointsService 用 **`@Lazy` 打破**（热度为缓存回源场景，延迟解析安全）；
- **存量数据**：V2 直接积分赠送流水 `gift_code` 为 NULL——聚合天然排除（展示口径=礼物时代数据），价值口径（SUM(delta)）不受影响（热度公式输入保持）。

### 错误码

1011 余额不足 / 1012 单次超限 / 1013 今日赠送超限 / 1014 单目标超限 / 1015 自赠拒绝 / 1016 今日已打卡（幂等提示，未用则删）。

### 排名接入与权重校准（V2 三阶段机制）

- **venue**：热度公式加 `近30天收到积分 × app.points.heat-weight`——三处镜像（`VenueHeatService` + `VenueRepository.HEAT_SCORE` + `findHotVenueIds`）经 `VenueHeatWeights` 常量拼接 + `:pointsWeight` 参数注入（见「场所热度 → 热度公式 → 权重收敛」）；`countHeatCounters` 加 `pointsreceivedtotal/pointsreceived30d` 标量；`countDailyTrends` 加第五序列 `points`（`DailyTrendRow.getPoints()`）；
- **dancer**：无热度公式，积分仅作**次级排序信号**（`findPublicPage`：近7天认可 DESC → 近30天收到积分 DESC → id DESC，tie-break 不影响认可主导口径）；是否升级为加权在 P2 按数据定；
- **权重校准 SOP（禁止拍脑袋）**：① 初始保守值 heat-weight=2 → ② 上线约 2 周采集基线（各门店积分贡献占比 = 积分得分/热度总分 的中位数/P90）→ ③ 目标区间 [5%, 15%]：超 15% 降权（减半）或收紧发放；低于 5% 适度升权或提高采纳奖励。只改 `app.points.heat-weight` 一处，公式文案后端下发自动同步。

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/points/check-in` | 登录 | 每日打卡（幂等：今日已打卡返回 checkedIn=false；2026-08-20 起由前端登录自动触发——启动/登录成功后调用，无手动入口，本接口幂等语义与并发防护不变） |
| GET | `/points/me` | 登录 | 概览（余额/今日挣赠/打卡态/规则文案 rules——合规文案后端唯一事实源） |
| GET | `/points/transactions` | 登录 | 流水分页（type=ALL/EARN/GIFT） |
| POST | `/points/gift` | 登录 | 赠送礼物（body `{"targetType","targetId","giftCode"}`，2026-08-12 礼物化；价格 GiftCatalog 权威，上限/自赠校验见上） |
| POST | `/admin/points/adjust` | ADMIN | 人工调整（delta 可正可负，reason 必填，审计） |
| POST | `/admin/reports/{id}/adopt` | ADMIN | 采纳上报（body `{"note", "reward"}`：reward 缺省/true → ADOPTED + 同事务发分；false → ADOPTED_NO_REWARD 不发分，见「统一用户上报」状态机） |

### 合规红线（微信小程序审核）

无充值入口（积分仅免费获得）/ 不可提现·转让·兑换 / 无邀请分享得积分（诱导分享违规）/ 无随机奖励（博彩）/ 文案禁「打赏·赞赏·小费」，统一「支持·感谢」。规则文案由后端 `PointsService.RULES_TEXT` 下发，前端只渲染。

### 积分解锁（2026-08-14 公共模块：照片/联系方式"自由设置是否需要积分"）

**需求**：舞伴上传的每张照片、联系方式都可"自由设置是否需要积分、支付多少"；
且是**公共模块**——任何内容点未来都可声明门槛。统一模型 = 门槛
（`qwt_points_gates`：target_type + target_id + cost）+ 解锁记录（`qwt_points_unlocks`）。

- **合规决策（核心）**：解锁消耗积分**单向燃烧（burn）**，不进舞伴账户——若转移
  即成"可流转准货币"红线（与礼物化根因同源）；舞伴回报 = "有人愿为 TA 的内容
  花积分"的社会证明（unlocks 表天然支持解锁人数统计，本期不展示，预留给后续）。
  <b>解锁流水 source_type=UNLOCK、delta&lt;0，不挂 target_type/target_id</b>
  （`PointsTargetType` 是赠送/收积分聚合维度 VENUE/DANCER，语义不混杂），
  目标记入 remark（"DANCER_PHOTO:123"），权威记录 = unlocks 表（含 transaction_id 关联）。
- **数据模型（V23/V24/V25 迁移）**：
  - `qwt_points_gates`：target_type + target_id + cost + created_by/updated_by；
    **继承 BaseEntity**（可改可删的业务配置）；**cost&gt;0 才落行**（"存在行"即"有门槛"，
    清除 = 软删）；部分唯一索引 `WHERE deleted=false`（软删后重建兼容）。
  - `qwt_points_unlocks`：user_id + target_type + target_id + transaction_id；
    **不继承 BaseEntity**（锚点记录只写一次）；`UNIQUE(user_id,target_type,target_id)`
    **一人一目标只扣一次费**——并发撞 23505 时本事务整体回滚（含扣费与流水），
    幂等返回已解锁，无重复扣费（同 earn() 幂等模式）。
  - `qwt_dancers.contact`（V24，varchar 100 可空）：联系方式随 UpsertDancerRequest
    走既有 PENDING 审核（管理员可见），明文存储（与昵称/简介同级别，MVP 不加密）。
  - `qwt_dancers.hide_contact`（V28，boolean NOT NULL DEFAULT true）：<b>联系方式遮挡
    开关</b>（2026-08-14，默认遮挡）——与积分门槛<b>正交</b>：hide_contact 决定是否
    打码展示；门槛 cost 决定打码后如何解锁（0=免费点击直显，>0=积分解锁后显示）。
    不遮挡（false）时后端<b>忽略门槛恒下发真实值</b>（残留门槛值保留，重新遮挡后
    恢复生效）；遮挡 + 免费照常下发（内容免费，前端遮罩承载"点击直显"交互）；
    遮挡 + 有门槛 + 未解锁才置 null 防绕过。UpsertDancerRequest 增 hideContact
    （null = 默认遮挡 true，旧客户端向后兼容）。
  - `qwt_dancer_photos.blur_url`（V25，可空）：<b>模糊占位图</b>（需求 4：
    收费照片详情页"模糊可见轮廓"遮罩，<b>薄码语义</b>——轻模糊、轮廓隐约可见，
    非厚码/马赛克）——前端上传原图时 canvas 离屏降采样生成（最长边 96px +
    JPEG 0.5 + 轻 blur(1px)，不可还原为原图）随 blurUrls 一起上传；缺失
    （旧数据/生成失败）时详情页回退<b>虚焦占位</b>（前端柔和光斑 + 锁角标——
    无原图可模糊，仅表达"内容被遮挡"；2026-08-14 根因修复）。
    <b>blurUrls 完整性契约（2026-08-14 前端根因修复）</b>：image-upload 的 change
    事件在<b>主图 + 模糊图全部 settle 后只发一次</b>，blurUrls 与 urls 一一对应且为
    最终值（blur 失败项空串）——服务端按 index 消费即可，blur_url 恒有值（新数据）。
- **接口**：
  - `POST /points/gates` body `{targetType, targetId, cost}`：cost&gt;0 设置/更新
    （≤ `app.points.gate.max-cost`，PointsProperties 配置化禁硬编码）；cost=0 清除
    （软删）。权限 = 目标属主（舞伴本人 createdBy）或管理员（与 dancer canManage 语义一致）。
  - `POST /points/unlock` body `{targetType, targetId}`：登录解锁；校验链 = 门槛存在
    （cost&gt;0 未软删）→ 目标可见（照片 PUBLIC + 舞伴 NORMAL / 联系方式 → 舞伴 NORMAL）
    → 幂等（已解锁直接返回内容）→ 余额（原子条件扣减 deductBalance，1011）→
    写 UNLOCK 流水 → 写 unlock 记录。响应 `UnlockResponse{unlocked, balance, targetType, targetId, content}`
    （content = 照片原图 URL / 联系方式文本，仅解锁成功返回）。
  - `POST /dancers/{id}/photos` body 扩展 `{urls, blurUrls}`（2026-08-14）：
    blurUrls 与 urls 按 index 一一对应（可缺省），落库 DancerPhoto.blurUrl。
- **详情组装（DancerService）**：`DancerDetailResponse` 增 contact/contactCost/
  contactUnlocked/hideContact；`DancerPhotoResponse` 增 cost/unlocked/blurUrl；**未解锁照片 url 置
  null**（不下发原图防绕过，blurUrl 恒下发作遮罩）；列表封面 SQL
  （findCoverUrlsByDancerIds）LEFT JOIN gates **跳过有门槛照片**（列表页不泄露需解锁
  原图；全设门槛的舞伴无封面 = 诚实呈现）。批量组装走 PointsService.gateCosts /
  unlockedIds（一次 IN 查询，N+1 规避）。
- **错误码**：复用 1001（目标不存在/无门槛/照片未公开）/ 1003（无权限）/ 1011（余额不足）。
- **测试**：PointsServiceTest / DancerServiceTest 适配新构造参数（UpsertDancerRequest
  + contact/earningsEnabled/hideContact、PointsService + gate/unlock/photo repo、PointsProperties + gate、
  DancerService + adViewRepo/DancerAdProperties、addPhotos + blurUrls；DancerServiceTest
  增 hideContact 三态用例：默认遮挡+付费→不下发 / 遮挡+免费→下发（前端遮罩）/
  不遮挡→恒下发 / 本人恒可见）。

---

### 创作者收益计划（2026-08-14：激励视频广告支持 TA，收益线下转账结算）

**需求**：舞伴开启后，详情页接入微信小程序激励视频广告——其他用户主动观看广告支持
TA（完整观看计入收益），收益由平台**线下转账**结算（MVP 无线上结算；收益记录 = 结算依据）。

- **合规与防刷**：
  - <b>广告必须用户主动触发</b>（前端点击入口才播放，微信广告规范禁自动弹出）；
  - <b>本人不可观看自己的广告</b>（自刷收益红线，同自赠检测 1015 语义）→ 1001；
  - 同一用户同舞伴<b>每天至多一次</b>（`qwt_dancer_ad_views` UNIQUE(user,dancer,view_date)，
    23505 幂等返回 recorded=false 不重复计收益）；匿名不可（requireAuth）。
- **数据模型（V25 迁移）**：
  - `qwt_dancers.earnings_enabled`（boolean @ColumnDefault false）：收益计划开关，
    随 UpsertDancerRequest 走审核（null = 关闭）；
  - `qwt_dancer_ad_views`：dancer_id + user_id + view_date + created_at，
    **不继承 BaseEntity**（收益锚点只写一次）；`UNIQUE(user_id,dancer_id,view_date)` 防刷；
    countByDancerId = 累计支持次数（详情页"已获得 N 次支持" + 线下结算依据）。
- **接口**：
  - `POST /dancers/{id}/ad-views`（登录；目标须开启收益计划且非本人；每日幂等）：
    响应 `AdViewResponse{recorded, viewsTotal}`——recorded=true 计入收益；
  - 详情 `GET /dancers/{id}` 增 `earningsEnabled`/`earningsAdUnitId`（配置下发，
    前端零硬编码）/`earningsViews`（累计支持次数）。
- **配置**：`app.dancer-ad.ad-unit-id`（`DancerAdProperties`，@ConfigurationPropertiesScan
  自动注册；环境变量 DANCER_AD_UNIT_ID 注入；留空 = 未配置广告位，前端不渲染广告入口）。
  需小程序开通**流量主**后填真实激励视频广告位 ID。
- **管理端结算**：MVP 不做线上结算（线下转账 = 运营人工核对 qwt_dancer_ad_views），
  数据模型已支持；后续可加管理端收益列表（按舞伴聚合观看次数）。

---


### 2026-08-19 舞伴域根因修复批量记录（性能/并发/安全/约定对齐）

> 本轮为舞伴系统全面根因审计后的修复沉淀（前端 + 后端联动），修复项与根因如下；
> 治理级约束已同步至 `15-governance.md`「AI 代理常见错误表」。

**1. 详情接口 ~15 次顺序 DB 往返（性能根因）→ DancerDetailCacheService 聚合缓存**
- 根因：`getDetail` 对每个请求顺序执行统计/标签/场所/城市/收礼/收到积分×2/广告计数/
  门槛/解锁等 ~15 次跨洲往返（单次 300~500ms），详情接口 3~7s 慢；绝大多数查询与
  当前请求用户无关，却在每请求重复执行。
- 修复：新增 `DancerDetailCacheService`——用户无关公共部分（认可统计/标签/场所/城市/
  收礼/收到积分/联系方式门槛/广告计数）整体打包为 Caffeine LoadingCache（60s
  refresh-ahead + 30min 过期 + 500 条），60s 窗口内详情往返 ~15 次 → ~6 次。
  用户相关态（isMine/今日认可/收藏/解锁/相册过滤）恒实时查询、严禁进缓存
  （对齐「个人状态禁入聚合缓存」治理约束）。
- **失效纪律**：`invalidate(dancerId)` 是唯一失效入口，必须**级联失效内层**
  DancerAggregateService 与 DancerStatsService（详情重算会回读它们的值，只清外层
  会让内层 60s 陈旧值泄漏）。写路径失效矩阵：认可 toggle / 收藏 add·remove /
  浏览记录 / 礼物赠送(DANCER) / 分享 SHARE / 资料编辑（城市子表）/ DANCER_CONTACT
  门槛设置；照片增删审（不在缓存）与状态流转（主表字段）无需失效。
- 积分读侧直连仓库（PointsTransactionRepository/PointsGateRepository）而非
  PointsService——避免「PointsService → 缓存服务 → PointsService」构造循环依赖；
  与写路径通过 invalidate 解耦。

**2. 23505 异常控制流不可靠（并发根因）→ 原子 upsert / advisory lock**
- 根因：多处「查 → 插 + catch 23505 吞异常」把幂等语义建立在 JPA 不可靠行为上——
  Hibernate flush 失败后持久化上下文状态未定义、事务可能已被标记 rollback-only：
  注释声称的「幂等 200」实际会变 HTTP 500；unlock 场景「扣费已执行、解锁未落库」的
  事务边界完全依赖 provider 行为。
- 修复（确定性写法，禁再 catch+clear 表达幂等）：
  - `recordAdView` → `INSERT ... ON CONFLICT (user_id,dancer_id,view_date) DO NOTHING`
    返回 affected 行数（恒 1 次往返零异常）；
  - 舞伴 `addFavorite` → `INSERT ... ON CONFLICT (user_id,dancer_id) DO UPDATE SET
    deleted=false`（插入/restore/幂等三分支原子覆盖，created_at 不变）；
  - 门店 `FavoriteService.addFavorite` 同根因同修复（跨域一致性）；
  - `unlock` / `checkIn` → `pg_advisory_xact_lock` 按 user 粒度串行化 check-then-act
    （对齐认可域 lockDailyTicket 先例；一人一天/一目标无真实并发价值，串行正确）；
  - 唯一索引/约束保留为纵深防御，但不再作为业务路径依赖。

**3. HTTP 方法约定违反（治理根因）→ 全部迁移 POST**
- 根因：早期 venue 域确立「只允许 GET 和 POST」（12-api-conventions.md），后续
  dancer 域与 venuestatuswatcher 域直接用了 RESTful PUT/DELETE，未经约定评审，
  漂移静默积累（同域内 favorite 用 POST 而 update/删除用 PUT/DELETE，风格分裂）。
- 修复：`PUT /dancers/{id}` → `POST /dancers/{id}/update`；`DELETE
  /dancers/{id}/photos/{photoId}` → `POST .../remove`；admin 三接口 PUT → POST；
  `PUT/DELETE /venues/{id}/status-watch` → `POST` + `POST /cancel`。前端
  `services/dancer.ts` / `services/statusWatch.ts` 同步迁移。

**4. 其他修复**
- `replaceHomeVenue` 早退 bug：循环内 return 跳过「其余旧 HOME 兜底清除」（注释
  自相矛盾，数据异常时无法自愈）→ 改为「跳过目标行、删除其余行」完整替换；
- `addPhotos`：单次数量上限 9（与前端 maxCount 对齐，后端独立校验防绕过）；
  **blurUrl 落库挂 ImageContentValidator**（08-12 安全约定：新增图片 URL 落库字段
  必须挂载内容校验——此前仅校验 http 前缀，可塞入任意外部 URL 绕过存储桶防线）；
- `maxSortOrder`：全量加载取 max → `COALESCE(MAX(sort_order),0)` 单值聚合；
- 分页参数归一：page<0 / size<1 会令 PageRequest 抛 IllegalArgumentException →
  HTTP 500，统一 `sanePage`（Math.max 0 / Math.max 1 / cap 50）；
- `DancerShareService.recordShare`：缓存失效从「事务内内联」改为 afterCommit——
  违反「失效时机约束」（提交前失效存在回源竞态窗口），对齐 DancerViewService/PointsService；
- `DancerStatsRepository` mega-query：qwt_dancer_views 四来源子查询各扫一次 → 单
  子查询 + FILTER 条件聚合（同一窗口 1 次扫描，省 3/4 IO）。

---
