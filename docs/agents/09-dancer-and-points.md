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

### 数据模型（5 张表，全部继承 BaseEntity；2026-08-07 起 Flyway 迁移 + validate，禁 ddl-auto 演进）

| 表 | 职责 | 关键约束 |
|---|---|---|
| `qwt_dancers` | 舞伴实体（昵称/头像/简介/性别可选/常驻城市/状态/创建人） | status 默认 `PENDING`（@ColumnDefault），createdBy 必填 |
| `qwt_dancer_venues` | 舞伴↔舞厅关系（多对多） | UNIQUE(dancerId, venueId, relation)；HOME 常驻 / APPEARANCE 出现 |
| `qwt_dancer_recognitions` | 认可记录（每日一记模型） | UNIQUE(userId, dancerId, recognitionDate) |
| `qwt_dancer_recognition_tags` | 认可携带的标签 | UNIQUE(recognitionId, tag)；dancerId/userId 冗余便于聚合 |
| `qwt_dancer_photos` | 舞伴相册照片（V7 迁移，2026-08-10） | status 默认 `PENDING`（@ColumnDefault）；照片必须**逐张**审核，JSON 列无法表达逐张状态故独立成表 |

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

### 认可模型（每日一记，复用 Reaction 的 anti-刷票设计）

与 VenueReaction「每日一记」模型完全同源（2026-08 确立，见「Reaction 快速反馈系统」）：

- 每次点击认可 = 插入一行 `recognitionDate = 今天`；取消 = **物理删除**当日记录 + **级联删除**其标签
- UNIQUE(userId, dancerId, recognitionDate)：同一用户每天只能认可同一舞伴一次，次日自动恢复
- 窗口统计（countAll/countToday/count7d/count30d）锚点 = `createdAt`（真实"此刻"，与 Reaction
  同口径）；`recognitionDate` 只承载"每日唯一"语义；「最近认可」动态（昨天 +3 前天 +5）按
  `recognitionDate` 自然日聚合——两套时间语义职责分离，同 VenueReaction 的 reactionDate/createdAt 约定
- 排序时间属性优先：公开列表按 **count7d 倒序**而非 countAll——"被认可的历史总量"不应让
  活跃度低的旧资料长期霸榜（舞厅/舞伴场景具有明显时间属性）

### 标签字典（DancerTagCode，后台维护）

标签来源 = 用户认可行为（认可时从字典勾选，每次最多 3 个）。与 ReactionCode 同模式：
枚举是唯一事实源（emoji/label），前端静态镜像 `constants/dancer-tags.ts`，修改须两端同步。
**全部正向**（产品定位 + 真实个人保护）。用户**不可自由创建**标签（防色情/攻击/广告/竞对刷评价）。

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
| GET /dancers | 软鉴权 | 列表（仅 NORMAL；city 可选；按 count7d 倒序分页；登录含 myRecognizedToday；含 coverPhotoUrl） |
| GET /dancers/cities | 软鉴权 | 常驻城市词表（聚合真实数据，2026-08-10 激活列表页城市筛选） |
| POST /dancers | 登录 | 舞伴主动注册 → PENDING；返回新建 ID |
| GET /dancers/{id} | 软鉴权 | 详情（可见性校验；登录含 isMine + myRecognizedToday + 四窗口统计 + 近7日每日认可 + 标签云 + 常去/出现舞厅 + 相册 photos（按身份过滤）） |
| PUT /dancers/{id} | 本人/管理员 | 编辑资料（全量覆盖；REJECTED → 自动 PENDING 重审；HOME 关系完整替换；返回更新后详情） |
| GET /dancers/{id}/tags | 软鉴权 | 标签聚合（可见性校验） |
| POST /dancers/{id}/recognitions | 登录 | 认可 toggle（body.tags 可选 0-3 字典标签；返回 RecognizeResponse{recognized, stats}） |
| POST /dancers/{id}/photos | 本人/管理员 | 上传相册照片（body {urls}，插入即 PENDING，单次 ≤9） |
| DELETE /dancers/{id}/photos/{photoId} | 本人/管理员 | 删除照片（软删） |
| GET /users/me/dancer-recognitions | 登录 | 我的认可记录（同舞伴只取最近一条，按认可时间倒序） |
| GET /users/me/dancers | 登录 | 我的舞伴主页（创建人视角，含 PENDING/HIDDEN/REJECTED + status） |
| GET /admin/dancers | 管理员 | **审核列表**（含全部状态，status 可选筛选，按提交时间倒序；LEFT JOIN qwt_users 带注册人昵称/头像） |
| POST /admin/dancers | 管理员 | 后台创建（可信来源直通 NORMAL） |
| PUT /admin/dancers/{id}/status | 管理员 | 状态切换（PENDING→NORMAL 审核通过 / PENDING→REJECTED 驳回 / NORMAL↔HIDDEN 下架恢复；body.reason 可选操作说明，**状态变化即向创建人发送站内信**，2026-08-08 新增，见「站内信（消息中心）」） |
| GET /admin/dancers/photos | 管理员 | 相册照片审核列表（status 可选，按上传时间倒序，2026-08-10） |
| PUT /admin/dancers/photos/{id}/status | 管理员 | 照片审核（PENDING→PUBLIC/REJECTED；reason 可选仅审计日志，2026-08-10） |

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
| POST | `/points/check-in` | 登录 | 每日打卡（幂等：今日已打卡返回 checkedIn=false） |
| GET | `/points/me` | 登录 | 概览（余额/今日挣赠/打卡态/规则文案 rules——合规文案后端唯一事实源） |
| GET | `/points/transactions` | 登录 | 流水分页（type=ALL/EARN/GIFT） |
| POST | `/points/gift` | 登录 | 赠送礼物（body `{"targetType","targetId","giftCode"}`，2026-08-12 礼物化；价格 GiftCatalog 权威，上限/自赠校验见上） |
| POST | `/admin/points/adjust` | ADMIN | 人工调整（delta 可正可负，reason 必填，审计） |
| POST | `/admin/reports/{id}/adopt` | ADMIN | 采纳上报（body `{"note", "reward"}`：reward 缺省/true → ADOPTED + 同事务发分；false → ADOPTED_NO_REWARD 不发分，见「统一用户上报」状态机） |

### 合规红线（微信小程序审核）

无充值入口（积分仅免费获得）/ 不可提现·转让·兑换 / 无邀请分享得积分（诱导分享违规）/ 无随机奖励（博彩）/ 文案禁「打赏·赞赏·小费」，统一「支持·感谢」。规则文案由后端 `PointsService.RULES_TEXT` 下发，前端只渲染。

---

