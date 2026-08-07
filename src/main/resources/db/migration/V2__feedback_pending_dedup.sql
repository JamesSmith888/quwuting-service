-- ============================================================================
-- V2: 用户上报防重复/防刷（2026-08-07，根因见 AGENTS.md「统一用户上报 → 防刷」）
--
-- 问题：qwt_venue_feedbacks 提交接口此前无任何去重/频控机制——登录用户连点
-- 重复插入、脚本可无限刷脏数据，而其余上报类接口（状态报告/浏览/分享/Reaction）
-- 均有唯一约束或频控。根因：feedback 泛化为统一上报模板时未对齐既有防刷模式。
--
-- 本迁移分两步（顺序敏感）：
-- ① 清理存量重复：对"登录用户 + 同一场所 + 同一类型 + 仍待处理"的记录组，
--    保留最早一条（最小 id），删除其余——不清理则唯一索引创建必然失败（此前
--    无防刷，线上可能存在重复 PENDING 记录）。匿名行（user_id IS NULL）不参与
--    （NULL 等值比较为 UNKNOWN，自连接天然跳过，另加显式 IS NOT NULL 防御）。
-- ② 建立部分唯一索引：仅 user_id IS NOT NULL 的行参与（匿名无法身份归因，
--    由应用层 60s IP 频控兜底）；仅 status = 'PENDING' 的行参与（管理员处理后
--    旧行移出索引，用户可对同一类型再次上报）。冲突由应用层 catch
--    DataIntegrityViolationException（SQLState 23505）幂等返回已有 PENDING 记录。
--
-- 幂等：Flyway 按版本只执行一次；DELETE 与 IF NOT EXISTS 均为防御性写法。
-- ============================================================================

-- ① 清理存量重复（保留每组最早一条）
DELETE FROM qwt_venue_feedbacks f
USING qwt_venue_feedbacks dup
WHERE dup.user_id IS NOT NULL
  AND dup.status = 'PENDING'
  AND dup.venue_id = f.venue_id
  AND dup.user_id = f.user_id
  AND dup.type = f.type
  AND dup.status = f.status
  AND dup.id > f.id;

-- ② 部分唯一索引：登录用户对同一场所同一类型在"待处理"期间只允许一条
CREATE UNIQUE INDEX IF NOT EXISTS qwt_uk_feedbacks_user_venue_type_pending
    ON qwt_venue_feedbacks (user_id, venue_id, type)
    WHERE user_id IS NOT NULL AND status = 'PENDING';
