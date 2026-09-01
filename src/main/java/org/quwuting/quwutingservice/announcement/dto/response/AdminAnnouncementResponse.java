package org.quwuting.quwutingservice.announcement.dto.response;

import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementScope;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;

import java.time.LocalDateTime;

/**
 * 管理端公告项（GET /admin/announcements 列表 + GET /admin/announcements/{id} 详情，
 * 需 ADMIN）。
 * <p>
 * 管理端全字段（含 content——编辑回显需要原文；operatorId 审计留痕；
 * 计划/实际发布下线四时间全量暴露）。列表与详情共用本结构（管理端列表
 * 数据量小，不追求列表轻量化，编辑页直接复用回显）。
 */
public record AdminAnnouncementResponse(
        Long id,
        String title,
        String content,
        AnnouncementCategory category,
        AnnouncementSource source,
        AnnouncementScope scope,
        AnnouncementStatus status,
        boolean pinned,
        LocalDateTime publishAt,
        LocalDateTime offlineAt,
        LocalDateTime publishedAt,
        LocalDateTime offlinedAt,
        Long operatorId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
