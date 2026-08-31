package org.quwuting.quwutingservice.venuesync.dto.response;

import java.time.LocalDateTime;

/**
 * 手动映射别名响应（GET /admin/venue-sync/aliases）。
 *
 * @param id         映射 ID（删除用）
 * @param city       标准城市名
 * @param sourceName 网上门店名称（信息源店名）
 * @param venueId    平台门店 ID
 * @param venueName  平台门店名（展示用）
 * @param venueCity  平台门店城市（展示用）
 * @param note       备注
 * @param updatedAt  最近配置时间
 */
public record VenueSyncAliasResponse(
        Long id,
        String city,
        String sourceName,
        Long venueId,
        String venueName,
        String venueCity,
        String note,
        LocalDateTime updatedAt
) {}
