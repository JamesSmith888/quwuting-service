package org.quwuting.quwutingservice.venue.dailyopening.dto.response;

import java.util.List;

/**
 * 状态反转批量执行结果（2026-09-01 快照机制退出后：仅反转，无快照落库）。
 *
 * @param total          提交评估总数
 * @param statusReversed 状态反转数（CEASED/SUSPENDED → OPEN，仅可信来源触发）
 * @param venueNotFound  门店不存在被跳过的条数
 * @param skippedLocked  因「人工锁未过期」跳过数（2026-09-14，V25；人工优先，不写库）
 * @param skippedExempt  因「已豁免舞讯推断」跳过数（2026-09-14，V25）
 * @param skipped        跳过明细（按原因可读；调用方须显式汇报，见 {@link SkippedByGuardDetail}）
 * @param reversals      反转明细（审计/回滚用）
 */
public record BatchApplyResult(
        int total,
        int statusReversed,
        int venueNotFound,
        int skippedLocked,
        int skippedExempt,
        List<SkippedByGuardDetail> skipped,
        List<ReversalDetail> reversals
) {
    /** 状态反转明细：门店 + 变更前后状态 + 匹配来源与置信度 + 变更来源标识（回滚依据） */
    public record ReversalDetail(
            long venueId,
            String venueName,
            String fromStatus,
            String toStatus,
            String sourceId,
            String confidence,
            String source
    ) {}
}
