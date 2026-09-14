package org.quwuting.quwutingservice.venue.dailyopening.dto.response;

import java.util.List;

/**
 * 批量置「暂停营业」执行结果（2026-09-10，白名单口径反向写库）。
 * <p>
 * 与 {@link BatchApplyResult} 对称但语义相反：前者是「资讯 OPEN → 平台恢复营业」，
 * 本结果 = 「资讯未上榜 → 平台转暂停营业」。刻意独立成类（不共用 BatchApplyResult），
 * 避免 confidence/sourceId 语义混淆，也不扰动管理后台既有「可直接更新」链路。
 *
 * @param total         提交评估总数
 * @param suspended     实际置为暂停营业的门店数（仅 OPEN 参与，其余静默跳过）
 * @param venueNotFound 门店不存在/已删除被跳过的条数
 * @param skippedLocked 因「人工锁未过期」跳过数（2026-09-14，V25；人工优先，不写库）
 * @param skippedExempt 因「已豁免舞讯推断」跳过数（2026-09-14，V25）
 * @param skipped       跳过明细（按原因可读；调用方须显式汇报，见 {@link SkippedByGuardDetail}）
 * @param details       暂停明细（审计/回滚用）
 */
public record BatchSuspendResult(
        int total,
        int suspended,
        int venueNotFound,
        int skippedLocked,
        int skippedExempt,
        List<SkippedByGuardDetail> skipped,
        List<SuspendDetail> details
) {
    /** 暂停明细：门店 + 变更前后状态 + 来源标识（回滚依据） */
    public record SuspendDetail(
            long venueId,
            String venueName,
            String fromStatus,
            String toStatus,
            String sourceId,
            String source
    ) {}
}
