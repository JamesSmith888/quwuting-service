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
 * 到店足迹状态确立上报接口（2026-09-29 四轮 V34；2026-10-03 五轮增到店首问来源；文档 =
 * docs/agents/52-venue-presence.md §5）。
 * <p>
 * 两个确立来源：「我的-设置-到店足迹」拨动开关（USER）与第一次真正到店时的首问回答（PROMPT）。
 * 本接口写入的流水是采集门禁的数据源——最新一条显式开启之后，该用户的 ping 才会被收下。
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
     * 上报一次状态确立（需登录；每次插一行流水，不幂等去重——连拨两次 = 两行，
     * 当前态按「每用户最新一条」口径吸收）。
     * POST /venues/presence-consent　body: { enabled: boolean, source?: "USER" | "PROMPT" }
     */
    @PostMapping
    public ApiResponse<PresenceConsentAckResponse> report(
            @RequestBody ReportPresenceConsentRequest request) {
        // requireAuth 放方法首位（重放安全不变量：鉴权先于任何副作用）
        Long userId = UserContext.requireAuth();
        venuePresenceService.recordConsent(userId, request.enabled(), request.source());
        return ApiResponse.ok(new PresenceConsentAckResponse(true));
    }
}
