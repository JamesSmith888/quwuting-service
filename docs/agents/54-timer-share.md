# 54 · 计时「二维码同步给对方」（timershare）

> 2026-10-07 新增（V42）。计时中出示小程序码，对方扫码后在**自己的手机上**从同一起点开始计时；
> 目的 = 传播小程序（舞伴侧是现有用户里的空白人群）。前端权威文档 = quwuting/docs/agents/59-timer-share.md
> （入口 / 弹层 / 落地页 / 本地还原 / 撤销 / 合规文案）。
> **本文件只讲服务端**：数据、接口、并发、防护、运营开关与上线顺序。

## 一、模型：一次快照 + 独立副本（为什么不是实时镜像）

- 服务端只在**三个瞬间**参与：主持方创建/刷新快照、接收方扫码加入、主持方关闭。此后不再在线。
- 扫码得到的是一份**独立副本**：加入那一刻之后各走各的账（各自暂停、各自结算）。
  - 弱网（舞厅地下室）下只有扫码那一次需要网络；
  - 对方有自己的账，留存不依赖主持方；
  - 代价：加入之后主持方再暂停/继续，两边会差开——这是产品前提，不是缺陷（P2 才评估暂停同步）。
- **服务端只存时间事实，不存金额。** 金额由各端 `danceCost` 现算（单一口径）。

快照 = 「真实起点 + 已排除秒数 + 规则」，全部落在**服务端时间轴**上（两端手机时钟互不可信）：

| 列 | 含义 |
|---|---|
| `start_server_ms` | 主持方真实起点。主持方上报 `wallElapsedMs`（此刻 − 起点），服务端以收到时刻回推：`start = recv − wall` |
| `excluded_seconds` | 已排除出计费的整数秒（暂停 + 已去掉的空窗）= `floor(wall/1000) − net` |
| `paused_at_server_ms` | 创建/刷新时主持方处于暂停 → 该时刻（服务端时钟），否则 NULL；接收方据此以冻结态起步 |
| `rule_json` | 计价参数 `{"tiers":[{durationMinutes,price,mode?}]}`，服务端白名单重序列化 |
| `snapshot_at_ms` / `expires_at_ms` | 快照时刻 / 二维码失效时刻（`+10 分钟`，主持方每次打开弹层都顺延） |

净时长公式与前端 `getElapsedSeconds` 同构（`TimerShareClock.netSecondsAt`）：
`max(0, floor((ref − start)/1000) − excluded)`，`ref` = 暂停则冻结时刻，否则当前时刻。

**excluded 存秒不存毫秒**：主持方端净时长是 floor 秒、`pausedAccumSeconds` 是整数秒；接收方用整数秒还原，
读数才与主持方屏幕逐秒一致（毫秒存法会在 floor 边界差 1 秒）。

## 二、表（V42）

- `qwt_timer_shares`：一行 = 主持方某一场计时（某一位成员）的分享会话。
  `UNIQUE(token)`、`UNIQUE(host_user_id, session_key)`（同一场重复打开弹层 = 刷新同一行，token/码图不变）；
  `status` ∈ ACTIVE/CLOSED（**过期是派生态**：`expires_at_ms < now`，不落库，无需调度任务去翻它）。
- `qwt_timer_share_joins`：一行 = 一个用户加入了一张会话。`UNIQUE(share_id, user_id)`；
  记 `is_new_user`（账号创建距加入 ≤ 15 分钟）、`net_seconds_at_join`（观测「计时进行到多久才被扫」）。
- `parent_share_id`：传播链（加入者自己再出示时，指向他加入的那张会话）。
- 时间列全部 `bigint` 毫秒：参与「服务端时间轴」算术，用 datetime 只会多一次时区换算。可读时间看 `created_at`。
- 无外键、枚举列无 CHECK、不写 DB `now()`（全库约定）。

## 三、接口（全仓只有 GET/POST；除码图外均需登录）

| 接口 | 说明 |
|---|---|
| `POST /timer-shares` | 主持方创建/刷新（幂等于 `(user, sessionKey)`）。请求见下；响应 `{token, qrPath, expiresAtMs, serverNowMs, joinCount, maxJoins}` |
| `GET /timer-shares/{token}/status` | 主持方轮询（弹层开着每 3s）。`{status: ACTIVE/CLOSED/EXPIRED, joinCount, maxJoins, expiresAtMs, serverNowMs}`；不是自己的 → 1043 |
| `POST /timer-shares/{token}/close` | 主持方结算/单独结算/丢弃时清理。`{closed}`，幂等，**永不报错**（不泄露 token 是否存在） |
| `POST /timer-shares/{token}/join` | 接收方扫码加入。`{outcome, serverNowMs, snapshot?}` |
| `GET /timer-shares/{token}/wxacode.jpg` | 码图（image/jpeg；**公开**——`<image src>` 不能带 Authorization，token 即凭据） |

创建请求体（全字段包装类型，缺失显式拒绝）：

```json
{ "sessionKey": "1780000000000:m1", "wallElapsedMs": 1530400, "netElapsedSeconds": 1230, "running": true,
  "rule": { "tiers": [{ "durationMinutes": 4, "price": 20 }, { "durationMinutes": 60, "price": 300, "mode": "linear" }] },
  "venueId": 42, "parentToken": "AbC123xyz0" }
```

### join 的 outcome（预期的业务状态，以**数据**回传而非异常）

客户端请求层（`httpRequest`）遇业务错误码只留 message、丢掉 code，无法据此分流；而落地页需要对每种状态给
一句贴切的话和一个出路。所以这些状态是 HTTP 200 + `outcome`：

`JOINED`（新加入）/ `ALREADY_JOINED`（重复扫码，幂等再给快照，**不受人数上限约束**）/ `SELF`（扫自己的码）/
`EXPIRED` / `CLOSED` / `FULL` / `DISABLED`（运营开关关闭）/ `NOT_FOUND`（格式非法/不存在/快照损坏，不区分）/
`TOO_FREQUENT`（单用户速率保护）。只有 JOINED/ALREADY_JOINED 带 `snapshot`。

判定链即优先级（`TimerShareStore.join`）：不存在 → 自己 → 已关闭 → 已过期 → 已加入过 → 已满 → 规则可解析 → 新加入。

### 错误码（登记于 12 号文档）

| 码 | 含义 |
|---|---|
| 1041 | 计时读数/规则/场次标识非法（文案固定「计时读数异常，无法同步」，细节只进日志） |
| 1042 | 功能未开放（创建侧；加入侧用 `DISABLED` outcome） |
| 1043 | 二维码不存在或不是自己的（主持方轮询） |
| 1006 | 速率/额度超限（复用既有「操作过于频繁」） |

## 四、口径参数（`TimerSharePolicy` 唯一声明处）

| 参数 | 值 | 理由 |
|---|---|---|
| 二维码有效期 | 10 分钟（自最近一次创建/刷新） | 快照新鲜度保护：主持方之后暂停/继续，旧快照就不再代表现状；前端弹层开着每 4 分钟自动刷新，不会自己过期 |
| 人数上限 | 5 / 会话 | 一次换几位舞伴的现实上限，也是刷量硬顶 |
| 墙钟时长上限 | 12 小时 | 一场舞不会更长，超限多半是忘了结束的计时 |
| 新建上限 | 30 张 / 滚动 24h / 主持方（DB 计数） | 刷新同一场不计 |
| 写速率 | 30 次 / 10 分钟 / 用户 | 进程内滑动窗口（`SlidingWindowLimiter`） |
| 状态轮询 | 60 次 / 分钟 / 用户 | 弹层每 3s 一次，留 3 倍余量 |
| 加入速率 | 20 次 / 小时 / 用户（含被拒的） | 防脚本扫 token |
| 新用户判据 | 账号创建距加入 ≤ 15 分钟 | 启发式，见「漏斗」 |

这些是协议/防护参数，**刻意不进 opsconfig**（管理端控件只承载开关/整数，而这些参数相互制约——例如 TTL 必须大于
前端自动刷新周期——拆开可调会让界面允许配出矛盾组合）。唯一进 opsconfig 的是总开关。

## 五、并发与事务

- **事务内禁远程 I/O**：`TimerShareService`（编排层，无 `@Transactional`）做开关/限流/校验/重试/微信外呼；
  `TimerShareStore`（事务层）只有 DB 操作。拆成两个 Bean 的原因：创建路径要「唯一键冲突 → 事务结束后重试」，
  `@Transactional` 是代理式的，同类自调用不经代理，而事务内一旦唯一键异常就是 rollback-only，必须在事务**外**捕获。
- **加入**：对分享行 `SELECT … FOR UPDATE`，把「已加入判定 → 人数上限 → 流水写入 → join_count 自增」串行化。
  不走 save + catch 唯一键（事务内 rollback-only，本库 `PointsUnlockRepository#insertIfAbsent` 的 500 事故形态）。
- **创建/刷新**：对 `(host, session_key)` 行 `FOR UPDATE` 读；不存在则新建（`saveAndFlush`，冲突立刻暴露）。
  编排层对**并发竞态**重试（最多 3 次，每次换新 token）：唯一键冲突（`DbConstraintViolations.isUniqueViolation`，
  其余完整性错误原样抛出）与锁失败（`PessimisticLockingFailureException`：死锁 / 锁等待超时）。
  - **`createOrRefresh` 必须是 `READ_COMMITTED`**（2026-10-07 真实 MySQL 8.0.41 实测的根因）：首次创建是「`FOR UPDATE` 一行**不存在**的
    `(host, session_key)` → 再 INSERT」。默认 REPEATABLE READ 下，对不存在的行加锁落成**间隙锁**，多个事务可**同时**持有同一个间隙锁，
    各自 INSERT 时插入意向锁又与对方的间隙锁互斥 ⇒ **死锁（错误 1213）**。8 线程并发首次创建同一场、30 轮：209 次被回滚成死锁，
    而不是预期的「一个赢、其余撞唯一键」。改 READ_COMMITTED 后：零死锁，输家在唯一索引上排队、拿到 1062，第二次进入找到赢家的行走刷新分支
    （240 次尝试全部成功：30 CREATED + 26 REFRESHED + 184 重试一次后 REFRESHED）。
    这件事 **H2 验证不出来**（H2 的锁语义不同）——所以涉及锁的并发逻辑必须在真实引擎上验。
  - 加入 / 关闭锁的是**已存在**的分享行，不产生间隙锁，保持默认隔离级别。
- `ON DUPLICATE KEY UPDATE` upsert **被否决**：MySQL 任一唯一索引冲突都触发 UPDATE 分支，token 撞号会**改写别人的行**。

## 六、安全与合规

- **token 是 bearer 凭据**：10 位 base62、`SecureRandom`；格式校验先于任何查库/微信外呼（随便拼个串就能触发
  DB 查询或微信调用，是最便宜的放大攻击）。三道收口：有效期 / 人数上限 / 主持方关闭。
- **规则不带名称**：规则名是用户输入的自由文本，经二维码展示给另一个用户 = UGC 公开展示（个人主体类目红线）。
  `TimerShareRules` 把请求解析成强类型、只取三个数值字段重新序列化，多余字段在解析那一刻就丢掉——库里和线上只可能有数字
  （`TimerShareWireFormatTest#ruleNameSentByAClientNeverSurvivesNormalization`）。接收方由档位本地派生展示名。
- **门店名不信任客户端**：只收 `venueId`，校验存在才保留；加入响应里的名称由服务端据 id 现取。
- **同行者资料只有昵称 + 头像**（V45 起双向下发，见 §十三「资料互看的边界」；2026-10-09 起前端可点开详情卡，仍不含任何账号标识 / 年龄 / 性别 / 城市）。**此前本条写的是"不展示任何一方的昵称/头像"——V45 起已过期。**文案禁出现「陌生人/交友/匹配」（前端门禁 `check:timer-share`）。
- **不给分享者任何奖励**（可能触及「诱导分享」规范，也会招来小号刷）。
- 码图端点公开但**不可被滥用**：会话不存在/已关闭/已过期一律 404，且不触发微信外呼。

## 七、二维码

- `scene = t=<token>`（12 字符，微信上限 32）；页面 `pages/timer-join/timer-join`（**非 tab 页**：tab 页收不到带参入口，
  见前端 47 号 §7.11）；`env_version` = `wechat.qrcode-env`（与门店码共用）。
- 复用 wxacode 域的 `WxacodeSpec`（指纹 = 缓存键 = ETag 的唯一派生处）与 `WechatService.getUnlimitedQrCode`。
  归「个性化码」一类（36 号文档的分层）：内容随 token 变、键空间无界、可再生无损失 ⇒ **内存缓存（Caffeine，15 分钟）**，
  不物化进 `qwt_wxacode_assets`。
- 响应头 `ETag` + `Cache-Control: private, max-age=600`（与二维码有效期同量级，不是 24h：会话失效后不该再被缓存命中）。
- **加载优化 = 预热（2026-10-07 晚）**：码图是「POST 返回后前端才发起」的第二跳，链路上唯一的外部依赖 = 微信外呼
  （首次未命中缓存时 300ms~3s 量级）。`createOrRefresh` 在**响应发出后**异步预热码图（`TimerShareQrService#prewarm`，
  单线程 daemon 队列；可注入提交口供单测直跑），把外呼提前到与前端渲染窗口并行。**不会重复外呼**：预热与随后的
  图片请求命中同一 Caffeine 键，`cache.get(key, fn)` 对同一 key 的并发调用至多执行一次 `fn`。失败一律静默
  （图片请求自然重试；连提交被拒也不波及主路径）。观测：`render` 打 `[timer-share] qr render: costMs= bytes=`
  日志（命中 ≈0ms；未命中 ≈ 外呼耗时；**不打 token**——它是加入凭据，不落日志）。

## 八、无运营开关与上线顺序

**本功能没有运营开关，功能常开**（2026-10-07 用户裁决：这是正常功能，不属于「提审期要能一键关」的
高敏感面，删掉了原 `timer.share.enabled` 及其全部联动）。

连带下线：`TimerShareJoinOutcome.DISABLED` outcome、创建侧错误码 `1042`、前端 `isTimerShareEnabled` /
`refreshTimerShareFlag` 与其落盘缓存、管理后台的「计时二维码同步」配置项。前端入口可见性现在**只**由
「仍有人在计时」派生（59 号文档 `isShareEntryVisible`）。

反向守卫（防止有人日后把它加回来）：前端门禁 X5（两端都不得出现该键）/ X12（迁移不插该行）/ C10 +
B7d（服务层不得导出开关）；后端迁移里也写明理由。

> **真要紧急止血**（例如突发合规争议）：改 `TimerShareStore` 的 `joinable` 判定或直接下线端点，
> 走发版收口——不要靠配置项，那会重新引入「忘了开 → 功能静默不可用」的失败模式。

**上线顺序**：① 部署后端（Flyway 建表）→ ② 发布含 `pages/timer-join` 的小程序版本并过审。
小程序码 `env_version=release` 要求落地页已在线上版本中，所以**后端先于前端发布是安全的**：那时还没有
客户端能调创建接口，不会生成「扫出来是页面不存在」的码。开发者工具/体验版联调：把 `wechat.qrcode-env`
设为 `trial`/`develop`。

> ⚠️ V41 已被「热度上报营业日」占用（同日另一条工作流），本迁移是 **V42**；部署前核对 `db/migration-mysql` 下没有第二份 V42。

## 九、漏斗（直接 SQL，不建统计表）

内部账号剔除沿用 `UserStatsSql.USER_SCOPE` 口径（真实用户谓词）；下面只写骨架。

```sql
-- 1. 创建 → 被扫 → 新用户（按日）
SELECT DATE(s.created_at) d,
       COUNT(DISTINCT s.id)                                    shares,
       COUNT(DISTINCT CASE WHEN j.id IS NOT NULL THEN s.id END) scanned_shares,
       COUNT(j.id)                                             joins,
       SUM(j.is_new_user)                                      new_users
FROM qwt_timer_shares s LEFT JOIN qwt_timer_share_joins j ON j.share_id = s.id
GROUP BY d ORDER BY d;

-- 2. 加入者是否自己结算（账本 source=DANCE，且结算发生在加入之后）
--    用 e.ts（账目的业务时刻 = 结算那一刻）而不是 e.created_at（上云时刻，弱网下可能晚很多）
SELECT COUNT(DISTINCT j.user_id) joiners,
       COUNT(DISTINCT CASE WHEN e.id IS NOT NULL THEN j.user_id END) settled
FROM qwt_timer_share_joins j
LEFT JOIN qwt_spend_entries e ON e.user_id = j.user_id AND e.source = 'DANCE' AND e.deleted = 0
     AND e.ts >= j.created_at;

-- 3. 传播链深度（加入者再出示）
SELECT COUNT(*) FROM qwt_timer_shares WHERE parent_share_id IS NOT NULL;
```

口径边界（如实登记）：
- `is_new_user` 是启发式——静默登录在首次打开小程序时建号，经扫码进入的新用户建号时刻与加入时刻几乎重合；
  但用户也可能先逛了 20 分钟才扫。它量「这次扫码带来了一个刚建号的账号」，不是严格归因。
- **量级预期**（2026-10-07 生产只读）：DANCE 结算 123 条/14 人，ADMIN 一人占 85%，舞伴侧真实用户 ≈ 0；周计时用户 ≈ 7。
  这个功能的价值是**打开舞伴侧 + 把漏斗埋好**，绝对量一周内是个位数，别用它衡量获客大盘。

## 十、已知误差（如实登记，别当成零误差）

读数是主持方在 t0 取的，服务端在 t0 + 上行延迟 收到并以收到时刻回推起点，所以快照比真实**少算**一个上行延迟
（移动网络 50~300ms，弱网可能 1~2 秒）。接收方侧往返校准另有半个 RTT 的对称性假设误差。合计亚秒到秒级，
与「两边后续可能差几秒」的产品前提同量级。想消掉上行延迟需要额外一次时间同步往返，弱网下得不偿失，故不做。

## 十一、验证（2026-10-07）

- **单测**（Mockito / standalone MockMvc，不连库、不起 Spring 容器）`-Dtest='TimerShare*Test,SlidingWindowLimiterTest'`：
  时间算术、规则白名单、token 格式、判定链、编排（开关 / 校验 / 限流 / 重试预算 / 响应装配 / **预热提交时机**）、线上 JSON 形态（含全局 `non_null`
  下的显式 null 契约）、HTTP 层（路由与动词、鉴权先于副作用、`.jpg` 后缀、码图 404/304/缓存头）——共 89 条、0 失败
  （2026-10-07 晚复跑；其中 `TimerShareQrServiceTest` 3 条为本轮新增：预热不重复外呼 / 失败静默不缓存 / 提交被拒不波及主路径）；
  全量 `-Dtest='!QuwutingServiceApplicationTests'` 0 失败（DB 用例按既有约定跳过）。
- **真实 MySQL 8.0.41（本机 Homebrew `mysql@8.0`，独立数据目录 + 端口 33999，不碰生产）**——未入库的一次性脚手架，方法记在这里以便复现：
  1. 用 Flyway 跑**完整的 V1 → V42 链**（含另一条工作流的 V41）：42 条迁移全部成功，`flyway validate` 通过；
  2. 用真实实体 + 真实仓储 + 真实 `TimerShareStore`（`JpaRepositoryFactory` 手工装配，`UserRepository` 打桩）：
     `ddl-auto=validate` 通过（生产同口径）、仓储 JPQL 解析与执行、唯一约束确实在库里、
     **16 线程抢 5 个名额恰好 5 JOINED / 11 FULL 且 `join_count` 恰为 5（无超卖）**、同一人 8 线程并发只写一行、
     首次创建竞态（上文 READ_COMMITTED 的发现）；
  3. 初版曾先用 H2(MySQL 模式) 做同样的验证（过了），但它**漏掉了**间隙锁死锁——只有真实引擎暴露。
  启动真实实例的坑：mysqld 的 pid-file 检查在沙箱内对 `~/.kiro/...` 下的目录报 `Operation not permitted`，数据目录放在仓库 `target/` 下即可；
  macOS 文件系统大小写不敏感会让 mysqld 自动设 `lower_case_table_names=2`（V42 全小写标识符，无影响）。
- **未验证**：真机扫码全链路（微信 release 码、落地页、弱网）——见前端 59 号文档「真机清单」。

## 十二、没做（有意）

- ~~P1「对方已结算 → 一键按此结算」~~ → **2026-10-08 V45 已做**（口径升级为「结算事实同步 +
  建议式对齐」，见「十三、V45 结算事实同步与资料互看」）。
- **P2 暂停/继续同步、结算单码、微信卡片转发**。
- 管理端漏斗看板：上面三条 SQL 足够首发观察；需要常态化再进 admin-web。

## 十三、V45 结算事实同步与资料互看（2026-10-08）

> 需求：① 一方结算后，另一方能看到「对方几点结束」并可选择**按此时间结束自己这场**（建议式，
> **不强制**）；② 扫码加入的双方互看头像昵称。迁移 = V45（四列，见迁移头注）。

### 数据（V45）

| 表 | 列 | 含义 |
|---|---|---|
| `qwt_timer_shares` | `host_settled_at_ms` / `host_settled_net_seconds` | 主持方结算时刻（服务端时间轴）/ 净秒数；NULL = 未结算 |
| `qwt_timer_share_joins` | `settled_at_ms` / `settled_net_seconds` | 加入者结算时刻 / 净秒数；NULL = 未结算 |

四条刻意的设计（细节在 V45 迁移头注）：
1. **不复用 `closed_at_ms`**——close 有三种触发（整场结算 / 单独结算 / 丢弃清理），丢弃不产生结算
   事实；「关码」是凭据生命周期、「结算」是账务事实，两件事各自独立。
2. **重新激活即结清**——主持方「单独结算后撤销」会经 createOrRefresh 重新激活（V42 既有行为），
   此时 host 又回到计时中，`host_settled_*` 一并清空（与 `closed_at_ms` 同口径）。
3. **覆盖语义**——重复上报覆盖为最新一次（客户端重试与「撤销后重结算」都靠它）；无「已结算不可改」。
4. **不检查会话状态**——结算大量发生在码过期 / 会话关闭之后（TTL 只有 10 分钟而一场舞几十分钟），
   CLOSED / EXPIRED 下照常可写、可读，这不是异常路径。

### 接口

| 接口 | 鉴权 | 说明 |
|---|---|---|
| `POST /timer-shares/{token}/settle` | 登录 | 结算事实上报（主持方与加入者共用，服务端按 caller 角色分派写入）。body `{netElapsedSeconds}`（0 ~ 12h 秒；**金额刻意不带**——各端规则可能不同、金额是账务隐私）。尽力而为：token 无效 / 非本会话成员 → `{recorded:false}`（数据回传，同 join 的 outcome 模式）；读数非法 → 1041。限流复用写速率（30/10min） |
| `GET /timer-shares/{token}/peer` | 登录 | **加入者**读主持方：`{status, host, hostSettledAtMs, hostSettledNetSeconds, serverNowMs}`。鉴权 = 调用者必须是该会话的加入者（主持方走 status）；CLOSED / EXPIRED 也正常响应。限流复用状态速率 |

既有接口扩展：`join` 响应加 `host`（主持方资料，仅 JOINED / ALREADY_JOINED 带有）；
`status` 响应加 `joins[]`（`{seq, nickname, avatarUrl, settledAtMs, settledNetSeconds}`，seq 1 起 = 界面
「第 N 位」）。`host` / `joins[].nickname|avatarUrl` 的 null 都是**显式写出**（ALWAYS 契约，客户端
用 null 与字段缺失区分语义）。

### 资料互看的边界（合规收敛点）

- **只下发昵称 + 头像**（`TimerShareProfileView`）——年龄 / 性别 / 城市等一概不经本通道。
  展示形态 = **会话内的静态标识**（计时页同行 chip / 弹层已加入态），不提供"对方主页"与任何
  浏览入口（用户公开主页 `GET /users/{id}` 2026-08-21 因「收集、存储用户身份信息」驳回下线，
  见前端 services/user.ts——本功能刻意不复活它）。
- **不下发加入者的 userId**（`TimerShareJoinView` 只有 seq）——不给跨会话串联同一个人留通道。
- 资料是**服务端现查的当前值**（非快照落库）；客户端各存一份快照用于离线展示（加入方
  `origin.peerProfile`），进页经 peer 顺带刷新。
- ⚠️ **若未来审核要求收敛**：改两处即可全链收敛——① 本域 DTO 不再下发 `host`/`joins[].nickname|avatarUrl`；
  ② 前端同行 chip / 弹层 joined 态退回无资料形态（`utils/timerPeer` 是前端唯一兜底单点）。
  不需要动表、不需要动其它域。

### 已知边界（如实登记）

- **加入方撤销结算后未重新结算**：服务端保留上一次的 `settled_at_ms`（主持方视角可能看到
  「对方已结算」的过期事实）。概率低、影响小；重结算自然覆盖。主持方一侧无此问题
  （重新激活即结清）。
- **结算时刻的客户端换算误差**：接收方用一次往返估偏移（同加入链路），误差上界 = 半个 RTT；
  对齐的落点是本机计时事实，秒级误差与场景容差同量级。
- **加入者视角的资料是快照**（join 时刻 + 进页 peer 刷新）；主持方视角是现查值——两侧不对称
  是有意的（主持方在弹层轮询里天然持续刷新，加入方没有等价通道）。

### 验证（2026-10-08）

- 后端：`TimerShare*Test` 97 条全绿（新增 settle 语义 / peer 鉴权与 CLOSED 可读 / status 装配 /
  WireFormat 的显式 null 契约 8 条）；静态验证止步于此，真机行为交用户（同 59 号 §十）。

## 十四、结算事实可重放 · 加入时刻 · 同行者账目快照（2026-10-09）

> 需求与根因见前端 quwuting 仓 `59-timer-share.md` §十四（用户四条：头像簇 / 结算提醒 / 结算卡 / 账本流水）。服务端只做三件小事，**都是加字段、对旧客户端零破坏**。

### settle 带 `settledAgoMs`（结算事实可安全重放）

V45 的结算时刻 = 服务端**收到**时刻，隐含假设"客户端在结算的同一刻就把它发出去"。但舞厅地下室弱网是常态：上报失败后客户端必须暂存重放，
重放时的收到时刻已是几分钟之后，"对方几点结束"就被推迟了。所以客户端上报的不是时间戳（两端时钟互不可信），而是「**这件事已经过去多久**」
（同一台手机上的单调差，不受时钟偏移影响）：

`settled_at = 收到时刻 − clamp(settledAgoMs, 0, 12h)`

- `SettleTimerShareRequest(netElapsedSeconds, settledAgoMs)`；`settledAgoMs` 缺省 / 负数 = 0（老客户端：行为与 V45 逐字相同）；超 `TimerSharePolicy.SETTLE_MAX_AGE_MS`
  （= 墙钟时长上限 12h）按上限**截断而不拒绝**（它只是展示用时间事实）。前端出站队列保留期与它同值（跨仓门禁 X1 钉）。
- 与主持方创建时上报 `wallElapsedMs` 而非本机时间戳是同一条判据（§一）。覆盖语义不变（重复上报 = 最新一次），所以重放天然幂等。

### status 的 `joins[]` 增 `joinedAtMs`

加入流水的创建时间（服务端时间轴，与 `settledAtMs` 同一条轴，客户端用同一次往返校准换算）。计时页详情卡展示"几点加入"。ALWAYS 契约：缺失显式 null。
**可选字段**——前端先于后端上线时缺省，展示层按"不知道加入时间"处理（不画该行）。

### 同行者账目快照（V47）

账目（`qwt_spend_entries`）新增 `companions_json`：落账那一刻的同行者展示快照（昵称 / 头像 / 关系，不含 userId）。**不是**本域（timershare）的表，
细节与护栏见 `40-spend-ledger.md`「一同计时的人快照」。timershare 域唯一的关联是：快照的原料来自本域已下发的资料（`TimerShareProfileView` /
`TimerShareJoinView` 的昵称头像），由客户端在落账时快照——服务端**不**在账目上联查分享会话（理由见 40 号）。

### 兼容矩阵（后端先发、前端后发 / 反之）

| 组合 | 行为 |
|---|---|
| 新后端 + 旧前端 | `settle` 不带 `settledAgoMs` → 按 0；`status` 多出 `joinedAtMs` 被忽略；`sync` 不带 `companions` → 保留已有值 |
| 旧后端 + 新前端 | `settle` 多余字段被忽略（退化为即时盖章，与 V45 相同）；`status` 无 `joinedAtMs` → 前端不画该行；`entries` 无 `companions` → 前端读为空 |

### 验证（2026-10-09）

`TimerShareServiceTest` +2（settle 带 ago 的回推 / ago 的夹取）、`statusCarriesJoinerProfilesAndSettlementFacts` 增 `joinedAtMs` 断言、
`TimerShareWireFormatTest` +2（join view 的显式 null 契约含 `joinedAtMs`、老客户端 settle 请求仍可反序列化）；`./mvnw -q -o -Dtest='TimerShare*Test,SpendCompanionsTest,SpendServiceTest,SpendEntryLimitsMirrorTest'` 全绿。
未验证：真实 MySQL 上跑 V47（纯 `ALTER TABLE … ADD COLUMN … NULL`，MySQL 8 即时 DDL；本机可用 Homebrew `mysql@8.0` 起一次性实例，方法见 §十一）。

## 相关文件

`timershare/`（controller / dto / entity / enums / repository / service）、`common/ratelimit/SlidingWindowLimiter`、
`db/migration-mysql/V42__timer_shares.sql`、测试 `src/test/.../timershare/service/*`。
