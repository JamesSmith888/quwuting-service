package org.quwuting.quwutingservice.venueshare.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venueshare.dto.request.RecordShareOpenRequest;
import org.quwuting.quwutingservice.venueshare.dto.request.RecordShareRequest;
import org.quwuting.quwutingservice.venueshare.service.VenueShareService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场所分享事件上报端点（分享追踪 P2 数据通道，2026-08-05 新增）。
 * <p>
 * 语义与 {@code POST /venues/{id}/view} 一致：fire-and-forget、软鉴权
 * （匿名可上报，身份仅作归因）、失败不影响主流程。分享维度不参与热度公式。
 */
@RestController
@RequestMapping("/venues")
@RequiredArgsConstructor
public class VenueShareController {

    private final VenueShareService venueShareService;

    /**
     * 记录一次分享动作（分享面板弹出时由 onShareAppMessage / onShareTimeline 触发）。
     * POST /venues/{id}/shares  body: {"channel": "BUTTON"|"MENU"|"TIMELINE"}（可选）
     */
    @PostMapping("/{id}/shares")
    public ApiResponse<Void> recordShare(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) RecordShareRequest request
    ) {
        String channel = request != null ? request.channel() : null;
        venueShareService.recordShare(id, UserContext.getCurrentUserId(), channel);
        return ApiResponse.ok(null);
    }

    /**
     * 记录一次分享打开（被分享者打开详情页且路径携带 share_from 时触发）。
     * POST /venues/{id}/share-opens  body: {"shareFrom": 123}（可选）
     */
    @PostMapping("/{id}/share-opens")
    public ApiResponse<Void> recordShareOpen(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) RecordShareOpenRequest request
    ) {
        Long shareFrom = request != null ? request.shareFrom() : null;
        venueShareService.recordOpen(id, UserContext.getCurrentUserId(), shareFrom);
        return ApiResponse.ok(null);
    }
}
