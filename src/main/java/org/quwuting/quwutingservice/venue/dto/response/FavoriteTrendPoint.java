package org.quwuting.quwutingservice.venue.dto.response;

/**
 * 收藏趋势单日数据点（热度 Tab 收藏趋势图用）。
 *
 * @param date  日期，ISO 格式 yyyy-MM-dd（前端自行格式化展示）
 * @param count 当日新增收藏数，无收藏时为 0（服务端已补零，保证连续时间轴）
 */
public record FavoriteTrendPoint(
        String date,
        long count
) {}
