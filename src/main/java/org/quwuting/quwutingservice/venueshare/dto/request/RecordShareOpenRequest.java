package org.quwuting.quwutingservice.venueshare.dto.request;

/**
 * 记录分享打开请求体（POST /venues/{id}/share-opens）。
 * shareFrom 为原分享者用户 ID（来自分享路径 share_from 参数），
 * 可空——匿名分享者的卡片不携带归因参数，OPEN 事件 shareFrom 为 null。
 * <p>
 * activityId（2026-09-16，V28，docs/agents/49-venue-activities.md §8）可空：
 * 打开者的落地 URL 携带 act_id（分享路径透传）时才有值，表示"**这条活动的分享卡片
 * 被点开了**"。注意与 shareFrom 相互独立——匿名分享者的卡片不带 share_from，
 * 但仍可带 act_id（活动归因不依赖分享者身份，二者是正交的两个维度）。
 */
public record RecordShareOpenRequest(
        Long shareFrom,
        Long activityId
) {
}
