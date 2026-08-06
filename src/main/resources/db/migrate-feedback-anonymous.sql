-- ============================================================================
-- qwt_venue_feedbacks.user_id NOT NULL 约束迁移（2026-08-06 匿名上报）
-- ============================================================================
-- 背景（根因，详见 AGENTS.md「Schema 演进 → 无法避免的场景」）：
--   实体 VenueFeedback 在"统一上报模板"时期 user_id 为 NOT NULL（强制登录），
--   2026-08-06 决策支持匿名上报（上报不强推登录）：未登录用户直接提交，
--   userId = null（trackable = false），登录用户 userId 落库可在个人中心回看
--   处理结果。
--
--   ddl-auto:update 只新增缺失列/约束、从不把现有列改为可空（MODIFY 列约束
--   不在 update 的能力范围），因此 user_id 放宽可空必须一次性手动 SQL——
--   这是「无法避免的场景」，与 migrate-drop-liked-not-null.sql 同模式。
--
-- 执行时机：dev / prod 上线前各执行一次（幂等可重复：重复执行无副作用）。
--   注意：本脚本必须在"实体已放宽为可空"的版本启动前执行——
--   若先启动新版应用，匿名 insert 会违反 NOT NULL 报错
--   （"null value in column \"user_id\" violates not-null constraint"）。
-- ============================================================================

-- 1) 取消 NOT NULL（幂等：重复执行无副作用）
ALTER TABLE qwt_venue_feedbacks ALTER COLUMN user_id DROP NOT NULL;

-- 2) 验证（期望输出 is_nullable = YES）
-- SELECT column_name, is_nullable, data_type
-- FROM information_schema.columns
-- WHERE table_name = 'qwt_venue_feedbacks' AND column_name = 'user_id';
