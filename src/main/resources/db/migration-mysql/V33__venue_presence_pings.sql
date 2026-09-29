-- ============================================================================
-- V33（MySQL 轨道 = db/migration-mysql）: 门店到访痕迹（presence）原始事实表
--      （2026-09-29，后端权威文档 = docs/agents/52-venue-presence.md；
--       前端采集侧 = quwuting 仓 docs/agents/52-venue-presence.md）
--
-- ⚠️ 编号说明：PG 遗留轨道（db/migration）与 MySQL 轨道编号各自独立、勿混用。
--
-- ── 需求与根因（为什么是「痕迹表」而不是「统计表」） ─────────────────────────
--
-- 需求：用定位数据记录「用户到店」，供管理后台判断与后续门店热度/排序参考。
--
-- 数据驱动诊断（2026-09-29 真库只读实测，决策依据）：
--   · 门店 1270 家 / 有坐标 1269（99.9%）——坐标覆盖无短板；SONG_CLUB 仅 1 家
--     且坐标被写路径主动清空（cityOnlyAddress 隐私设计），天然不在采集范围；
--   · 20m 内有邻居的门店仅 69 家（5.4%）——「20m 内最近店」的归因唯一性成立；
--   · 日均打开 48.5 人（qwt_daily_checkins 近 30 天），69% 用户 30 天内只活跃 1 天，
--     人均浏览 5.8 次 ⇒ **单次前台会话远短于 30 分钟**。
--
-- 由此推翻原设想的三个根因：
--   ① 「未关闭时每 30 分钟采一次」不可实现也不必要：小程序切后台 JS 线程挂起、
--      定时器停摆（且 wx.startLocationUpdateBackground 后台定位被 51 号位置服务
--      明确禁止）——采样主力只能是「每次打开小程序（onShow）」这一次；
--   ② 「到访人数」的绝对量不可信：分母（真实到店人数）未知，本数据只覆盖
--      「到店 × 打开小程序 × 定位命中」的联合事件，严重低估且低估幅度不可测
--      ⇒ 定位为「可证实的到店事实/痕迹」，不做人数承诺；只服务打标（给浏览/
--      上报补「在店」置信）与 admin 展示，不进热度公式（马太闭环风险）；
--   ③ 阈值不能在采集时一次性判死：门店坐标是 wx.chooseLocation 人工选点，
--      本身带 10~30m 误差 ⇒ **写宽松（存原始 distance）、读严格（命中阈值在
--      查询侧判定）**——阈值调整不需要重新采集，历史数据可回溯。
--
-- ── 隐私红线（本表存在的第一约束） ─────────────────────────────────────────
--   · **不落用户经纬度**：行踪轨迹属敏感个人信息。端侧复用 GET /venues/nearby
--     （服务端 Haversine 返回 distanceMeters）取得「到最近门店的距离」，只上报
--     (venueId, distanceMeters, accuracyMeters) 三元组——用户坐标永不出端。
--   · accuracy_m 同理只是精度标量。数据面最小化是本功能的隐私正当性来源，
--     任何「顺手把坐标也存了」的改动都推翻这条红线，禁止。
--
-- ── 防刷与幂等（15 分钟写桶） ──────────────────────────────────────────────
--   · write_bucket = floor(epochMinute / 15)（Java 侧按 UTC epoch 计算，与时区
--     无关）：同一 (user, venue, bucket) 唯一——onShow 可能高频触发，桶内重复
--     命中一律幂等吸收；写路径另有每用户滑动窗口频控兜底（service 常量）。
--   · distance_m 为端侧自报值：隐私红线决定服务端不复算（复算需要坐标），
--     防刷面 = 伪造 distance；缓解 = 桶幂等 + 频控 + admin 侧分布观察，
--     该信任边界的完整论证见 52 号文档 §「信任边界」。
--   · 时间戳由 Java 传 LocalDateTime.now()（JVM 北京时间），禁 DB now()
--     （V59/热度上报同款事故：DB 会话时区 UTC 会让窗口比较错位）。
--
-- ── 表设计说明 ─────────────────────────────────────────────────────────────
--   · BaseEntity 四列契约（id / created_at / updated_at / deleted）齐备；
--     created_at = 桶内首见时刻（ON DUPLICATE 不改写，保留首证），updated_at
--     = 桶内末次触发时刻。
--   · UNIQUE (user_id, venue_id, write_bucket)：全量唯一即可——本表无用户可见
--     删除语义，不引入软删复活场景，无需生成列部分唯一索引（V3 形态）。
--   · 索引：admin 按店聚合走 (venue_id, created_at)；异常排查/用户轨迹走
--     (user_id, created_at)。
--   · 无外键（全库约定）；枚举列无（本表无状态机）；无 CHECK（全库约定）。
-- ============================================================================

CREATE TABLE qwt_venue_presence_pings (
    id          bigint      NOT NULL AUTO_INCREMENT,
    created_at  datetime(6) NOT NULL COMMENT '桶内首见时刻（JVM 北京时间，首证保留）',
    updated_at  datetime(6) NOT NULL COMMENT '桶内末次触发时刻',
    deleted     tinyint(1)  NOT NULL DEFAULT 0,
    user_id     bigint      NOT NULL COMMENT '上报用户（qwt_users.id；requireAuth 保证非空归因）',
    venue_id    bigint      NOT NULL COMMENT '命中的门店（qwt_venues.id）',
    write_bucket bigint     NOT NULL COMMENT '写幂等桶 = floor(epochMinute / 15)，UTC epoch 派生与时区无关',
    distance_m  int         NOT NULL COMMENT '用户到该店的距离（米，端侧自报、来自 /venues/nearby 的服务端 Haversine 结果）',
    accuracy_m  int         NULL     COMMENT '端侧定位精度（米，wx.getLocation accuracy；缺省 = 端侧未提供）',
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_vp_user_venue_bucket UNIQUE (user_id, venue_id, write_bucket)
) COMMENT='门店到访痕迹（presence ping）：不落坐标的「可证实到店事实」，写宽松读严格';

-- admin 按店聚合（近 7/30 天到访 UV / 附近 UV）：(venue_id, created_at) 前缀扫描
CREATE INDEX qwt_idx_vp_venue_created ON qwt_venue_presence_pings (venue_id, created_at);

-- 用户维度排查（异常位移 / 单用户分布观察 / 未来「在店」打标回溯）
CREATE INDEX qwt_idx_vp_user_created ON qwt_venue_presence_pings (user_id, created_at);

-- ── 运营开关（V31 契约：迁移插默认行 + OpsConfigService 常量 + admin-web 登记）──
-- 仅开关进 opsconfig；命中半径/精度门槛/附近半径是统计口径参数（Java 协议常量，
-- 调整需发版并与 52 号文档同步），理由见 52 号文档 §「口径参数为什么不进 opsconfig」。
INSERT INTO qwt_ops_config (`key`, value, updated_by, updated_at)
VALUES ('presence.collect.enabled', 'true', NULL, now());
