package org.quwuting.quwutingservice.announcement.dto.response;

import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementScope;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementTouchLevel;

import java.time.LocalDateTime;

/**
 * 管理端公告项（GET /admin/announcements 列表 + GET /admin/announcements/{id} 详情，
 * 需 ADMIN）。
 * <p>
 * 管理端全字段（含 content——编辑回显需要原文；operatorId 审计留痕；
 * 计划/实际发布下线四时间全量暴露）。列表与详情共用本结构（管理端列表
 * 数据量小，不追求列表轻量化，编辑页直接复用回显）。
 * <p>
 * {@code touchLevel}（触达等级，2026-09-15）= 是否计入用户未读的判据，编辑页
 * 必须回显——否则运营改一次正文就会把每日舞讯的档位悄悄改回默认 ALERT。
 */
public record AdminAnnouncementResponse(
        Long id,
        String title,
        String content,
        AnnouncementCategory category,
        AnnouncementTouchLevel touchLevel,
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
