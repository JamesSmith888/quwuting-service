package org.quwuting.quwutingservice.taginteraction.dto.response;

import java.util.List;

/**
 * 评分统计响应（GET /venues/{id}/tags/stats）。
 * <p>
 * dimensionScores：各系统评分维度的评分统计（含时间窗口），所有维度始终返回（无数据时 count=0）。
 * dimensions：系统评分维度名称列表（前端据此渲染评分区，后端新增维度时前端自动同步）。
 * <p>
 * 原 tagLikes（标签点赞统计）字段已随"标签点赞"功能移除，替代方案见
 * {@link org.quwuting.quwutingservice.venuereaction.dto.response.ReactionStatsResponse}
 * （GET /venues/{id}/reactions/stats）。
 */
public record TagStatsResponse(
        List<DimensionScoreStats> dimensionScores,
        List<String> dimensions
) {}

