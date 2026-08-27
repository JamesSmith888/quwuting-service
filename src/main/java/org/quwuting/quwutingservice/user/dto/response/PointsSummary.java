package org.quwuting.quwutingservice.user.dto.response;

/**
 * 积分账户概览（管理端用户详情内嵌，2026-08-27 用户管理增强）。
 * <p>
 * 数据源 = qwt_points_accounts 快照（balance/earnedTotal/spentTotal 是账务操作
 * 同步维护的冗余累计，见 PointsAccount javadoc）+ qwt_points_transactions 条数
 * （流水 = 余额唯一事实源，条数反映行为活跃度）。无账户用户恒 0（从未参与积分
 * 活动，调用方兜底，见 PointsAccount 注释）。
 */
public record PointsSummary(
        /** 当前余额（读写快照，恒 >= 0） */
        long balance,
        /** 累计获得 */
        long earnedTotal,
        /** 累计消费（赠送 + 解锁） */
        long spentTotal,
        /** 积分流水条数（行为活跃度信号） */
        long transactionCount
) {}
