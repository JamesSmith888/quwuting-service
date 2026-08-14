package org.quwuting.quwutingservice.dancer.dto.response;

/**
 * 舞伴统计单日趋势点（date + count，2026-08-14 舞伴统计图第一期）。
 * <p>
 * 对齐门店 {@code FavoriteTrendPoint} 的结构（date + count）；舞伴域独立定义
 * 避免跨域依赖（能力平权：每域一套统计响应，同模式不同包）。
 */
public record DancerTrendPoint(
        /** 日期（yyyy-MM-dd，服务端已补零，31 天骨架含今日） */
        String date,
        /** 当日计数（认可数 / 新增收藏数 / 收到礼物价值 / 分享次数 / 浏览数） */
        long count
) {
}
