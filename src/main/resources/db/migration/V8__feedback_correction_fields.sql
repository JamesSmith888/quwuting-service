-- ============================================================================
-- V8: 用户上报结构化纠错载荷（2026-08-10，根因见 AGENTS.md「统一用户上报 → 结构化纠错载荷」）
--
-- 背景：门店数据经 OCR 批量导入，票价/电话/营业时间/地址等字段系统性错误，
-- 只能靠终端用户发现。旧上报载荷只有自由文本 note（"哪里错了"与"正确值"混在
-- 一起），管理端无法机器可读地核对纠错建议。本迁移为统一上报模板新增两个可空
-- 结构化列：
--   ① field varchar(30)：纠错目标字段（INACCURATE 类型使用，受控词汇表见
--      FeedbackField 枚举；其余类型不填）
--   ② corrected_value varchar(500)：用户认为正确的数据（与 field 配套）
-- 可空列直接 ADD COLUMN，存量行自动为 NULL（「Schema 演进」规则 2）。
--
-- 唯一索引拆分（去重单位升级，根因）：
-- V2 的唯一索引 (user_id, venue_id, type) WHERE status='PENDING' 的去重单位是
-- "type"——但字段级纠错的语义单位是 "(type, field)"：同一用户对同一场所报两个
-- 不同字段的错误（如门票价格 + 联系电话），第二条 INACCURATE 会撞旧索引被幂等
-- 吞掉，字段级纠错无法表达。本迁移将旧索引拆为两条部分唯一索引：
--   ① (user_id, venue_id, type, field) WHERE field IS NOT NULL——每字段一条
--      PENDING（同字段重复提交仍去重，跨字段互不阻塞）；
--   ② (user_id, venue_id, type) WHERE field IS NULL——非纠错场景（缺失/状态/
--      其他，field 不填）保持 V2 原语义不变。
-- 存量行 field 均为 NULL，只落索引②；索引①从空集开始，无重复冲突，无需清理。
--
-- 幂等：Flyway 按版本只执行一次；DROP INDEX IF EXISTS / CREATE UNIQUE INDEX
-- IF NOT EXISTS 均为防御性写法。
-- ============================================================================

-- ① 新增结构化纠错列（可空，无需默认值）
ALTER TABLE qwt_venue_feedbacks ADD COLUMN field varchar(30);
ALTER TABLE qwt_venue_feedbacks ADD COLUMN corrected_value varchar(500);

-- ② 拆分 V2 唯一索引：去重单位由 type 升级为 (type, field)
DROP INDEX IF EXISTS qwt_uk_feedbacks_user_venue_type_pending;

-- 纠错场景（field IS NOT NULL）：每 (user, venue, type, field) 一条 PENDING；
-- 冲突由应用层 catch DataIntegrityViolationException（SQLState 23505）按
-- (user, venue, type, field) 回查已有记录幂等返回。
CREATE UNIQUE INDEX IF NOT EXISTS qwt_uk_feedbacks_user_venue_type_field_pending
    ON qwt_venue_feedbacks (user_id, venue_id, type, field)
    WHERE user_id IS NOT NULL AND status = 'PENDING' AND field IS NOT NULL;

-- 非纠错场景（field IS NULL）：保持 V2 原语义（每 (user, venue, type) 一条 PENDING）
CREATE UNIQUE INDEX IF NOT EXISTS qwt_uk_feedbacks_user_venue_type_pending
    ON qwt_venue_feedbacks (user_id, venue_id, type)
    WHERE user_id IS NOT NULL AND status = 'PENDING' AND field IS NULL;
