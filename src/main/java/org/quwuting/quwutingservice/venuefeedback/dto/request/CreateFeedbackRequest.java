package org.quwuting.quwutingservice.venuefeedback.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;

/**
 * 场所信息纠错反馈请求体。
 * <p>
 * type 为必填（用户选择具体问题类型），note 为可选补充说明。
 */
public record CreateFeedbackRequest(
        @NotNull(message = "反馈类型不能为空")
        FeedbackType type,

        @Size(max = 500, message = "补充说明最多 500 字")
        String note
) {}
