package org.quwuting.quwutingservice.taginteraction.dto.response;

/**
 * 单个评分维度的统计（含时间窗口）。
 * <p>
 * avgScore 保留一位小数，count < 1 时为 null（无评分数据）。
 * recent30d / recent7d 为时间窗口内的聚合，结构复用 WindowScore。
 * myScore 为当前用户的最新评分（未登录或未评分时为 null）。
 *
 * @param tag       维度名称
 * @param avgScore  全部时间平均分（1-10，一位小数），无数据时 null
 * @param count     全部时间评分人数
 * @param myScore   当前用户评分
 * @param recent30d 近 30 天聚合
 * @param recent7d  近 7 天聚合
 */
public record DimensionScoreStats(
        String tag,
        Double avgScore,
        long count,
        Integer myScore,
        WindowScore recent30d,
        WindowScore recent7d
) {}
