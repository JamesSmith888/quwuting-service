package org.quwuting.quwutingservice.venuestatusreport.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuestatusreport.dto.request.SubmitReportRequest;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.ActiveReportSummary;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.springframework.web.bind.annotation.*;

/**
 * 场所实时状态报告接口。
 * <p>
 * 路由嵌套在 /venues/{venueId} 下，与动态（posts）、反馈（feedbacks）等子资源保持一致的 URL 层级。
 * 任何登录用户均可报告（不要求管理权），与 venuefeedback 的权限模型一致。
 */
@RestController
@RequestMapping("/venues/{venueId}/status-reports")
@RequiredArgsConstructor
public class StatusReportController {

    private final StatusReportService statusReportService;

    /**
     * 提交或更新状态报告（upsert，需登录）。
     * POST /venues/{venueId}/status-reports
     * <p>
     * 请求体全部可选——极速上报时 body 可为空（{}），系统默认 reason=UNKNOWN。
     * 返回更新后的活跃报告摘要。
     */
    @PostMapping
    public ApiResponse<ActiveReportSummary> submitReport(
            @PathVariable Long venueId,
            @RequestBody(required = false) SubmitReportRequest request) {
        SubmitReportRequest req = request != null ? request
                : new SubmitReportRequest(null, null, null);
        return ApiResponse.ok(statusReportService.submitReport(venueId, req));
    }

    /**
     * 撤销当前用户的状态报告（需登录）。
     * POST /venues/{venueId}/status-reports/cancel
     * <p>
     * soft delete 当前用户对该场所的报告，返回更新后的活跃报告摘要。
     */
    @PostMapping("/cancel")
    public ApiResponse<ActiveReportSummary> cancelReport(@PathVariable Long venueId) {
        return ApiResponse.ok(statusReportService.cancelReport(venueId));
    }
}
