package org.quwuting.quwutingservice.venuereaction.dto.response;

import java.util.List;

/**
 * 场所 Reaction 完整统计响应（GET /venues/{id}/reactions/stats）。
 * reactions 恒含字典内全部 Reaction（按枚举声明顺序），无数据时计数为 0——
 * 与 TagStatsResponse.dimensionScores 的"始终全量返回"约定一致。
 */
public record ReactionStatsResponse(List<ReactionStat> reactions) {}
