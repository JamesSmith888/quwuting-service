# 52 · 门店到访痕迹（venue presence，2026-09-29）

> 后端权威文档。采集端（小程序）= quwuting 仓 `docs/agents/52-venue-presence.md`；
> admin 展示 = quwuting-admin-web 仓 README「门店列表 / 门店详情」节。
> 迁移 = `db/migration-mysql/V33__venue_presence_pings.sql`（头注含完整根因）。

## 1. 定位与边界（先读这个再动代码）

**一行 ping = 一次「用户此刻在门店附近」的可证实事实**，采样主力 = 小程序每次打开（onShow）。

三个刻意不做（动手前先自查是否在重蹈）：

| 不做 | 根因（2026-09-29 数据驱动裁决） |
|---|---|
| **不承诺「到店人数」** | 分母（真实到店）未知：数据只覆盖「到店 × 打开小程序 × 定位命中」联合事件，69% 用户 30 天只活跃 1 天 ⇒ 严重低估且低估幅度不可测。定位 = 痕迹/打标，admin 数字必须带口径说明 |
| **不进热度公式** | 到店数天然随曝光增长（排前面→更多人去→数更多）＝马太闭环（热度四问第 3 问不过）。先只做展示与打标，数据量起来后按 05 号流程另行评估 |
| **不落/不传用户经纬度** | 行踪轨迹属敏感个人信息。协议上不存在坐标字段（不是"暂不上传"）——这是本功能隐私正当性的来源，任何改动不得引入坐标 |

歌友会（SONG_CLUB）不在采集范围：坐标写路径主动清空 ⇒ nearby 拿不到 ⇒ 天然排除；
写接口侧另有显式拒绝（`VenuePresenceService.report` 的无坐标/SONG_CLUB 守卫，封死直连伪造路径）。

## 2. 采样时机的根因（为什么不是「每 30 分钟」）

需求原始设想「未关闭时每 30 分钟取一次」被两层证据推翻：

1. **平台层**：小程序切后台 ~5s 后 JS 线程挂起、定时器停摆；持续后台定位
   （`wx.startLocationUpdateBackground`）被 51 号位置服务明确禁止（分级弹窗 + 审核）。
   ⇒ 可采窗口 = **前台驻留**。
2. **业务层**：日均打开 48.5 人、人均浏览 5.8 次、69% 用户 30 天只活跃 1 天
   ⇒ **单次前台会话远短于 30 分钟**，「每 30 分钟补采」在现实会话里一次都碰不上。

⇒ 定稿形态：**主力 = App onShow 每次一次；补采 = 前台常驻每 15 分钟**（间隔必须 ≤
典型会话长度）。采集零新增定位调用——`pages/index` onShow / location 服务本来就在定位，
presence 只是在拿到新 fix 后顺带做一次 nearby 判定 + 一条上报。

## 3. 数据模型（V33）

`qwt_venue_presence_pings`：BaseEntity 四列 + `user_id` / `venue_id` / `write_bucket` /
`distance_m` / `accuracy_m`。

- **`write_bucket = floor(epochMinute / 15)`**（UTC epoch 派生，与时区无关），
  `UNIQUE(user_id, venue_id, write_bucket)`：onShow 抖动由桶吸收，一行 = 一个
  15 分钟窗口的**首见事实**（`ON DUPLICATE KEY UPDATE` 只刷 updated_at，
  不改写 created_at / distance / accuracy——首证保留）。
- **写宽松读严格**：写侧只做协议限幅（distance ≤ 500m、accuracy ≤ 500m，防脏数据）；
  「到访 / 附近」口径全部在**查询侧**判定。理由：门店坐标是 `wx.chooseLocation`
  人工选点（10~30m 误差），阈值定错时历史数据可回溯，无需重采。
- 索引：`(venue_id, created_at)`（admin 聚合）、`(user_id, created_at)`（异常排查/打标回溯）。
- 时间戳 Java 传 `LocalDateTime.now()`（JVM 北京时间，禁 DB now()——V59 同款事故）。
- **V34 `qwt_venue_presence_consents`（开关状态流水）**：每行一次状态确立
  （DEFAULT=默认态确立 / USER=用户手动变更），当前态 = 每用户最新一条；
  授权模型与统计口径见 §5「用户级授权」。

## 4. 口径参数与判据

| 参数 | 值 | 位置 | 依据 |
|---|---|---|---|
| 命中半径 | 20m | `VenuePresenceService.HIT_RADIUS_M` | 真库实测 20m 内有邻居的店仅 5.4%（归因唯一性成立）+ 需求方直觉 |
| 精度门槛 | 30m | `HIT_MAX_ACCURACY_M` | 超过即城市级误差；NULL 精度视为达标（未提供 ≠ 超标） |
| 附近半径 | 300m | `NEARBY_RADIUS_M` | 对齐 `GET /venues/nearby` 缺省值——同一「附近」语义一个值，不造第二份真值 |
| 写窗口 | 15min | `WRITE_WINDOW_MINUTES` | ≈ 典型前台会话粒度上界 |

**口径参数为什么不进 opsconfig**：① 阈值是统计口径而非产品开关，变更应与本文档和
admin 展示文案同步发版；② 管理端开关控件承载不了数值语义（数值会被渲染成
「已开启/已关闭」= 界面撒谎，违反「标签恒等于结果」纪律）。**只有采集总开关
`presence.collect.enabled`**（V33 插默认行 true + OpsConfigService 常量 + admin-web
登记三处同步）进 opsconfig。

### 统计口径（查询侧唯一实现 = `VenuePresencePingRepository`）

- **到访人数（UV）**：`COUNT(DISTINCT user_id)`，`distance ≤ hitRadius` 且
  `(accuracy IS NULL OR accuracy ≤ maxAccuracy)`，时间窗 7d / 30d。
- **附近人数**：同谓词、半径换 300m。语义 = **片区覆盖度**（这一带出现过多少用户），
  不是实时在场——实时「附近」在当前量级（日均打开 48.5 人）下恒为 0，无意义。
- **禁自然日去重**：舞厅营业跨零点（22:00 进 02:00 出会被日粒度拆成两天两次），
  UV 恒按时间窗去重（与热度「近 30 天去重人数」同构）。
- **会话切分（到访次数）刻意不做**：采样稀疏后「次数」≈ 打开次数，无信息量；
  且 JPQL 无 FROM 派生表能力，切分进不了列表查询（同热度复访项约束）。

## 5. 信任边界（distance 是自报值）

隐私红线决定服务端**不复算**距离（复算需要坐标）。防刷面 = 伪造 distance 刷「到访」。
缓解 = 桶幂等（唯一约束）+ 每用户滑动窗口频控（10 次/60s，`writeRateCache`）+
无坐标门店显式拒绝 + admin 侧距离分布观察。**若未来要把到访数据用于任何有利益
关联的场合（排序/积分），必须先重新评估本节**——这是当前形态的明确边界。

用户级授权（consent，V34）——09-29 四轮改版后服务端**可观测偏好**（不再是
纯端上私有，但仍不含任何位置信息）：

- **模型 = 默认开启 + 常驻开关 + 手动开启提醒**（详见采集端 52 号 §3.5）：
  未选择 = 采集开启；关闭立即停采（端上判定）；开关只存端上 `presence_consent`。
- **服务端感知面 = 状态确立流水**（`qwt_venue_presence_consents`，V34）：
  ① USER 行——「我的-设置」拨动开关时端上 fire-and-forget 上报
  `POST /venues/presence-consent`（body `{enabled}`，每次一行，不幂等去重）；
  ② DEFAULT 行——首次采集 ping 时该用户无任何 consent 行则补一条
  enabled=true（`INSERT ... WHERE NOT EXISTS` 单语句，最少 DB 往返；并发窗口
  双写无害，统计口径吸收）。
- **admin 统计**（`GET /admin/venues/presence-consent-stats`，门店列表页头展示）：
  当前态 = 每用户最新一条（native 窗口函数，`ROW_NUMBER` 按 created_at DESC,
  id DESC）；启用 / 关闭去重用户数 + 其中「从未手动改过设置」的默认开启人数
  （最新态 source=DEFAULT）+ 近 30 天 USER 变更次数。
- **信任边界不变量**：consent 流水是 admin 统计输入，**不反哺采集行为**（采集
  与否只由端上开关决定）；若未来要服务端强制执行 consent（如关闭者发 ping 直接
  拒收），属信任模型升级，须与「distance 复算」一并评估。

## 6. 接口清单

| 接口 | 鉴权 | 说明 |
|---|---|---|
| `POST /venues/{venueId}/presence` | requireAuth | body `{distanceMeters, accuracyMeters?}`；桶幂等；开关关闭返回 `accepted=false(DISABLED)` 而非报错；错误码 **1022**（门店不存在 / 不参与采集 / 参数越界 / 频控）；**同时触发默认态确立**（该用户无 consent 行则补 DEFAULT 行，V34） |
| `POST /venues/presence-consent` | requireAuth | 开关状态上报（V34）：body `{enabled}`，每次变更插一行 USER 流水；fire-and-forget，客户端失败静默 |
| `GET /admin/venues` | requireAdmin | 管理端门店列表（无业务裁剪全量分页，`AdminVenueQueryService` + `VenueRepository.findAdminPage`）；行内带 `visitUsers30d` 批量注入；status 经 `WireEnums.parse` 宽容解析（非法 = 不筛） |
| `GET /admin/venues/{id}/presence` | requireAdmin | 单店到访统计（口径参数随响应回显，admin 展示必须与数值同屏；DTO 全字段 `@JsonInclude(ALWAYS)`——non_null 全局策略会删 null，35 号教训） |
| `GET /admin/venues/presence-consent-stats` | requireAdmin | 开关统计（V34）：启用 / 关闭去重用户数 + 默认开启未改设置人数 + 近 30 天手动变更次数；admin-web 门店列表页头展示 |

admin-web 门店基础信息详情复用既有公开 `GET /venues/{id}`（该响应无 venueType——
管理列表行的类型来自本清单第二个接口，两处字段面不同是有意的）。

## 7. 验证边界（静态红线）

后端：`rm -rf target/maven-status` 后 compile + test-compile 全绿（防 ECJ 假绿）；
`VenueListQueryHqlSyntaxTest`（新增 `findAdminPage` JPQL 过括号配平门禁）✓。
未连库、未启动服务；行为验证（真机采集→落库→admin 展示）交用户联调。
