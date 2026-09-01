package org.quwuting.quwutingservice.announcement.dto.response;

import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;

import java.time.LocalDateTime;

/**
 * 用户端公告详情（GET /announcements/{id}，需登录）。
 * <p>
 * content = Markdown 原文（小程序端 towxml 渲染，不预转 HTML——渲染责任
 * 在前端，后端只存原文）。read 布尔 = 打开详情前是否已读（前端打开后调
 * POST /{id}/read 标已读）。已下线/已软删公告返回 404（契约：列表不展示、
 * 详情 404，深链失效不渲染过期内容）。
 */
public record AnnouncementDetailResponse(
        Long id,
        String title,
        String content,
        AnnouncementCategory category,
        AnnouncementSource source,
        boolean pinned,
        LocalDateTime publishAt,
        LocalDateTime publishedAt,
        boolean read,
        LocalDateTime createdAt
) {}
