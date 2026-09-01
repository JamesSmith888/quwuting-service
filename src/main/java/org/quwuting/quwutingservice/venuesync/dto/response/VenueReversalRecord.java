package org.quwuting.quwutingservice.venuesync.dto.response;

import java.time.LocalDateTime;

/**
 * 更新记录（2026-09-01，快照机制退出后「已更新门店」的数据源）。
 * <p>
 * 读自 VenueStatusLog 中系统自动反转条目（changedBy IS NULL 且 CEASED/SUSPENDED → OPEN），
 * 带平台门店名/城市（门店已删时名称为 null，前端回退显示门店 ID）。
 *
 * @param venueId      平台门店 id
 * @param venueName    平台门店名（门店已删为 null）
 * @param city         门店城市（门店已删为 null）
 * @param fromStatus   反转前状态（CEASED/SUSPENDED）
 * @param toStatus     反转后状态（恒 OPEN）
 * @param reversedAt   反转时间（状态日志写入时刻）
 * @param changeSource 变更来源标识（AGENT_BATCH=Agent+Skill 批量更新 / ADMIN=管理端人工 /
 *                     null=旧数据或其他系统自动变更；2026-09-01 V8）
 */
public record VenueReversalRecord(
        long venueId,
        String venueName,
        String city,
        String fromStatus,
        String toStatus,
        LocalDateTime reversedAt,
        String changeSource
) {
}
