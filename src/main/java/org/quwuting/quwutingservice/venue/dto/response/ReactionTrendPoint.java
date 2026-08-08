package org.quwuting.quwutingservice.venue.dto.response;

/**
 * 单日 Reaction 趋势数据点（热度页「反馈趋势」图用，2026-08-08 新增）。
 * <p>
 * 正/负向分列——反应 2026-08 确立的 Reaction 分极性业务语义（见
 * {@link org.quwuting.quwutingservice.venuereaction.ReactionCode.Polarity}）：
 * POSITIVE 计入热度公式、NEGATIVE 不计入公式、单独计数展示负面信号。
 * 趋势图正负向并排呈现，用户一瞥即可看出"最近口碑在变好还是变差"。
 *
 * @param date     日期，ISO 格式 yyyy-MM-dd（前端自行格式化展示）
 * @param positive 当日正向反馈数（无记录时为 0，服务端已补零）
 * @param negative 当日负向反馈数（无记录时为 0，服务端已补零）
 */
public record ReactionTrendPoint(
        String date,
        long positive,
        long negative
) {}
