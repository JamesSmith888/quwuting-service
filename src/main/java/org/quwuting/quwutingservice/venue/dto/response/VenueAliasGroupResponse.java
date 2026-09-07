package org.quwuting.quwutingservice.venue.dto.response;

import org.quwuting.quwutingservice.venue.enums.VenueStatus;

import java.util.List;

/**
 * 门店别名聚合响应（GET /admin/venue-aliases）。
 * <p>
 * 每组 = 门店 + 其全部有效别名（录入倒序，最近配置在前）；组序 = 该店最近一条
 * 别名配置时间倒序。门店已软删/不存在的组自动跳过（同 VenueSyncAliasService#export
 * 的悬空兜底口径）。
 */
public record VenueAliasGroupResponse(
        Long venueId,
        String venueName,
        String city,
        VenueStatus status,
        List<AliasItem> aliases
) {
    /** 单条别名（id 供管理端删除按钮定位） */
    public record AliasItem(Long id, String alias) {}
}
