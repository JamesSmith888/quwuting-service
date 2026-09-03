package org.quwuting.quwutingservice.points.enums;

/**
 * 积分挣取来源（可扩展：新增挣取场景 = 加枚举 + 对应发放逻辑）。
 * <p>
 * 幂等键 = (user_id, source_type, source_id)：同一用户同一来源只发一次分，
 * 由 qwt_points_transactions 的部分唯一索引（delta > 0 AND source_id IS NOT NULL）
 * 在库内兜底（SQLState 23505 按并发幂等吞掉，与 feedback/reaction 同模式）。
 */
public enum PointsSourceType {
    /** 每日打卡，source_id = qwt_daily_checkins.id */
    DAILY_CHECK_IN,
    /** 上报被管理员采纳（ADOPTED），source_id = qwt_venue_feedbacks.id */
    FEEDBACK_REWARD,
    /**
     * 暂停营业报告被管理员采纳（2026-08-10 新增，source_id = qwt_venue_status_reports.id）。
     * 采纳 = 管理员核实暂停属实并标记门店 SUSPENDED；奖励与采纳同事务、幂等
     * （(user, source_type, source_id) 部分唯一索引兜底，与 FEEDBACK_REWARD 同模式）。
     */
    STATUS_REPORT_REWARD,
    /** 管理端人工调整（可正可负，纠正误发/惩罚刷分），source_id = 操作管理员 id */
    ADMIN_ADJUST,
    /** 赠送（用户消费动作，delta < 0，必带 target_type + target_id；source_id 空） */
    GIFT,
    /**
     * 积分解锁内容（2026-08-14 新增：照片/联系方式等门槛目标，delta < 0，
     * 必带 target_type + target_id = PointsGateTargetType 目标）。
     * 与 GIFT 的差异：GIFT 是"购买礼物送出"（资产不转移但产生接收方展示），
     * UNLOCK 是"单向燃烧换取查看权"——积分不进任何接收方账户（合规红线：
     * 不可流转准货币，见 AGENTS.md「积分系统 · 积分解锁」），故不参与
     * receivedTotal（收到积分）聚合。
     */
    UNLOCK,
    /**
     * 解锁返还（2026-08-27 新增，V56，docs/agents/25「反馈闭环」；delta > 0，
     * source_id = 邀约 id = qwt_demand_records.id）——客人对已解锁邀约提交
     * 「没加上 TA？」反馈后，自动返还该邀约解锁时的原扣费积分（拿回自己花的
     * 分，无净收益可刷；幂等键 (user, UNLOCK_REFUND, demandId) 保证一次反馈
     * 只返还一次）。免费解锁（无扣费流水）无返还，不写本来源流水。
     */
    UNLOCK_REFUND,
    /**
     * 意见反馈被管理员采纳（2026-08-28 新增，source_id = qwt_app_feedbacks.id）。
     * 与 FEEDBACK_REWARD 的差异：那是门店维度上报（venue_feedbacks），本来源是
     * 平台级意见反馈（BUG/建议/夸奖）——两表 id 各自自增可能相同，幂等键必须
     * 分来源（(user, APP_FEEDBACK_REWARD, appFeedbackId)），否则跨表撞键会漏发。
     * 奖励金额与 FEEDBACK_REWARD 同池（app.points.feedback-reward，用户心智一致）。
     */
    APP_FEEDBACK_REWARD,
    /**
     * 今晚热度上报被舞友确认（2026-09-03 新增，source_id = qwt_venue_crowd_reports.id，
     * docs/agents/27-venue-crowd-report.md「确认后积分」）：6h 窗口内该店 ≥3 人档位
     * 一致（众包互认，非管理员人工采纳）时发放给「与众数一致」的上报者，奖励与
     * 信号质量对齐（刷分必须报真）；幂等键 = 上报行 id（每行至多一次，同日改档位
     * 再确认不重复发）。金额独立键 app.points.crowd-confirm-reward（默认 3，低于
     * 人工采纳 5——确认是同级互认而非权威认定）。计入贡献档案「上报采纳」维度
     * （ContributionService.REPORT_SOURCE_TYPES），进而加速可信度权重（资深/常客）。
     */
    CROWD_CONFIRMED
}
