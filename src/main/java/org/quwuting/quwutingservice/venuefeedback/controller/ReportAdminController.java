package org.quwuting.quwutingservice.venuefeedback.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.request.HandleReportRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.response.AdminReportResponse;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.quwuting.quwutingservice.venuefeedback.service.VenueFeedbackService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 用户上报管理端接口（仅 ADMIN）。
 * <p>
 * 平台级聚合视图：跨场所分页列出全部上报（按状态/类型筛选），
 * 处理（resolve）/ 忽略（dismiss）流转状态机。路由前缀 /admin/reports
 * 独立于 /venues/{venueId}/feedbacks（提交通道），管理操作与具体场所无关。
 */
@RestController
@RequestMapping("/admin/reports")
@RequiredArgsConstructor
public class ReportAdminController {

    private final VenueFeedbackService venueFeedbackService;

    /**
     * 上报列表（需 ADMIN）。
     * GET /admin/reports?status=PENDING&type=PRICE&page=0&size=20
     * status / type 均可选，缺省返回全部；按提交时间倒序。
     */
    @GetMapping
    public ApiResponse<Page<AdminReportResponse>> listReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) FeedbackType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(venueFeedbackService.listAdminReports(status, type, page, size));
    }

    /**
     * 标记上报为已处理（需 ADMIN）。
     * POST /admin/reports/{id}/resolve
     * body 可选：{@code {"note": "处理结果说明"}}——处理结果随「我的上报记录」
     * 回传上报者（2026-08-06 新增，个人中心展示）。
     * 幂等：终态（RESOLVED/DISMISSED）重复操作直接返回成功。
     */
    @PostMapping("/{id}/resolve")
    public ApiResponse<Void> resolveReport(@PathVariable Long id,
                                           @RequestBody(required = false) HandleReportRequest request) {
        venueFeedbackService.resolveReport(id, request);
        return ApiResponse.ok(null);
    }

    /**
     * 标记上报为已忽略（需 ADMIN，判定为误报/无需处理）。
     * POST /admin/reports/{id}/dismiss
     * body 可选：{@code {"note": "处理结果说明"}}。
     * 幂等：终态重复操作直接返回成功。
     */
    @PostMapping("/{id}/dismiss")
    public ApiResponse<Void> dismissReport(@PathVariable Long id,
                                           @RequestBody(required = false) HandleReportRequest request) {
        venueFeedbackService.dismissReport(id, request);
        return ApiResponse.ok(null);
    }
}
