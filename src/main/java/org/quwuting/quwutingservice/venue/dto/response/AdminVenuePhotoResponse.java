package org.quwuting.quwutingservice.venue.dto.response;

import org.quwuting.quwutingservice.venue.enums.VenuePhotoStatus;

import java.time.LocalDateTime;

/**
 * 管理端门店照片审核列表项（GET /admin/venues/photos，仅 ADMIN）。
 * 门店已软删时 venueName 由服务层回退占位；上传者已软删/存量导入（created_by=0）
 * 时 uploaderNickname 由服务层回退占位。
 */
public record AdminVenuePhotoResponse(
        Long id,
        String url,
        VenuePhotoStatus status,
        Long venueId,
        /** 门店名称（LEFT JOIN qwt_venues；已软删回退"门店已删除"） */
        String venueName,
        Long createdBy,
        /** 上传者昵称（LEFT JOIN qwt_users；存量导入/用户已删回退占位） */
        String uploaderNickname,
        /** 上传时间（"yyyy-MM-dd HH:mm:ss"，审核列表按此倒序） */
        LocalDateTime createdAt
) {
}
