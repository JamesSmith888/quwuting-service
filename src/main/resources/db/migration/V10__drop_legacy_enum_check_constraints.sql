-- ============================================================================
-- V10: 清理 Hibernate 遗留枚举 CHECK 约束（扩枚举不再需要 DB 迁移）
--
-- 背景（2026-08-10 生产事故）：管理端「采纳上报」写入 status='ADOPTED' 报
-- DataIntegrityViolationException，违反 qwt_venue_feedbacks_status_check。
-- 根因：Flyway 引入前（ddl-auto:update 时代）Hibernate 为 @Enumerated(STRING)
-- 列自动生成 CHECK 约束（内容=当时枚举值全集），此后：
--   - 枚举扩值（ReportStatus 3 态 → 5 态，新增 ADOPTED/ADOPTED_NO_REWARD）
--     **不触碰**既有 CHECK（update 不改存量约束、validate 不校验约束表达式）；
--   - Flyway 管理的 schema 不声明也不维护这些约束（V1 baseline 起均无 CHECK），
--     只有 Hibernate 时代的存量表残留——扩枚举即写库失败，且 validate 期不可见，
--     只在首次真实写入时爆炸（本次事故，详见 AGENTS.md「Schema 演进」）。
--
-- 处理：DROP 全部 4 个 Hibernate 遗留枚举 CHECK 约束——它们既不在 V1 baseline
-- 权威结构内（新环境本就没有），也无法随枚举演进自动同步，唯一作用是埋雷。
-- 枚举值合法性由应用层 Jackson 反序列化 + 实体枚举映射把关（与 type/field 等
-- 其他枚举列同模式）。本次一并清理同类隐患，杜绝「扩枚举=改库」的隐性契约。
--
-- 幂等性：DROP CONSTRAINT IF EXISTS，对无约束的新环境零影响（no-op）。
-- ============================================================================

-- 上报处理状态（ReportStatus：PENDING / ADOPTED / ADOPTED_NO_REWARD /
-- RESOLVED / DISMISSED——2026-08-10 管理端三动作定稿扩为 5 态）
ALTER TABLE qwt_venue_feedbacks
    DROP CONSTRAINT IF EXISTS qwt_venue_feedbacks_status_check;

-- 舞伴资料状态（DancerStatus：NORMAL / HIDDEN / PENDING）
ALTER TABLE qwt_dancers
    DROP CONSTRAINT IF EXISTS qwt_dancers_status_check;

-- 舞伴-场所关系类型（DancerVenueRelation：HOME / APPEARANCE）
ALTER TABLE qwt_dancer_venues
    DROP CONSTRAINT IF EXISTS qwt_dancer_venues_relation_check;

-- 分享事件类型（ShareEventType：SHARE / OPEN）
ALTER TABLE qwt_venue_shares
    DROP CONSTRAINT IF EXISTS qwt_venue_shares_event_type_check;
