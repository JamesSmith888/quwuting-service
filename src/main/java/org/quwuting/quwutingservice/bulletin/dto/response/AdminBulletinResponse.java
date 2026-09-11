package org.quwuting.quwutingservice.bulletin.dto.response;

import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;

import java.time.LocalDateTime;

/**
 * 管理端快讯项（GET /admin/bulletins 列表 + GET /admin/bulletins/{id} 详情，需 ADMIN）。
 * <p>
 * 管理端全字段（含 content 编辑回显 + dedupKey 溯源 + 四个时间戳 + operatorId 审计）；
 * 列表与详情共用（管理端数据量小，不追求列表轻量化）。<b>不含 category</b>——
 * 快讯域恒为 FLASH，无需暴露。
 */
public record AdminBulletinResponse(
        Long id,
        String excerpt,
        String content,
        String city,
        Long venueId,
        String dedupKey,
        AnnouncementSource source,
        AnnouncementStatus status,
        LocalDateTime publishAt,
        LocalDateTime offlineAt,
        LocalDateTime publishedAt,
        LocalDateTime offlinedAt,
        Long operatorId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
