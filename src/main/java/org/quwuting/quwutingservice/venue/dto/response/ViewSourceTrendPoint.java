package org.quwuting.quwutingservice.venue.dto.response;

/**
 * 单日浏览来源趋势点（date + 三种来源计数，2026-08-13 新增「浏览来源」统计图）。
 * <p>
 * 与 {@link FavoriteTrendPoint}（单值）不同：来源图是同一日期轴上多条序列的对比，
 * 故按来源分列。list + share + other = 当日浏览总量（与 viewTrend 同口径，可交叉验证）；
 * other = 搜索/收藏/深链等其他来源（前端图上只画 list/share 两条折线，other 供口径校验）。
 */
public record ViewSourceTrendPoint(
        /** 日期，ISO 格式 yyyy-MM-dd */
        String date,
        /** 当日来源=LIST（列表页进入）浏览数 */
        long list,
        /** 当日来源=SHARE（分享卡片打开）浏览数 */
        long share,
        /** 当日来源=OTHER（其他/历史兜底）浏览数 */
        long other
) {
}
