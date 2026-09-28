package org.quwuting.quwutingservice.announcement.dto.response;

import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.media.MediaAttachment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户端公告详情（GET /announcements/{id}，需登录）。
 * <p>
 * content = Markdown 原文（小程序端 towxml 渲染，不预转 HTML——渲染责任
 * 在前端，后端只存原文）。media = 结构化媒体附件（2026-09-28，渲染在正文之后，
 * 图片可预览 / 视频内联播放；媒体作为事实与正文解耦，markdown 外链图片仅为
 * 历史内容保留兼容）。read 布尔 = 打开详情前是否已读（前端打开后调
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
        List<MediaAttachment> media,
        LocalDateTime publishAt,
        LocalDateTime publishedAt,
        boolean read,
        LocalDateTime createdAt
) {}
