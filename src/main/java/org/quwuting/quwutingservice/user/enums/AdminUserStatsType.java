package org.quwuting.quwutingservice.user.enums;

/**
 * 管理端用户统计明细类型（2026-08-28，GET /admin/users/{id}/stats-detail，
 * docs/agents/23；仅 ADMIN）——用户详情页统计数据点击下钻的明细维度。
 * <p>
 * 覆盖详情页全部可下钻统计：积分流水（POINTS，可选 mode=EARN/GIFT 过滤；上报采纳 =
 * REPORT_REWARD = 积分流水中 source_type ∈ 采纳来源）/ 打卡（CHECKIN）/ 认可舞伴
 * （RECOGNITION）/ 认领（CLAIM，可选 status 过滤）/ 分享（SHARE）/ 收藏舞伴
 * （FAVORITE）/ 需求单（DEMAND，可选 status 过滤）/ 上报（REPORT，信息反馈 +
 * 状态报告合并，可选 status 过滤）。非法值 → Controller 400。
 */
public enum AdminUserStatsType {
    /** 积分流水（全部；可选 mode=EARN/GIFT 过滤，与用户积分页同语义） */
    POINTS,
    /** 上报采纳流水（贡献档案「上报采纳」维度：积分流水中 source_type ∈ 采纳来源） */
    REPORT_REWARD,
    /** 每日打卡记录（日期列表） */
    CHECKIN,
    /** 认可舞伴记录 */
    RECOGNITION,
    /** 门店认领记录（可选 status=ClaimStatus code 过滤） */
    CLAIM,
    /** 分享动作记录（门店分享 + 舞伴分享合并，仅 SHARE 事件） */
    SHARE,
    /** 舞伴收藏记录 */
    FAVORITE,
    /** 需求单记录（可选 status=DemandStatus code 过滤；存量 NULL 归 APPROVED） */
    DEMAND,
    /** 上报记录（信息反馈 + 暂停营业报告合并；status=PENDING 跨表匹配） */
    REPORT
}
