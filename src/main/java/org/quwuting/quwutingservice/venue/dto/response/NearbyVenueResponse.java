package org.quwuting.quwutingservice.venue.dto.response;

/**
 * 附近门店（GET /venues/nearby，2026-09-09 消费账本域门店自动关联的数据源，
 * docs/agents/44-spend-ledger.md §13）：轻量行——只回 id/name/距离，列表页的
 * 徽标/热度/照片组装对"匹配最近门店"无意义。按距离升序，radiusM 内取最近 limit 家。
 */
public record NearbyVenueResponse(
        Long id,
        String name,
        /** 距离（米，haversine 服务端算） */
        double distanceMeters
) {
}
