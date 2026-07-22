package org.quwuting.quwutingservice.venue.dto.response;

/** 城市统计（城市名 + 场所数量），用于前端热门城市选择 */
public record CityStatsResponse(
        String city,
        long venueCount
) {}
