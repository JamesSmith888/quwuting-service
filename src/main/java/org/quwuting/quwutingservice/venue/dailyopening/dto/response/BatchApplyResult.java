package org.quwuting.quwutingservice.venue.dailyopening.dto.response;

import java.util.List;

/**
 * 批量应用快照的结果统计（幂等语义下可重复返回）。
 *
 * @param total            提交总数
 * @param snapshotApplied  已写入/更新快照条数（幂等 upsert）
 * @param statusReversed   状态反转数（CEASED/SUSPENDED → OPEN，仅 EXACT/ALIAS 触发）
 * @param venueNotFound    门店不存在被跳过的条数
 * @param reversals        反转明细（审计/回滚用）
 */
public record BatchApplyResult(
        int total,
        int snapshotApplied,
        int statusReversed,
        int venueNotFound,
        List<ReversalDetail> reversals
) {
    /** 状态反转明细：门店 + 变更前后状态 + 匹配来源与置信度（回滚依据） */
    public record ReversalDetail(
            long venueId,
            String venueName,
            String fromStatus,
            String toStatus,
            String sourceId,
            String confidence
    ) {}
}
