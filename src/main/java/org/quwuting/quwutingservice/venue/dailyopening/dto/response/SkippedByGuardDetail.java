package org.quwuting.quwutingservice.venue.dailyopening.dto.response;

import java.time.LocalDateTime;

/**
 * 「因人工权威保护而跳过」明细（2026-09-14，V25；方案见 docs/agents/48）。
 * <p>
 * 两个外部舞讯批量通道（{@code /batch}、{@code /batch-suspend}）共用本明细：
 * 提交的条目若命中人工锁（{@code GuardSkipReason.LOCKED}）或永久豁免
 * （{@code EXEMPT}），既不写库也不产生关注者通知，仅在此如实列出。
 * <p>
 * 调用方（舞讯采集 Skill）必须把本列表按原因归类后**显式汇报**，
 * 否则用户无法区分「门禁在工作」与「该写库的漏跑了」。
 *
 * @param venueId     跳过的平台门店 ID
 * @param venueName   门店名（汇报可读）
 * @param reason      跳过原因枚举名（LOCKED / EXEMPT）
 * @param lockedUntil 人工锁到期时刻（reason=LOCKED 时非空，供汇报展示）
 */
public record SkippedByGuardDetail(
        long venueId,
        String venueName,
        String reason,
        LocalDateTime lockedUntil
) {}
