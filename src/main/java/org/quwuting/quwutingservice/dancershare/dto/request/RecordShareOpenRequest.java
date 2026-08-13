package org.quwuting.quwutingservice.dancershare.dto.request;

/**
 * 记录分享打开请求体（POST /dancers/{id}/share-opens，镜像场所分享）。
 * shareFrom 为原分享者用户 ID（来自分享路径 share_from 参数），
 * 可空——匿名分享者的卡片不携带归因参数，OPEN 事件 shareFrom 为 null。
 */
public record RecordShareOpenRequest(
        Long shareFrom
) {
}
