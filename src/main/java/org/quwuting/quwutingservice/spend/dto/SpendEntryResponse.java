package org.quwuting.quwutingservice.spend.dto;

import java.math.BigDecimal;

/**
 * 增量拉取条目（GET /spend/entries?cursor=，跨设备/换机恢复）。
 * deleted=true 行同步下发——客户端据此删除本地副本（游标语义与 venues/snapshot
 * 同模式：含软删行，客户端单向收敛）。
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
        long updatedAt
) {
}
