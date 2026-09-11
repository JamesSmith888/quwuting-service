package org.quwuting.quwutingservice.bulletin.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户端快讯详情（GET /bulletins/{id}，需登录）。
 * <p>
 * 列表页已内联全文（{@link BulletinFeedItemResponse}），本接口保留为<b>长文深读与分享落地</b>
 * 通道：分享卡片直达单条内容（带标题的分享文案 + 直达路径），比"落到信息流某处"更稳。
 * <p>
 * content = markdown 原文，小程序侧复用公告详情页的 towxml 渲染链路与
 * {@code venue://} 链接归一化（外链降级纯文本）；reactions 与列表口径一致
 * （同一 DTO、同一聚合函数），保证两处展示绝不漂移；
 * viewCount 与列表口径一致（信息流展示即计），详情打开也会计一次浏览。
 */
public record BulletinDetailResponse(
        Long id,
        String excerpt,
        String content,
        String city,
        Long venueId,
        LocalDateTime publishAt,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        List<BulletinReactionBadge> reactions,
        Long viewCount
) {}
