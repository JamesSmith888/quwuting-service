package org.quwuting.quwutingservice.dancershare.dto.request;

/**
 * 记录分享打开请求体（POST /dancers/{id}/share-opens，镜像场所分享）。
 * shareFrom 为原分享者用户 ID（来自分享路径 share_from 参数），
 * 可空——匿名分享者的卡片不携带归因参数，OPEN 事件 shareFrom 为 null。
 * demandId 为分享卡片携带的邀约 id（2026-08-27，V56，docs/agents/25「分享闭环
 * 自动化」；邀约落地页打开时透传）——非空则服务端同步置该邀约
 * share_opened_at（幂等），客人侧「TA 已查看你的邀约」零操作自动感知。
 */
public record RecordShareOpenRequest(
        Long shareFrom,
        Long demandId
) {
}
