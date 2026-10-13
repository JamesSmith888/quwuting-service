package org.quwuting.quwutingservice.venuepresence.service;

import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceStats;

import java.util.List;

/**
 * 单店足迹详情（{@code VenuePresenceService#nearbyDetailFor} 的产出）：展示口径事实 + 邻域内其他门店。
 * <p>
 * 单独成型而不直接返回 {@link VenuePresenceStats} 的原因：该 DTO 带「小程序会显示的原文」，
 * 文案渲染属于展示层（{@code VenueVisitBadgeService#renderBadgeText}，改文案只改那一处），
 * 事实层（本包的 {@code VenuePresenceService}）不反向依赖渲染。由展示层把本记录装配成 DTO。
 */
public record NearbyDetail(NearbyFootprint footprint, List<VenuePresenceStats.VicinityVenue> vicinityVenues) {
}
