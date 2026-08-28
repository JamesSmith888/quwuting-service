package org.quwuting.quwutingservice.appfeedback.dto.response;

import org.quwuting.quwutingservice.appfeedback.AppFeedbackCategory;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * 管理端意见反馈列表项（GET /admin/app-feedbacks，需 ADMIN）。
 * <p>
 * 与 venuefeedback 的 AdminReportResponse 同构，另加 reporterName（上报者
 * 真实昵称——意见反馈无场所可作核对锚点，管理端需知道"谁反馈的"；匿名 =
 * "匿名用户"）。批量查昵称消除 N+1（与 StatusReportService 管理端上下文同模式）。
 */
public record AdminAppFeedbackResponse(
        Long id,
        AppFeedbackCategory category,
        String categoryDisplay,
        String content,
        String imageUrl,
        String reporterName,
        ReportStatus status,
        String statusDisplay,
        String handleNote,
        LocalDateTime handledAt,
        LocalDateTime createdAt
) {}
