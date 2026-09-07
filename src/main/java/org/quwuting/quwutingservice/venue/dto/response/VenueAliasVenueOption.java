package org.quwuting.quwutingservice.venue.dto.response;

import org.quwuting.quwutingservice.venue.enums.VenueStatus;

/**
 * 管理端门店候选（GET /admin/venue-aliases/venue-search，配置别名时选店）。
 * <p>
 * 名称模糊命中（ESCAPE '!' 字面口径与公开搜索一致），不限状态——停业/暂停门店
 * 同样可能配置别名（曾用名），空关键词返回最近收录兜底（首次进入即有候选可点）。
 */
public record VenueAliasVenueOption(
        Long id,
        String name,
        String city,
        VenueStatus status
) {}
