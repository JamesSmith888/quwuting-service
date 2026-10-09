package org.quwuting.quwutingservice.spend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 增量拉取条目（GET /spend/entries?cursor=，跨设备/换机恢复）。
 * deleted=true 行同步下发——客户端据此删除本地副本（游标语义与 venues/snapshot
 * 同模式：含软删行，客户端单向收敛）。direction 恒有值（EXPENSE/INCOME），
 * 恢复行据此还原本地方向。companions（2026-10-09，V47）= 一同计时的人快照，无则空数组
 * （恒非 null：客户端不必区分「没有」与「缺字段」）。
 */
public record SpendEntryResponse(
        String clientEntryId,
        long ts,
        BigDecimal amount,
        String category,
        String source,
        String sourceRefId,
        Long venueId,
        String venueName,
        Integer durationSeconds,
        boolean deleted,
        long updatedAt,
        String direction,
        List<SpendCompanionItem> companions
) {
}
