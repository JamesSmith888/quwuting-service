-- ============================================================================
-- V36: 恢复存量「已采纳」突发事件记录的可见性（采纳 = 处置标记而非删除行）
--
-- 背景（2026-08-20 修正，根因见 AGENTS.md「门店突发事件列表」）：
-- 旧实现 adoptReport 对采纳采用 soft delete（deleted=true + admin_action='ADOPTED'），
-- 而公开明细「最近的突发事件」（findRecentByVenue）只查 deleted=false 的记录——
-- 管理员采纳后记录从列表消失，用户误以为"上报记录被删除"（实际需求：采纳只重置
-- 用户侧状态为「待报告」，上报记录应保留展示并带"已核实"标注）。
--
-- 新语义：采纳 = 仅置 admin_action='ADOPTED'（不删行）；「活跃」判定 = deleted=false
-- AND admin_action IS NULL AND expires_at > now()（hasMyReport / 热度计数 / 管理端
-- 队列 / 提交摘要全部排除已处置记录——用户侧重置为「待报告」，可再次上报产生新行）。
--
-- 本迁移把历史已被采纳（deleted=true AND admin_action='ADOPTED'）的记录恢复
-- deleted=false，使其重新出现在明细列表（带"已核实"标注）；移除（REMOVED）记录
-- 保持软删（虚假信号清理，公开视图不展示）。
-- 幂等：仅影响存量数据，Flyway 按版本执行一次。
-- ============================================================================

UPDATE qwt_venue_status_reports
SET deleted = false,
    updated_at = now()
WHERE deleted = true
  AND admin_action = 'ADOPTED';
