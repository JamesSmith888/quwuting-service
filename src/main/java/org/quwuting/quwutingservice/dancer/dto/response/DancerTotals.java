package org.quwuting.quwutingservice.dancer.dto.response;

/**
 * 舞伴累计指标（2026-08-22 追加，{@link DancerStatsResponse#totals()}）。
 * <p>
 * 全量历史口径（非近30天窗口）的常见指标汇总——驱动舞伴统计页顶部「累计数据」
 * 汇总卡（总收藏数 / 总浏览数等一览）。各字段与趋势序列同源同口径，仅窗口不同
 * （累计 = 全量，趋势 = 近30天补零骨架）：
 * <ul>
 *   <li>认可：qwt_dancer_recognitions 行数（每日一记，deleted=false，与
 *       recognitionTrend 同源）；</li>
 *   <li>收藏：qwt_dancer_favorites 行数（deleted=false，与 favoriteTrend 同源）；</li>
 *   <li>浏览：qwt_dancer_views 行数（按天按来源去重 PV 含匿名，与 viewTrend 同源）；</li>
 *   <li>分享：qwt_dancer_shares SHARE 主动分享事件行数（与 shareTrend 同源）；</li>
 *   <li>礼物价值：qwt_points_transactions SUM(-delta)（target_type='DANCER' 收礼，
 *       与 pointsTrend 同源）。</li>
 * </ul>
 * 可见范围沿用统计接口既有契约（仅本人 + 管理员，见 DancerController#getStats）。
 */
public record DancerTotals(
        /** 累计认可数（每日一记，deleted=false） */
        long recognitionCount,
        /** 总收藏数（deleted=false） */
        long favoriteCount,
        /** 累计浏览数（PV 含匿名，按天按来源去重；与 viewTrend 同源全量口径） */
        long viewCount,
        /** 累计分享数（event_type='SHARE' 主动分享事件，不含 OPEN 回流） */
        long shareCount,
        /** 收到礼物价值累计（SUM(-delta)，礼物价值=积分价值） */
        long pointsReceivedTotal
) {
}
