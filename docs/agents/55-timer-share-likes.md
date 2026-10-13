# 55 · 计时「给同行者点赞」（timershare likes，2026-10-12）

> 后端权威文档。**前端权威 = quwuting 仓 `docs/agents/61-timer-share-likes.md`**（入口 / 详情卡 / 乐观更新 / 真机清单）；
> 会话本身（快照 / 二维码 / 结算同步）见本目录 `54-timer-share.md`。
> 迁移 = `db/migration-mysql/V48__timer_share_likes.sql`（头注含完整理由与部署顺序）。
> 代码落点 = `timershare/`：`TimerShareLikeController`（独立于会话控制器）/ `TimerShareLikeService`（事务化编排）/
> `TimerShareLikeRules`（准入与窗口，纯函数）/ `TimerShareLikeRepository` / `TimerShareLike` / `TimerShareRole` / `TimerShareLikeOutcome`；
> 口径常量唯一出处 `TimerSharePolicy`（`LIKE_*`）。
> 用户拍板（2026-10-12）：「按推荐方式完成一版，不用过分考虑审核风险，有问题再下线」。
> 这是原需求④「给舞伴点赞送礼」的落地：**只做一个常规点赞**；礼物不做（积分是不可流转的准货币，礼物墙正是 2026-09-01
> 审核驳回的「交友闭环」形态）；表情只在数据上留扩展口（`code` 列），不开。
>
> ⚠️ **维护警告**：本文件是单一主题的详细设计；新增细节写在这里，**禁止写回 AGENTS.md**（只更新索引表一行摘要）；
> 超过 ~300 行时拆子文档。

## 一、模型

- 表 `qwt_timer_share_likes`（V48）：一行 =「某张会话里 A 赞了 B」。`UNIQUE(share_id, liker_id, liked_user_id)`；
  取消 = 软删、再赞 = 原行复活（同热度点赞 `qwt_venue_crowd_report_likes` 的 toggle）。`code` 现在恒 `LIKE`——唯一键
  是一对一，所以将来放开表情是「同一行换码」，不是加表也不是改键（请求协议里**暂无**该字段，放开时再加）。
- **准入**（`TimerShareLikeRules`，纯函数，读状态与写校验共用同一个 `pairsOf` 入口——不会出现「界面给了按钮、点下去说
  不是成员」）：主持方 ↔ 加入者互赞；**加入者之间不开放**；非成员 = 不存在（不泄露 token 是否存在）。
- **目标不用 userId**：请求用「角色 + 序号」指明（`HOST` / `JOINER` + `seq`，同 `status.joins[].seq`）。本域一贯不向对方
  下发 userId（54 号 §十三「资料互看的边界」），点赞不例外；`liked_user_id` 只在服务端内部使用，任何响应都不含它
  （`TimerShareWireFormatTest#likeResponsesCarryNoUserIdentifiersAndAlwaysWriteTheBooleans` 钉住）。
- **窗口**（`TimerSharePolicy.LIKE_WINDOW_MS` = 24h）：该对（主持方 × 某位加入者）**最近一次活动**之后 24 小时，赞与取消
  都在窗口内、窗口外**同时冻结**（不存在「赞得了但取消不了」）。最近一次活动 = max(会话最近创建 / 刷新、该加入者加入、
  主持方结算、该加入者结算)——所以「还在计时」「刚结算」都落在窗口里，不需要另判会话状态；对方 25 小时后才结算会重新打开窗口。
- 🚫 **无经济价值、永不进算法**：不给积分、不进榜、不进任何主页、不改任何排序或统计口径；表上**没有**按被赞者聚合的计数列，
  也没有以 `liked_user_id` 打头的索引——读路径只有两个，都被唯一索引的 `share_id` 前缀覆盖。
  ⚠️ **2026-10-13 修订**：原写"不累计到人"已按用户明确要求放开——点头像可看**该用户**的点赞记录（§八）。放开的只是"一位用户的一份
  时间线"（在内存里按对方分组），**仍然没有**全站聚合量 / 榜单 / 计数列 / 新索引。
  管理端行为目录里它是 EXTENDED 档（`TIMER_SHARE_LIKE`，轨迹可见、**不计活跃**；与 35 号「用户行为目录 2026-10-09 更新」的 EXTENDED 档同口径——「扩展功能是否并入活跃」仍待产品裁决，裁决后只改 `Nature` 一行）。

## 二、接口（全仓只有 GET / POST；`TimerShareLikeController`，独立于会话生命周期的控制器）

| 接口 | 说明 |
|---|---|
| `GET /timer-shares/{token}/likes` | 调用者面前的每一位：`{enabled, items:[{relation, seq, likedByMe, likesMe, likeable}], serverNowMs}`。非成员 / token 非法 / 速率超限 → 空 `items`（装饰性读取，不报错）；开关关 → `enabled=false` 且 `items` 空 |
| `POST /timer-shares/{token}/likes` | body `{relation, seq?, liked}`，**幂等设为目标状态**。响应 `{outcome, liked, serverNowMs}` |

`outcome`（预期的业务状态以**数据**回传，同 join；客户端请求层丢弃业务错误码，无法据 code 分流）：
`OK` / `DISABLED`（开关关）/ `NOT_MEMBER`（含 token 非法 / 不存在）/ `INVALID_TARGET`（加入者想赞加入者、赞自己、
序号越界、协议值非法、缺 `liked`——**没有「默认赞」**）/ `WINDOW_CLOSED` / `TOO_FREQUENT`。`liked` 仅 `OK` 时有意义。

**独立资源，而不是塞进 `status` / `peer`（偏离原推荐「在 PeerFact 里加一项」，如实登记）**：那两个接口被前端对账轮询按
「成本边界」驱动（`planPeerWatch`，56 号页面进入成本契约的约束），且**结算之后就停读**——而点赞恰恰大量发生在结算之后（结算卡
是主入口）。塞进去只有两个结果：读不到，或放宽轮询边界。独立资源 = 详情卡打开时按需读一次，空闲成本为零，也不动已稳定的
`status` / `peer` 契约。代价：对方「在线时」不会被 `status` / `peer` 顺带推送——这条由站内信承担（见下）。⚠️ **2026-10-13 补充**：前端对账轮询现在**另外**顺带读本接口（前端 61 号 §十：只在「我这位还在计时、码上有过人」时，≥20s 一次 / 慢档 30s），服务端零改动；`status` / `peer` 的契约仍然没有点赞字段。

## 三、被赞通知（站内信 `TIMER_SHARE_LIKED`）

仅该对**首次赞**触发（`INSERT … ON DUPLICATE KEY UPDATE` 的受影响行数 == 1；取消后再赞 = 2，不重发）。同一场里的未读
被赞通知**合并成一条**（`MessageService#createOrMergeUnread`，合并键 = 收件人 × 会话 id，`mergeSince` = 会话创建时刻，
正文带累计数）；已读后再被赞 ⇒ 新建；**不点名赞者**。`relatedType=TIMER_SHARE` 仅作合并键，**不深链**（计时页是 tab 页，
消息中心 `navigateTo` 到不了，且这条消息是告知而非待办）。文案只说事实：「一同计时的舞伴给你点了个赞 / 共给你点了 N 个赞」，
不出现评价 / 好评 / 打赏 / 送礼 / 心动（`TimerShareLikeServiceTest#notificationCopyAvoidsEvaluativeAndGiftWording`）。

**真实 MySQL 8 实测的受影响行数语义**（Homebrew `mysql@8.0` 一次性实例，方法同 54 号 §十一）：首次赞 = 1；同一秒完全相同的重复 = 0；
稍后重复 = 2；取消 → 再赞（复活）= 2；反向（B 赞 A）是另一对 = 1。应用只认 `== 1`，所以 0 / 2 都不会重发通知。

## 四、运营开关 `timer.share.like.enabled`（默认 true）

54 号 §八说「本功能没有运营开关」——**那句话对计时二维码同步本身仍然成立**，点赞是例外：16 号文档的判据是「需要一键停掉一整类
用户可见行为」，而对人点赞是 29 号驳回史里被点名的「认可 / 表情」的单键简化版，是整个计时域**残余审核风险最高**的一面
（用户明确要求「有问题再下线」）。默认开且代码侧缺键也按开（`isEnabled(KEY, true)`），所以没有「忘了开 ⇒ 静默不可用」的失败模式。
关闭 = 读接口 `enabled=false`（前端整个隐藏入口）+ 写接口 `DISABLED` + 不再发被赞通知，**历史数据保留**，重新打开即恢复。
admin-web「运营配置」已登记该键（`OPS_CONFIG_META`）。

## 五、并发、速率与事务

- 点赞没有任何远程 I/O，所以 `TimerShareLikeService` 直接事务化（与 `CrowdReportLikeService` 同构），**不放进**刻意无事务的
  `TimerShareService`；toggle 是一条语句的幂等 upsert，没有「事务内捕获唯一键 ⇒ rollback-only」的问题，也不需要悲观锁。
- 速率：写 20 次 / 10 分钟 / 用户，读 60 次 / 分钟 / 用户，各自独立于 54 号 §四 的创建 / 结算额度（狂点赞不能挤掉结算上报的额度）。
- 窗口判定 `epochMsOf(createdAt)`：加入时刻由流水 `created_at` 派生，与 `status.joins[].joinedAtMs` 共用 `TimerShareClock.epochMsOf`。

## 六、没做（有意）

- ~~**账本流水里不能点赞**~~ → **2026-10-13 已做（前端 61 号 D13）**：服务端**没有任何改动**——账目仍不携带会话凭据（V47 的口径不变），
  句柄由小程序存在**本机**账目行里、永不上云；服务端看到的仍是同一个 `GET/POST /timer-shares/{token}/likes`，鉴权仍是「成员身份」。
- ~~对方「在线」时的实时推送（轮询顺带）~~ → 2026-10-13 已做，**仍然没有服务端改动**：前端对账轮询顺带读 `GET likes`
  （前端 61 号 §十，节流 ≥ 20s、`LIKES` 慢档 30s；本接口读额度 60 次/分钟/用户，留 4 倍余量）；App 后台 / 被杀时无系统推送（订阅消息仍未做，同 54 号 §十四）。
- 礼物；表情白名单；加入者之间互赞；排行 / 积分 / 总榜（2026-10-13 起"一位用户的点赞记录"可见，见 §八，但仍无任何全站聚合）。

## 七、验证（2026-10-12）

`./mvnw -o -s settings-central.xml -Dtest='TimerShare*Test,UserBehaviorCatalogMirrorTest' test`：`TimerShareLikeRulesTest` 8 / `TimerShareLikeServiceTest` 18 /
`TimerShareLikeControllerTest` 4 / `TimerShareWireFormatTest` +2 全绿。**真实 MySQL 8 上验证了**：V48 迁移可执行、`qwt_ops_config` 默认行、
upsert / 软删 / 复活的受影响行数（上文）。**未验证**：Hibernate `ddl-auto=validate` 对新实体的真实启动校验（实体列名全部显式声明，类型与 V48 逐列对照，
但没有起完整容器）、真机全链路（见前端 61 号·真机清单）。

## 八、点赞记录（2026-10-13 第二版；前端权威 = quwuting 仓 61 号 §九）

用户要「点头像就能看到**该用户**所有的点赞记录」（首版读成"调用者本人的私有记录"，被用户纠正后重做）。**读法**：点谁的头像就看谁的——
**记录主人**（`subject`）收到的与送出的赞，按对方分组。主人 = 调用者本人（不带参数）或调用者在某张会话里点开的那位同行者（带 `token + relation + seq`）。
代码落点 = `TimerShareLikeHistoryController` / `TimerShareLikeHistoryService` / `TimerShareLikeHistoryRules`（纯函数分组）/
`TimerShareLikeHistoryResponse`；枚举 `TimerShareLikeDirection`（`RECEIVED` / `GIVEN`，相对主人）、`TimerShareLikeSubject`（`SELF` / `PEER` / `UNRESOLVED`），
前端 `TIMER_SHARE_LIKE_DIRECTIONS` / `TIMER_SHARE_LIKE_SUBJECTS` 逐值比对（门禁 X11 / X16）。

| 接口 | 说明 |
|---|---|
| `GET /timer-shares/likes/history` | 调用者**本人**的记录：`{enabled, subject:"SELF", summary:{received,given,people}, groups:[{me,nickname,avatarUrl,received,given,lastAtMs,events:[{direction,atMs}]}], truncated, serverNowMs}` |
| `GET /timer-shares/likes/history?token=&relation=&seq=` | 调用者点开的那位**同行者**的记录（`subject:"PEER"`；`me=true` 的那组 = 调用者自己与 TA 之间，排最前）。三个参数里带了任何一个就是在要对方的记录：解析不出 ⇒ `subject:"UNRESOLVED"` + 空记录（**不读任何点赞数据**）。开关关 → `enabled=false` 其余空 |

**授权边界怎么守住**（这是本节最该读的部分）：
- **看别人的记录必须先过 `TimerShareLikeService#resolvePeerUserId`**——调用者是这张会话的成员、且那位是他在这张会话里的对家（主持方 ↔ 加入者；加入者之间不互通）。
  解析不出一律 `UNRESOLVED`，**绝不退回成调用者自己的记录**（前端会把返回的数据画在对方名下，那是张冠李戴）；前端另有 `subject` 对照校验（`toLikeRecord`），双保险。
  门禁 X17 钉住"resolvePeerUserId 先于任何点赞数据读取、UNRESOLVED 立即返回"。
- **读路径以「主人参与的会话」为界**：先取「主人作为主持方开过的 / 作为加入者进过的会话 id」（走**既有**索引 `qwt_idx_ts_host_created` / `qwt_idx_tsj_user_created`，各取最近 200 张），
  再取这些会话里与主人有关的有效点赞行（`share_id IN (…)` 走唯一索引 `qwt_uk_tsl_share_pair` 的 `share_id` 前缀，行再按「主人是赞者或被赞者」过滤，最多 500 条）。
  点赞行只可能产生在主人参与的会话里，所以这条路径**取得全**；同时**没有新迁移、没有以 `liked_user_id` 打头的索引**（V48 的结构性红线原样保留；门禁 X14 扫所有迁移文件钉住），也**没有按被赞者扫表**。
- 聚合只在内存里、只围绕一位主人：每组两个计数（对方赞过主人几次 / 主人赞过对方几次）；**没有**跨主人的全站量、没有排名。
- 响应不含任何用户 id（`WireFormatTest#likeHistoryCarriesNoUserIdentifiers…` 钉住）；对方用「现在的昵称 / 头像」认人，与会话内「资料互看」同口径。
  ⚠️ **新增的披露面（用户明确要的）**：A 的记录里出现 A 与第三方 Y 之间的赞，Y 的昵称头像会被与 A 同场过的人看到。收敛方式见前端 61 号 §九·9.4。

**失败语义**：与 `GET likes`（装饰性读取，限流返回空）相反——这是用户主动打开的页面，**速率超限抛 `BusinessException(1006)`**，让客户端显示「加载失败 · 重试」，
而不是返回一份空记录让人误以为「TA 还没有人赞过」。读额度 30 次/分钟/用户（按调用者计），独立于详情卡的 60 次。

**上限与诚实**：会话 200+200、行 500、分组 50、每组事件 20；任一撞到 ⇒ `truncated=true`（客户端显示「只显示最近的记录」，不假装完整）；`summary.people` 在分组被截断时仍是总人数。
调用者自己那组（`me`）总是最前；**没有占位组**——主人没有任何点赞时 `groups` 为空、`summary` 全 0，客户端画空态。
`resolvePeerUserId` 不判窗口（记录是历史，窗口只管能不能赞）。

**验证**：`TimerShareLikeHistoryRulesTest` 6 / `TimerShareLikeHistoryServiceTest` 13（含：本人不解析对方、对方记录读的是对方的会话而不是调用者的、`me` 组置顶、
空记录、解析不出 ⇒ UNRESOLVED 且不读任何点赞数据、只带 relation 也算要对方的、开关关回显请求视角）/ `TimerShareLikeHistoryControllerTest` 5 / `TimerShareLikeServiceTest` +2 / `WireFormatTest` +1，
全部 Mockito / MockMvc、不连库。**未验证**：三条 JPQL 在真实 MySQL 上的执行（已写 `TimerShareLikeHistorySqlTest`，`@Tag("db")` 默认禁用，**没有跑**——本仓 `application-mysql.yaml` 指向生产 RDS 且 Flyway 开，必须换一次性本机库再跑）、
Hibernate/Spring Data 启动期对三条 `@Query` 的解析（没起容器）、性能（"热门主持方"的会话数 × 点赞行规模在真实数据上的耗时——这是新增的读放大点：读别人的记录取的是别人的会话）。
