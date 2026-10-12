-- ============================================================================
-- V48（MySQL 轨道 = db/migration-mysql）: 计时「给同行者点赞」（2026-10-12）
--      （后端权威文档 = docs/agents/54-timer-share.md §十五；
--        前端 = quwuting 仓 docs/agents/59-timer-share.md §十六）
--
-- ⚠️ 编号说明：取号前已核对 db/migration-mysql 最大号 = V47（并行工作流会抢号，V41 就被两条抢过）；
--    部署前须再核对无第二份 V48（Flyway 对重复版本号直接拒启）。PG 遗留轨道编号各自独立、勿混用。
--
-- ── 需求 ────────────────────────────────────────────────────────────────────
-- 一场二维码同步的计时里，主持方与加入者可以对彼此点一个赞——结算时顺手致意（用户 2026-10-12 拍板
-- 「按推荐方式完成一版，不用过分考虑审核风险，有问题再下线」）。
--
-- ── 表：qwt_timer_share_likes ───────────────────────────────────────────────
-- 一行 = 某张分享会话里「A 赞了 B」。
--   · 唯一键 (share_id, liker_id, liked_user_id)：每对至多一行。取消 = 软删（deleted=1），再赞 = 原行复活
--     （与 qwt_venue_crowd_report_likes 同款 toggle：被赞通知只在「首次赞」触发一次，由 INSERT 的受影响行数
--     派生，不需要标志列）。
--   · code varchar(16)：现在恒为 'LIKE'。唯一键是一对一，所以将来要放开表情是「同一行换码」（对齐快讯表态的
--     一人一条恒一个），不是加表也不是改键。枚举类列禁 CHECK（扩枚举免迁移）。
--   · liker_id / liked_user_id：命名里的 liker_id 与热度点赞同名——管理端行为目录（UserBehaviorEvent）的
--     「用户列」归因口径，门禁 everyUserAttributedTableIsClassified 以此要求登记。liked_user_id **仅服务端
--     内部使用，不下发客户端**（54 号 §资料互看的边界：不给跨会话串联同一个人留通道）。
--   · 无外键（全库约定）、不写 DB now()、时间列为 datetime(6) 业务时刻由 Java 写入。
--
-- ── 🚫 红线（写进表注释，防日后被人"顺手"加上）─────────────────────────────────
-- 赞无任何经济价值、永不进算法：不产生积分、不累计到人（所以这里**没有**按被赞者聚合的计数列，也不建
-- 以 liked_user_id 打头的索引）、不进榜、不进任何主页。读路径只有两个：会话内「我面前每一位」的点查，
-- 与被赞通知的按 (share_id, liked_user_id) 计数——都被唯一索引的 share_id 前缀覆盖，且一张会话最多
-- 1 主持方 + 5 加入者，行数天然 ≤ 6 × 5。
--
-- ── 运营开关 timer.share.like.enabled（默认 true）────────────────────────────
-- 计时二维码同步本身 2026-10-07 被裁决删掉了开关（V43：「功能正常可用」不是开关）。但对人点赞是 29 号
-- 舞伴交友服务驳回史里被点名的「认可 / 表情」的单键简化版，是整个计时域残余审核风险最高的一面——
-- 符合 16 号文档的判据「需要一键停掉一整类用户可见行为」，所以它（而且只有它）配一个开关。
-- 默认 true，且代码侧缺键也按 true（OpsConfigService#isEnabled(KEY, true)），所以不存在
-- 「忘了开 ⇒ 功能静默不可用」的失败模式。关闭 = 入口整个隐藏 + 写入口返回 DISABLED + 不再发被赞通知，
-- 历史数据保留。
--
-- ── 部署顺序 ────────────────────────────────────────────────────────────────
-- 后端先于小程序（纯增量：新表 + 新接口 + 一行配置；旧客户端不知道这些接口，行为零变化）。
-- 注意：`key` 是 MySQL 保留字，INSERT 列清单里必须反引号（V1 baseline 第 5 条契约）。
-- ============================================================================

CREATE TABLE qwt_timer_share_likes (
    id            bigint      NOT NULL AUTO_INCREMENT,
    created_at    datetime(6) NOT NULL COMMENT '首次赞的时刻（JVM 本地时间）',
    updated_at    datetime(6) NOT NULL COMMENT '最近一次赞 / 取消的时刻',
    deleted       tinyint(1)  NOT NULL DEFAULT 0 COMMENT '1 = 已取消赞（软删 toggle；再赞复活原行）',
    share_id      bigint      NOT NULL COMMENT '分享会话（qwt_timer_shares.id）',
    liker_id      bigint      NOT NULL COMMENT '点赞者（qwt_users.id）',
    liked_user_id bigint      NOT NULL COMMENT '被赞者（qwt_users.id）；仅服务端内部使用，不下发客户端',
    code          varchar(16) NOT NULL DEFAULT 'LIKE' COMMENT '行内码；现在恒为 LIKE，将来放开表情 = 同一行换码',
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_tsl_share_pair UNIQUE (share_id, liker_id, liked_user_id)
) COMMENT='计时同步里给同行者点赞：一行 = 某张会话里 A 赞了 B。无经济价值、永不进算法、不累计到人';

INSERT INTO qwt_ops_config (`key`, value, updated_by, updated_at)
VALUES ('timer.share.like.enabled', 'true', NULL, now());
