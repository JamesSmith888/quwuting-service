package org.quwuting.quwutingservice.appfeedback.dto.response;

import org.quwuting.quwutingservice.appfeedback.AppFeedbackCategory;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * 意见反馈提交响应体（POST /app-feedbacks）。
 * <p>
 * 携带三件承诺/激励信息（前端零拼接，唯一事实源在后端）：
 * <ul>
 *   <li>{@code maintenanceHint}——"已收到！我们会第一时间处理，预计 X 日内回复"
 *       （天数来自配置 app.reports.maintenance-days，与门店上报同一承诺池）；</li>
 *   <li>{@code rewardAmount} / {@code rewardHint}——"被采纳可得 +N 积分"的
 *       采纳激励预告（2026-08-28 用户要求：积分数量在上报时提前告知用户），
 *       金额来自配置 app.points.feedback-reward（与门店纠错采纳同额同池）；</li>
 *   <li>{@code trackable}——userId 是否落库（false = 匿名，提示登录后可查看处理结果）。</li>
 * </ul>
 */
public record AppFeedbackResponse(
        Long id,
        AppFeedbackCategory category,
        String categoryDisplay,
        String content,
        String imageUrl,
        ReportStatus status,
        String statusDisplay,
        String maintenanceHint,
        int rewardAmount,
        String rewardHint,
        boolean trackable,
        LocalDateTime createdAt
) {}
