package org.quwuting.quwutingservice.venuefeedback.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;

import java.time.LocalDateTime;

/**
 * 场所信息纠错反馈响应体。
 */
public record VenueFeedbackResponse(
        Long id,
        Long venueId,
        FeedbackType type,
        String typeDisplay,
        String note,
        boolean handled,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {}
