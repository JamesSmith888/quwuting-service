package org.quwuting.quwutingservice.venuepresence.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenueVisitFootprintResponse;
import org.quwuting.quwutingservice.venuepresence.service.VenueVisitBadgeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店详情页「到店足迹」行数据接口（2026-10-16；docs/agents/52-venue-presence.md
 * 「详情页到店足迹 + 贡献者回路」节）。
 * <p>
 * 路由 = {@code /venues/{venueId}/visit-footprint}（与 presence / visit-thanks 同层级子资源）。
 * <b>软鉴权</b>：公开读——游客与登录用户都能拿到足迹行原文（与列表卡片同样是公开信息）；
 * 登录用户额外得到自己的足迹句（{@code mineText}）。因此<b>不</b>调 {@code requireAuth()}：
 * 游客没有 token 时返回 {@code mineText = null}，而不是 401（401 会让前端 httpRequest
 * 走「清凭证 → 提示重新登录」自愈链，对一个纯展示读是错误的副作用）。
 * <p>
 * ⚠️ 响应<b>随调用者不同</b>（带 {@code mineText}）⇒ 此接口及其上游<b>不得挂共享缓存</b>
 * （服务端聚合缓存 / CDN / 网关缓存均不可）。当前部署无任何 HTTP 缓存层；若未来引入，
 * 须把本路由列入绕过名单，否则 A 用户的「你留下过 N 次足迹」会被 B 用户读到。
 */
@RestController
@RequestMapping("/venues/{venueId}/visit-footprint")
@RequiredArgsConstructor
public class VenueVisitFootprintController {

    private final VenueVisitBadgeService venueVisitBadgeService;

    @GetMapping
    public ApiResponse<VenueVisitFootprintResponse> footprint(@PathVariable Long venueId) {
        // 软鉴权：读当前用户（可能为 null）。⛔ 不要改成 requireAuth——见类注释
        return ApiResponse.ok(venueVisitBadgeService.footprintViewOf(venueId, UserContext.getCurrentUserId()));
    }
}
