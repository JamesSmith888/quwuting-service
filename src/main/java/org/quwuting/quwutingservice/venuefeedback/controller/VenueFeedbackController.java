package org.quwuting.quwutingservice.venuefeedback.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.request.CreateFeedbackRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.response.VenueFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.service.VenueFeedbackService;
import org.springframework.web.bind.annotation.*;

/**
 * 用户上报提交通道（统一上报模板，2026-08-05 泛化）。
 * <p>
 * 路由嵌套在 /venues/{venueId} 下，与动态（posts）、状态报告（status-reports）
 * 等子资源保持一致的 URL 层级。任何登录用户均可提交（不要求管理权）。
 * <p>
 * 管理端（列表 / 处理 / 忽略）在 {@link ReportAdminController}（/admin/reports）。
 */
@RestController
@RequestMapping("/venues/{venueId}/feedbacks")
@RequiredArgsConstructor
public class VenueFeedbackController {

    private final VenueFeedbackService venueFeedbackService;

    /**
     * 提交用户上报（需登录）。
     * POST /venues/{venueId}/feedbacks
     * 响应携带 maintenanceHint（维护承诺，天数来自配置 app.reports.maintenance-days）。
     */
    @PostMapping
    public ApiResponse<VenueFeedbackResponse> createFeedback(
            @PathVariable Long venueId,
            @Valid @RequestBody CreateFeedbackRequest request
    ) {
        return ApiResponse.ok(venueFeedbackService.createFeedback(venueId, request));
    }
}
