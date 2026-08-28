-- V58__demand_feedback_handled.sql
-- 客人反馈 → 管理端待办闭环（2026-08-28，docs/agents/25「反馈闭环 · 管理端可见性修复」）
--
-- 背景（根因）：V56 客人反馈（guest_feedback + feedback_requested_at）落库成功，
-- 但管理端邀约工作台查询范围 = 仅开启中转（contact_relay=true）舞伴 + PENDING 状态，
-- 而反馈发生在【非中转舞伴的已发放/存量邀约】上（生产实证：feedback_requested_at
-- 非空的邀约 100% 非中转）——数据集合与工作台查询范围不相交，管理端零可见。
--
-- 本迁移：guest_feedback_handled_at = 反馈"已核实"时间戳（NULL = 待处理待办）。
-- 反馈从"一次性标记"升级为"待办生命周期"（待处理 → 已核实）：
--  - pending-count 计入未核实反馈（me 页「邀约工作台」入口红点）
--  - 工作台待处理视图并入反馈待办（全舞伴范围，按 feedback_requested_at 倒序浮顶）
--  - 管理员微信侧核实后 POST /admin/demands/{id}/feedback-handled 置位（幂等）
--
-- 部分索引：待办扫描只关心未核实反馈（guest_feedback_handled_at IS NULL），
-- 覆盖 countPendingFeedback + findPendingFeedback 的排序（feedback_requested_at DESC）。

ALTER TABLE qwt_demand_records
    ADD COLUMN guest_feedback_handled_at timestamp(6);

CREATE INDEX idx_qwt_demand_records_feedback_pending
    ON qwt_demand_records (feedback_requested_at DESC)
    WHERE guest_feedback_handled_at IS NULL;
