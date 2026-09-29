package org.quwuting.quwutingservice.venuepresence.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuepresence.dto.request.ReportPresenceRequest;
import org.quwuting.quwutingservice.venuepresence.dto.response.PresenceReportResponse;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店到访痕迹上报接口（2026-09-29，V33；文档 = docs/agents/52-venue-presence.md）。
 * <p>
 * 路由嵌套在 /venues/{venueId} 下，与 crowd-reports 等子资源同层级。
 * 需登录（openid 归因是防刷与本数据存在的前提；游客经静默登录天然具备身份）。
 * 请求体只有距离与精度两个标量——用户坐标在协议上不存在（隐私红线）。
 */
@RestController
@RequestMapping("/venues/{venueId}/presence")
@RequiredArgsConstructor
public class VenuePresenceController {

    private final VenuePresenceService venuePresenceService;

    /**
     * 上报一次到访痕迹（需登录；15 分钟桶幂等，重复上报被吸收不报错）。
     * POST /venues/{venueId}/presence
     * <p>
     * 请求体：{ distanceMeters: int（必填，0~500），accuracyMeters: int（选填） }。
     * 运营开关关闭时返回 accepted=false（HTTP 200），客户端 fire-and-forget 静默。
     */
    @PostMapping
    public ApiResponse<PresenceReportResponse> report(
            @PathVariable Long venueId,
            @RequestBody ReportPresenceRequest request) {
        // requireAuth 放方法首位（重放安全不变量：鉴权先于任何副作用）
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(venuePresenceService.report(venueId, userId, request));
    }
}
