package org.quwuting.quwutingservice.venue.dto.response;

import org.quwuting.quwutingservice.venue.enums.VenuePhotoStatus;

/**
 * 门店照片响应（上传/删除/管理入口回显用，含状态供前端回显审核态）。
 * 与 DancerPhotoResponse 同构；公开消费（详情/列表轮播）不经本 DTO——
 * 消费方只读 VenueResponse.photos（PUBLIC URL 列表）。
 */
public record VenuePhotoResponse(
        Long id,
        String url,
        VenuePhotoStatus status
) {
}
