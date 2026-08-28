package org.quwuting.quwutingservice.appfeedback.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.appfeedback.dto.request.CreateAppFeedbackRequest;
import org.quwuting.quwutingservice.appfeedback.dto.response.AppFeedbackResponse;
import org.quwuting.quwutingservice.appfeedback.dto.response.MyAppFeedbackResponse;
import org.quwuting.quwutingservice.appfeedback.service.AppFeedbackService;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 平台级意见反馈提交通道（2026-08-28 新增）。
 * <p>
 * 路由独立于 /venues/{venueId}/feedbacks（门店维度）：本通道不绑定门店，
 * 面向"对整个小程序的 BUG/建议"。<b>匿名可提交</b>（不强推登录，响应
 * trackable=false 提示登录后可查看处理结果）。
 * <p>
 * 管理端（列表 / 处理 / 采纳）在 {@link AdminAppFeedbackController}
 * （/admin/app-feedbacks），上报管理后台第三 tab 消费。
 */
@RestController
@RequestMapping("/app-feedbacks")
@RequiredArgsConstructor
public class AppFeedbackController {

    private final AppFeedbackService appFeedbackService;

    /**
     * 提交意见反馈（匿名可提交，不强推登录）。
     * POST /app-feedbacks
     * 响应携带 maintenanceHint（"已收到！我们会第一时间处理，预计 X 日内回复"）、
     * rewardAmount/rewardHint（采纳积分前置告知）与 trackable。
     */
    @PostMapping
    public ApiResponse<AppFeedbackResponse> createFeedback(
            @Valid @RequestBody CreateAppFeedbackRequest request
    ) {
        return ApiResponse.ok(appFeedbackService.createFeedback(request));
    }

    /**
     * 我的意见反馈记录（需登录，倒序，含终态与管理端处理说明）。
     * GET /app-feedbacks/mine
     */
    @GetMapping("/mine")
    public ApiResponse<List<MyAppFeedbackResponse>> listMyFeedbacks() {
        return ApiResponse.ok(appFeedbackService.listMyFeedbacks());
    }
}
