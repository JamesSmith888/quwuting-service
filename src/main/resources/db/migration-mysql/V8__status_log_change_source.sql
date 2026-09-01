-- V8：状态变更日志增加「变更来源」标识（2026-09-01，Agent+Skill 批量落库审计）
--
-- 背景：Agent+Skill（quwuting-venue-daily-sync）通过 /admin/venue-daily-openings/batch
-- 批量反转门店状态时，VenueStatusLog.changed_by 恒为 null（null = 系统/Agent 来源，
-- 人工编辑 = userId）。管理后台「更新记录」（GET /admin/venue-sync/reversals）只能区分
-- 系统反转 vs 人工编辑，无法区分「Agent 批量更新」与「其他系统自动变更」。
--
-- 本迁移新增 change_source 列，显式标注变更来源：
--   * AGENT_BATCH —— Agent+Skill 批量落库（status-reverse 通道，sourceId=xianbao360）
--   * ADMIN       —— 管理端人工写库（Web 后台 apply/apply-item/apply-selected）
--   * null        —— 旧数据 / 其他系统自动变更（不强制回填，向前兼容）
--
-- 写入方：DailyOpeningService.applyBatch 从 ApplyDailyOpeningRequest.source 透传；
-- 展示方：VenueReversalRecord.changeSource → 管理后台「更新记录」页展示「批量更新」标识。
-- 枚举以 varchar 存储（对齐 V1 baseline 风格，禁 CHECK，扩枚举免迁移）。

ALTER TABLE qwt_venue_status_logs
    ADD COLUMN change_source varchar(20) NULL COMMENT '变更来源：AGENT_BATCH Agent批量 / ADMIN 管理端人工 / NULL 旧数据或其他系统';
