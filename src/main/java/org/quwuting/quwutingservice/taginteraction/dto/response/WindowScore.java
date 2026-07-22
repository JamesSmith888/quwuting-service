package org.quwuting.quwutingservice.taginteraction.dto.response;

/**
 * 时间窗口内的评分聚合。
 *
 * @param avgScore 窗口内平均分（一位小数），无数据时 null
 * @param count    窗口内评分人数
 */
public record WindowScore(
        Double avgScore,
        long count
) {}
