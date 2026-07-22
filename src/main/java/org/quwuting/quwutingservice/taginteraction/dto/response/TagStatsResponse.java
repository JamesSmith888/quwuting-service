package org.quwuting.quwutingservice.taginteraction.dto.response;

import java.util.List;

/**
 * 标签交互统计响应（GET /venues/{id}/tag-stats）。
 * <p>
 * tagLikes：各描述性标签的点赞统计（仅当前存在于场所 tags 中的标签）。
 * dimensionScores：各系统评分维度的评分统计（含时间窗口），所有维度始终返回（无数据时 count=0）。
 * dimensions：系统评分维度名称列表（前端据此渲染评分区，后端新增维度时前端自动同步）。
 */
public record TagStatsResponse(
        List<TagLikeStats> tagLikes,
        List<DimensionScoreStats> dimensionScores,
        List<String> dimensions
) {}
