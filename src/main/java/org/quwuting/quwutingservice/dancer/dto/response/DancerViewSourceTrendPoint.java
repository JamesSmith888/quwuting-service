package org.quwuting.quwutingservice.dancer.dto.response;

/**
 * 舞伴浏览来源单日趋势点（date + list/share/search/venue/other 分列，2026-08-14）。
 * <p>
 * 对齐门店 {@code ViewSourceTrendPoint} 的结构；list + share + search + venue + other =
 * 当日 viewTrend 值（同源可交叉验证）。other 由全量减分列推导（与门店同做法，
 * 省一次扫描）。2026-08-21 新增 venue 列（门店详情页「同城舞伴」入口进入——
 * 舞伴浏览来源图第四折线，其他/历史兜底仍不入图）。
 */
public record DancerViewSourceTrendPoint(
        /** 日期（yyyy-MM-dd，服务端已补零，31 天骨架含今日） */
        String date,
        /** 当日来源=LIST 浏览数（列表页进入，浏览来源图主序列） */
        long list,
        /** 当日来源=SHARE 浏览数（分享卡片打开，浏览来源图次序列） */
        long share,
        /** 当日来源=SEARCH 浏览数（搜索结果进入，浏览来源图第三序列） */
        long search,
        /** 当日来源=VENUE 浏览数（门店详情页同城舞伴入口进入，浏览来源图第四序列，
         *  2026-08-21 新增） */
        long venue,
        /** 当日来源=OTHER 浏览数（其他/历史兜底，前端不入图） */
        long other
) {
}
