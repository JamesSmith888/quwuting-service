package org.quwuting.quwutingservice.venuefeedback.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.request.CreateFeedbackRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.response.VenueFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.service.VenueFeedbackService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 场所信息纠错反馈接口。
 * <p>
 * 路由嵌套在 /venues/{venueId} 下，与动态（posts）等子资源保持一致的 URL 层级。
 */
@RestController
@RequestMapping("/venues/{venueId}/feedbacks")
@RequiredArgsConstructor
public class VenueFeedbackController {

    private final VenueFeedbackService venueFeedbackService;

    /**
     * 提交信息纠错反馈（需登录）。
     * POST /venues/{venueId}/feedbacks
     */
    @PostMapping
    public ApiResponse<VenueFeedbackResponse> createFeedback(
            @PathVariable Long venueId,
            @Valid @RequestBody CreateFeedbackRequest request
    ) {
        return ApiResponse.ok(venueFeedbackService.createFeedback(venueId, request));
    }
}
