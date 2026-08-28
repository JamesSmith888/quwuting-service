package org.quwuting.quwutingservice.appfeedback.dto.response;

import org.quwuting.quwutingservice.appfeedback.AppFeedbackCategory;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * 我的意见反馈记录项（GET /app-feedbacks/mine，需登录）。
 * <p>
 * 与 venuefeedback 的 MyFeedbackResponse 同构：全状态展示（PENDING/RESOLVED/
 * DISMISSED/ADOPTED 均可见——异步审核流程的每条记录都有消费价值），handleNote
 * 为管理员处理结果说明（处理结果回传用户 = "告诉我们第一时间处理"承诺的闭环）。
 * rewardEarned 仅终态 ADOPTED（采纳并奖励）非空。
 */
public record MyAppFeedbackResponse(
        Long id,
        AppFeedbackCategory category,
        String categoryDisplay,
        String content,
        String imageUrl,
        ReportStatus status,
        String statusDisplay,
        String handleNote,
        LocalDateTime handledAt,
        Integer rewardEarned,
        LocalDateTime createdAt
) {}
