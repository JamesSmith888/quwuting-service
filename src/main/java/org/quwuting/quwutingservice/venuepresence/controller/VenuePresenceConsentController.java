package org.quwuting.quwutingservice.venuepresence.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuepresence.dto.request.ReportPresenceConsentRequest;
import org.quwuting.quwutingservice.venuepresence.dto.response.PresenceConsentAckResponse;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 到店足迹开关状态上报接口（2026-09-29 四轮，V34；文档 =
 * docs/agents/52-venue-presence.md）。
 * <p>
 * 「我的-设置-到店足迹」拨动开关时 fire-and-forget 上报一次状态确立（USER 来源）。
 * 与每店痕迹 {@code POST /venues/{venueId}/presence} 分离：开关是用户全局偏好，
 * 不挂门店维度；路径用 {@code /venues/presence-consent} 字面段（同
 * {@code /venues/activity-badges} 先例，精确匹配优先于 {venueId} 变量）。
 */
@RestController
@RequestMapping("/venues/presence-consent")
@RequiredArgsConstructor
public class VenuePresenceConsentController {

    private final VenuePresenceService venuePresenceService;

    /**
     * 上报一次开关状态变更（需登录；每次变更插一行 USER 流水，不幂等去重——
     * 连拨两次 = 两行，统计按「每用户最新一条」口径吸收）。
     * POST /venues/presence-consent　body: { enabled: boolean }
     */
    @PostMapping
    public ApiResponse<PresenceConsentAckResponse> report(
            @RequestBody ReportPresenceConsentRequest request) {
        // requireAuth 放方法首位（重放安全不变量：鉴权先于任何副作用）
        Long userId = UserContext.requireAuth();
        venuePresenceService.recordConsent(userId, request.enabled());
        return ApiResponse.ok(new PresenceConsentAckResponse(true));
    }
}
