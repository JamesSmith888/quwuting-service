package org.quwuting.quwutingservice.bulletin.dto.response;

import java.time.LocalDateTime;

/**
 * 用户端快讯列表项（GET /bulletins，需登录）。
 * <p>
 * 列表轻量化：不含 content（详情接口单独拉取）；<b>不含 read 字段</b>——快讯域
 * 有意不做已读回执（不进红点，新鲜度由时间戳表达，docs/agents/47）。
 * city / venueId 供列表卡片渲染「城市标签」与「关联门店」锚点。
 */
public record BulletinSummaryResponse(
        Long id,
        String title,
        String city,
        Long venueId,
        LocalDateTime publishAt,
        LocalDateTime createdAt
) {}
