-- ============================================================================
-- qwt_venue_reactions 「每日一记」模型迁移（2026-08）
-- ============================================================================
-- 背景（根因，详见 AGENTS.md「Reaction 快速反馈系统」）：
--   旧"toggle 软删 hold 模型"：一个用户对一个场所的一个 Reaction 至多一行，
--   取消 = 软删、再次参与 = 恢复并刷新 createdAt。该模型下取消可能作用于
--   createdAt 超窗的旧记录，导致近7天/近30天窗口计数无法在本地精确推导，
--   前端被迫使用"展示 countAll、排序 count30d"的双计数 hack；且"次日自动
--   恢复可点击状态（可再次 +1）"的需求语义无法表达（次日点击变成"取消"）。
--
--   新"每日一记"模型：每次点击 = 插入一条 reaction_date = 今天的记录（同一天
--   唯一），取消 = 物理删除当日记录（"取消当天 Reaction"语义）。四个窗口计数
--   的本地 ±1 全部精确（取消只作用于当日记录），列表页可默认展示近7天并支持
--   近7天/近30天/全部窗口切换。
--
-- 执行时机（重要）：
--   必须在应用启动（ddl-auto: update 会尝试加列）之前手动执行本脚本，
--   或在 Hibernate 已加出可空列后执行本脚本收尾（幂等，可重复执行）。
--   顺序不对时 Hibernate 可能对已有数据的表执行"加 NOT NULL 列"而失败。
--   生产环境（ddl-auto: validate）无需本脚本——validate 只校验表/列存在，
--   列与约束由本脚本保证。
--
-- 迁移步骤：
--   1. 物理删除旧模型的软删行（deleted=true）——新模型"取消即物理删除"，
--      旧软删行无保留价值（历史窗口统计不含已取消记录）
--   2. 新增 reaction_date 列并回填 created_at 的日期（旧行近似为"当日一次点击"）
--   3. 旧唯一约束 (user_id, venue_id, reaction_code) → 新唯一约束
--      (user_id, venue_id, reaction_code, reaction_date)
-- ============================================================================

BEGIN;

-- 1. 清理旧模型软删行（新模型下取消即物理删除，无软删概念）
DELETE FROM qwt_venue_reactions WHERE deleted = true;

-- 2. 新增列 + 回填（旧模型至多一行 per (user, venue, code)，回填后不会触发
--    新唯一约束冲突）
ALTER TABLE qwt_venue_reactions ADD COLUMN IF NOT EXISTS reaction_date date;
UPDATE qwt_venue_reactions SET reaction_date = created_at::date
    WHERE reaction_date IS NULL;
ALTER TABLE qwt_venue_reactions ALTER COLUMN reaction_date SET NOT NULL;

-- 3. 唯一约束：旧 → 新（含日期维度）
ALTER TABLE qwt_venue_reactions DROP CONSTRAINT IF EXISTS qwt_uk_vr_user_venue_code;
ALTER TABLE qwt_venue_reactions ADD CONSTRAINT qwt_uk_vr_user_venue_code_date
    UNIQUE (user_id, venue_id, reaction_code, reaction_date);

COMMIT;
