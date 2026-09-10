package org.quwuting.quwutingservice.bulletin.dto.response;

import java.time.LocalDateTime;

/**
 * 用户端快讯详情（GET /bulletins/{id}，需登录）。
 * <p>
 * content = markdown 原文，小程序侧复用公告详情页的 towxml 渲染链路与
 * {@code venue://} 链接归一化（外链降级纯文本）。
 */
public record BulletinDetailResponse(
        Long id,
        String title,
        String content,
        String city,
        Long venueId,
        LocalDateTime publishAt,
        LocalDateTime publishedAt,
        LocalDateTime createdAt
) {}
