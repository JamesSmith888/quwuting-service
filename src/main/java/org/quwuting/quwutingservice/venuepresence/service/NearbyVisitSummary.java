package org.quwuting.quwutingservice.venuepresence.service;

/**
 * 单店「附近的足迹」展示事实（2026-10-08，52 号「C 侧展示：附近的足迹」节）。
 * <p>
 * 一行 = 一家店在 {@code NEARBY_TRACE_RADIUS_M} 范围内（含本店）全部<b>「附近带」足迹</b>
 * （距店 ≤ {@code NEARBY_RADIUS_M}，2026-10-09 起含 150~300m 段）的<b>并集</b>计数，
 * 由 {@code VenuePresenceService#nearbyVisitSummaries} 产出、{@code VenueVisitBadgeService}
 * 渲染为卡片文案。语义是「这一带有人留下过足迹」，<b>不是</b>「这家店有多少人到访」——
 * 刻意不做门店级归属（经纬度距离无法判定用户真实在哪家店，见常量注释）。
 * <p>
 * 本记录是 {@link NearbyFootprint} 的 C 侧投影（人数 + 次数）；admin 门店列表 / 详情 / 名单读的是
 * 同一次计算的完整记录（2026-10-13 起两侧数字同源，不会再出现「后台 4 人、小程序 2 人」）。
 *
 * @param visitUsers  近 30 天「附近带」足迹的去重用户数（≥1 才有本记录；并集去重，同一用户多处只计 1）
 * @param visitEvents 近 30 天去重到访次数 = {@code (用户, 自然日)} 二元组并集大小；
 *                    ⚠️ 与 {@code visitUsers} 同源但去重粒度不同（人数跨零点连场算 1、次数算 2），
 *                    <b>恒 ≥ visitUsers</b>（每位用户至少贡献 1 次），⛔ 禁互相推算
 */
public record NearbyVisitSummary(long visitUsers, long visitEvents) {
}
