package org.quwuting.quwutingservice.appfeedback.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.appfeedback.dto.response.AdminAppFeedbackResponse;
import org.quwuting.quwutingservice.appfeedback.service.AppFeedbackService;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.request.HandleReportRequest;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 平台级意见反馈管理端接口（仅 ADMIN，2026-08-28 新增）。
 * <p>
 * 与门店上报（/admin/reports）并列：路由前缀 /admin/app-feedbacks，
 * 上报管理后台第三 tab「意见反馈」消费。三动作与门店上报体验一致
 * （采纳并奖励 / 采纳不奖励 / 已处理 / 忽略，处理说明随站内信回传反馈者）。
 */
@RestController
@RequestMapping("/admin/app-feedbacks")
@RequiredArgsConstructor
public class AdminAppFeedbackController {

    private final AppFeedbackService appFeedbackService;

    /**
     * 意见反馈列表（需 ADMIN，分页倒序，可按状态筛选）。
     * GET /admin/app-feedbacks?status=PENDING&page=0&size=20
     */
    @GetMapping
    public ApiResponse<Page<AdminAppFeedbackResponse>> listFeedbacks(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(appFeedbackService.listAdminFeedbacks(status, page, size));
    }

    /**
     * 标记为已处理（需 ADMIN，幂等）。
     * POST /admin/app-feedbacks/{id}/resolve
     * body 可选：{"note": "处理结果说明"}——随站内信回传反馈者。
     */
    @PostMapping("/{id}/resolve")
    public ApiResponse<Void> resolveFeedback(@PathVariable Long id,
                                             @RequestBody(required = false) HandleReportRequest request) {
        appFeedbackService.resolveFeedback(id, request);
        return ApiResponse.ok(null);
    }

    /**
     * 采纳反馈（需 ADMIN，幂等）。
     * POST /admin/app-feedbacks/{id}/adopt
     * body 可选：{"note": "...", "reward": true|false}——reward 缺省 = true
     * （采纳并奖励，PointsSourceType.APP_FEEDBACK_REWARD 同事务发分）。
     */
    @PostMapping("/{id}/adopt")
    public ApiResponse<Void> adoptFeedback(@PathVariable Long id,
                                           @RequestBody(required = false) HandleReportRequest request) {
        appFeedbackService.adoptFeedback(id, request);
        return ApiResponse.ok(null);
    }

    /**
     * 标记为已忽略（需 ADMIN，幂等）。
     * POST /admin/app-feedbacks/{id}/dismiss
     * body 可选：{"note": "忽略原因"}——随站内信回传反馈者。
     */
    @PostMapping("/{id}/dismiss")
    public ApiResponse<Void> dismissFeedback(@PathVariable Long id,
                                             @RequestBody(required = false) HandleReportRequest request) {
        appFeedbackService.dismissFeedback(id, request);
        return ApiResponse.ok(null);
    }
}
