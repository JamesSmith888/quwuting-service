package org.quwuting.quwutingservice.venue.dto.request;

import jakarta.validation.constraints.NotNull;

import org.quwuting.quwutingservice.venue.enums.VenuePhotoStatus;

/**
 * 门店照片审核请求体（POST /admin/venues/photos/{photoId}/status，仅 ADMIN）。
 * 与 UpdateDancerPhotoStatusRequest 同构：PENDING → PUBLIC（通过）/ REJECTED
 * （驳回，reason 可选仅服务端审计日志——上传者本人在管理入口可见 REJECTED 状态
 * 后自行删除重传，不新增站内信，见 AGENTS.md「门店照片域 · 审核」）。
 */
public record UpdateVenuePhotoStatusRequest(
        @NotNull(message = "审核状态不能为空")
        VenuePhotoStatus status,
        String reason
) {
}
