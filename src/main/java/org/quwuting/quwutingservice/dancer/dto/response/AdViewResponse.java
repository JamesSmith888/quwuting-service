package org.quwuting.quwutingservice.dancer.dto.response;

/**
 * 广告观看上报响应（POST /dancers/{id}/ad-views，创作者收益计划，2026-08-14）。
 * <ul>
 *   <li>{@code recorded}：true = 本次观看已计入收益（首次/当日首次）；
 *       false = 当日已支持过（幂等，不重复计收益，23505 兜底）；</li>
 *   <li>{@code viewsTotal}：该舞伴累计获得的广告支持次数（收益线下结算依据）。</li>
 * </ul>
 */
public record AdViewResponse(
        boolean recorded,
        long viewsTotal
) {}
