-- ============================================================================
-- V34（MySQL 轨道 = db/migration-mysql）: 到店足迹「开关状态」流水表
--      （2026-09-29 四轮，授权模型改版；后端权威文档 = docs/agents/52-venue-presence.md）
--
-- ── 授权模型改版（用户三轮拍板，推翻 09-29 二轮的 opt-in 首问） ───────────────
-- ① **默认开启**：未做任何选择的用户直接采集（首问弹窗撤销）——「先问后采」
--    在默认开启模型下无意义（未询问 ≠ 未允许，允许是出厂态）；
-- ② 弹窗改为「手动开启提醒」：用户在「我的-设置」把开关拨到开启的**第一次**，
--    弹一次单按钮提醒（简单告知数据用途 + 介意则主动建议关闭），此后不再提醒
--    （notice 状态存端上，见采集端 52 号 §3.5）；
-- ③ **服务端可感知开关状态**（本轮新增）：admin 要统计「启用 / 关闭」分布，
--    而开关状态原本纯端上（二轮 §用户授权「服务端无感知」）——现在需要把每次
--    状态确立上报一份。本表即状态流水的落点。
--
-- ⚠️ 与隐私红线的关系：本表记录的是「用户偏好布尔值 + 来源」，**不含任何位置
-- 信息**，敏感度与「订阅通知偏好」同级；上报动作为 fire-and-forget，拒绝/失败
-- 静默。但「服务端开始感知用户偏好」这一步仍要在 52 号 §5 信任边界里如实登记
-- （consent 从「端上私有」升格为「服务端可观测」——是为了 admin 统计这个明确
-- 的产品需求，不是顺手采集）。
--
-- ── 建模：每行 = 一次「状态确立」，不是当前态快照 ───────────────────────────
--   · 保留变更流水（而非 upsert 当前态）才能同时回答三类问题：
--     当前分布（每用户最新一条）/ 从未手动改过的人数（最新 source=DEFAULT）/
--     近期变更热度（近 30 天 USER 行数）。行数上界 ≈ 用户数 × 变更次数，
--     开关是低频动作，量级千级行、无清理压力；
--   · source：DEFAULT = 默认态确立（第一次采集 ping 时该用户无任何 consent 行
--     则补一条 enabled=true——「默认开启人群」由此进入统计，无需用户做任何
--     动作）；USER = 用户在「我的-设置」手动拨动开关；
--   · 默认态补写用 INSERT ... SELECT ... WHERE NOT EXISTS 单语句（最少 DB 往返
--     约束；并发窗口可能双写 DEFAULT 行，无唯一约束——统计按「每用户最新一条」
--     口径天然吸收，双 true 行无害，不值得为它上唯一键）；
--   · 时间戳由 Java 传 LocalDateTime.now()（JVM 北京时间，禁 DB now()，全库口径）；
--   · 无外键（全库约定）；枚举列禁 CHECK（扩枚举免迁移）；
--   · enabled 用 tinyint(1)（布尔），Hibernate 侧 Boolean 映射。
-- ============================================================================

CREATE TABLE qwt_venue_presence_consents (
    id          bigint      NOT NULL AUTO_INCREMENT,
    created_at  datetime(6) NOT NULL COMMENT '状态确立时刻（JVM 北京时间）',
    updated_at  datetime(6) NOT NULL COMMENT '同 created_at（流水行不再更新）',
    deleted     tinyint(1)  NOT NULL DEFAULT 0,
    user_id     bigint      NOT NULL COMMENT '用户（qwt_users.id）',
    enabled     tinyint(1)  NOT NULL COMMENT '确立后的开关状态（true=采集开启）',
    source      varchar(16) NOT NULL COMMENT '确立来源：DEFAULT=默认态确立 / USER=用户手动变更',
    PRIMARY KEY (id)
) COMMENT='到店足迹开关状态流水（每行一次状态确立；当前态 = 每用户最新一条）';

-- 「每用户最新一条」扫描（admin 分布统计的窗口函数按 user_id 分区）
CREATE INDEX qwt_idx_vpcons_user_created ON qwt_venue_presence_consents (user_id, created_at);

-- 近 N 天变更次数 / 后续按时间清理的扫描
CREATE INDEX qwt_idx_vpcons_created ON qwt_venue_presence_consents (created_at);
