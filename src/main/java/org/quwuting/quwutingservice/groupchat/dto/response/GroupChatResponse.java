package org.quwuting.quwutingservice.groupchat.dto.response;

import org.quwuting.quwutingservice.groupchat.enums.GroupChatScope;

import java.time.LocalDateTime;

/**
 * 舞友群响应（公开读与管理端共用）。
 * <p>
 * scopeName 为维度展示文案（前端可直接渲染，无需维护枚举字典——与门店
 * publisherType.displayName 同模式）；enabled 仅管理端列表有语义（公开读恒 true）。
 */
public record GroupChatResponse(
        Long id,
        String name,
        GroupChatScope scope,
        /** 维度展示文案（NATIONWIDE→全国 / CITY→城市 / REGION→地域） */
        String scopeName,
        /** 城市（scope=CITY 时非空） */
        String city,
        /** 地域（scope=REGION 时非空） */
        String region,
        /** 群二维码图片 URL（用户端长按识别） */
        String qrCodeUrl,
        /** 群简介 / 引导语 */
        String description,
        int displayOrder,
        boolean enabled,
        LocalDateTime createdAt
) {
}
