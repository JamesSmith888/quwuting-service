package org.quwuting.quwutingservice.venuefeedback.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.request.CreateFeedbackRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.response.MyFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.response.VenueFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.service.VenueFeedbackService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户上报提交通道（统一上报模板，2026-08-05 泛化）。
 * <p>
 * 路由嵌套在 /venues/{venueId} 下，与动态（posts）、状态报告（status-reports）
 * 等子资源保持一致的 URL 层级。<b>匿名可提交</b>（2026-08-06：不强推登录，
 * 响应 trackable=false 提示登录后可查看处理结果）；管理端不要求登录态。
 * <p>
 * 管理端（列表 / 处理 / 忽略）在 {@link ReportAdminController}（/admin/reports）。
 */
@RestController
@RequestMapping("/venues/{venueId}/feedbacks")
@RequiredArgsConstructor
public class VenueFeedbackController {

    private final VenueFeedbackService venueFeedbackService;

    /**
     * 提交用户上报（匿名可提交，不强推登录）。
     * POST /venues/{venueId}/feedbacks
     * 响应携带 maintenanceHint（维护承诺，天数来自配置 app.reports.maintenance-days）
     * 与 trackable（userId 是否落库：false = 匿名，前端提示登录后可查看处理结果）。
     */
    @PostMapping
    public ApiResponse<VenueFeedbackResponse> createFeedback(
            @PathVariable Long venueId,
            @Valid @RequestBody CreateFeedbackRequest request
    ) {
        return ApiResponse.ok(venueFeedbackService.createFeedback(venueId, request));
    }

    /**
     * 当前用户对该场所的上报记录（详情页「我的上报记录」弹窗数据源，需登录）。
     * GET /venues/{venueId}/feedbacks/mine
     * <p>
     * 只返回当前门店的记录（与个人中心 GET /feedbacks/mine 同口径，venueId 过滤
     * 由 Service 层统一实现）——「详情页弹窗只见当前门店，全部记录去个人中心」的
     * 用户任务模型（2026-08-06 收敛，见前端 AGENTS.md「我的上报记录」）。
     */
    @GetMapping("/mine")
    public ApiResponse<List<MyFeedbackResponse>> listVenueMyFeedbacks(@PathVariable Long venueId) {
        return ApiResponse.ok(venueFeedbackService.listMyFeedbacks(venueId));
    }
}
