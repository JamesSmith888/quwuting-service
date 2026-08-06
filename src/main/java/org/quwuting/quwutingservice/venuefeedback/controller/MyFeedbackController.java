package org.quwuting.quwutingservice.venuefeedback.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.response.MyFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.service.VenueFeedbackService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户级上报记录接口（「我的上报记录」，2026-08-06 新增）。
 * <p>
 * 路由与场所子资源 {@code /venues/{venueId}/feedbacks} 区分——「我的上报记录」是
 * 用户维度资源（跨场所），不属于单个场所，参照 {@code /status-reports/mine}（我的
 * 状态上报）与 {@code /favorites}（用户级收藏列表）的顶层资源模型。
 * <p>
 * 与场所级 {@code GET /venues/{venueId}/feedbacks/mine} 共用
 * {@link VenueFeedbackService#listMyFeedbacks}（venueId 可选过滤，同一查询口径）。
 */
@RestController
@RequestMapping("/feedbacks")
@RequiredArgsConstructor
public class MyFeedbackController {

    private final VenueFeedbackService venueFeedbackService;

    /**
     * 当前用户的全部上报记录（个人中心「我的上报」区块数据源，需登录）。
     * GET /feedbacks/mine?venueId=
     * <p>
     * venueId 可选：缺省 = 跨场所全部（个人中心）；传值 = 单门店（与场所级
     * {@code /venues/{venueId}/feedbacks/mine} 等价，供详情页弹窗复用同一后端口径）。
     * 返回全部状态记录（含已处理/已忽略——展示管理员处理结果），按上报时间倒序。
     */
    @GetMapping("/mine")
    public ApiResponse<List<MyFeedbackResponse>> listMyFeedbacks(
            @RequestParam(required = false) Long venueId
    ) {
        return ApiResponse.ok(venueFeedbackService.listMyFeedbacks(venueId));
    }
}
