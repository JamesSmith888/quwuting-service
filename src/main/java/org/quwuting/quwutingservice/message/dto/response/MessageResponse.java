package org.quwuting.quwutingservice.message.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.message.enums.MessageType;

import java.time.LocalDateTime;

/**
 * 站内信列表项（GET /users/me/messages）。
 * <p>
 * read 为派生布尔（readAt 非空）；createdAt 由前端格式化为短格式展示
 * （同「我的上报」行模型的时间展示规则，见前端 utils）。
 */
public record MessageResponse(
        Long id,
        MessageType type,
        /** 标题（列表行主展示文案） */
        String title,
        /** 内容（正文；驳回时附原因） */
        String content,
        /** 业务关联类型（DANCER 等；null = 无关联，前端不深链） */
        String relatedType,
        /** 业务关联 ID（与 relatedType 成对；前端拼详情页 URL） */
        Long relatedId,
        /** 是否已读（派生自 readAt） */
        boolean read,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {}
