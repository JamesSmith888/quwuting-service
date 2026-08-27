package org.quwuting.quwutingservice.user.dto.response;

/**
 * 上报概览（管理端用户详情内嵌，2026-08-27 用户管理增强）。
 * <p>
 * 数据源 = 门店信息上报（qwt_venue_feedbacks，未软删且 user_id 非空——匿名上报
 * 无法归属）+ 暂停营业报告（qwt_venue_status_reports，未软删）合并计数。
 * <p>
 * 语义：上报是用户社区共建的高信号行为——总数反映参与度，待处理数反映
 * 运营积压与上报质量（PENDING = 待管理员核实；采纳奖励与状态流转同事务，
 * 见 VenueFeedbackService）。采纳数见 ContributionBrief.reportedCount
 * （贡献档案维度，与积分流水同源，此处不重复）。
 */
public record ReportSummary(
        /** 上报总数（信息上报 + 暂停营业报告） */
        long total,
        /** 待处理数（PENDING 未核实） */
        long pending
) {}
