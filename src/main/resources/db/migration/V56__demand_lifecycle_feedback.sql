-- ============================================================================
-- V56: 邀约生命周期与反馈闭环（2026-08-27，docs/agents/25-invite-lifecycle-and-feedback.md）
--
-- 根因（产品分析，25 号文档）：邀约单创建后即"死"——非中转舞伴（contact_relay=false，
-- 绝大多数）邀约 status 为 NULL，客人侧无状态可见、无履约入口、无反馈通道；
-- 平台不感知真实世界事件（添加成功/邀约被查看/被拒），客人拿到微信返回后
-- 感觉"邀约单消失、联系断了"。
--
-- 本迁移三个字段 = 三个系统动作（全部可空，存量记录零影响）：
--   ① share_opened_at      分享闭环自动化：舞伴打开邀约落地页（demand-invite）时
--                          置位（幂等只置一次）——客人侧「TA 已查看你的邀约」零操作
--                          可见，平台自动感知"分享生效"（无需客人确认）。
--   ② guest_feedback       客人反馈枚举 code（DemandGuestFeedback：ADD_FAILED 没加上 /
--                          REJECTED 被 TA 拒绝 / NO_REPLY 未回复 / OTHER 其他）——
--                          非中转舞伴被拒/没加上的唯一反馈通道，平台感知线下真实结果。
--   ③ feedback_requested_at 反馈提交时间（非空 = 已反馈；幂等只置一次，防重复
--                          提交重复返还积分——返还幂等键 = (user, UNLOCK_REFUND, demandId)）。
--
-- 隐私克制延续：只存枚举 code / 时间戳，不存自由文本。
-- ============================================================================
ALTER TABLE qwt_demand_records ADD COLUMN share_opened_at timestamp(6);
ALTER TABLE qwt_demand_records ADD COLUMN guest_feedback varchar(20);
ALTER TABLE qwt_demand_records ADD COLUMN feedback_requested_at timestamp(6);
