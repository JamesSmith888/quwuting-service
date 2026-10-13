package org.quwuting.quwutingservice.venuepresence.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuepresence.dto.response.VisitThanksResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.VisitThanksStatusResponse;
import org.quwuting.quwutingservice.venuepresence.service.VenueVisitThanksService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 到店足迹感谢接口（2026-10-13，V50；docs/agents/52-venue-presence.md「到访感谢」节）。
 * <p>
 * 路由 = {@code /venues/{venueId}/visit-thanks}（与 presence / crowd-reports 同层级子资源）：
 * <ul>
 *   <li>{@code GET} 读状态（可感谢人数 / 我尚未感谢的人数）——驱动浮层按钮显隐与文案；</li>
 *   <li>{@code POST} 感谢该店全部尚未感谢过的贡献者（幂等；返回本次感谢数 + 剩余 + 反馈文案）。</li>
 * </ul>
 * 均需登录（openid 归因是「同一对永不重复」的前提；游客经静默登录天然具备身份——
 * 前端在打开浮层 / 点击前 {@code ensureLogin}，同点赞游客策略）。
 * <p>
 * <b>接口面即隐私面</b>：两个端点都不接受、也不返回任何具体的贡献者身份——请求体为空，
 * 响应只有计数（52 号「永不公开谁到过这家店」裁决在接口形态上的落实）。
 */
@RestController
@RequestMapping("/venues/{venueId}/visit-thanks")
@RequiredArgsConstructor
public class VenueVisitThanksController {

    private final VenueVisitThanksService venueVisitThanksService;

    /**
     * 感谢状态（供浮层按钮态）。语义是「我能做什么」——一个与「我」强相关的读数，
     * 游客静默登录后即有答案（与点赞游客策略一致）。
     */
    @GetMapping
    public ApiResponse<VisitThanksStatusResponse> status(@PathVariable Long venueId) {
        // requireAuth 放方法首位（重放安全不变量：鉴权先于任何副作用）
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(venueVisitThanksService.status(venueId, userId));
    }

    /**
     * 感谢一次（幂等：同一 (感谢者, 贡献者) 对永不重复、不重发通知；
     * 频控 = 每用户每店 60s，超限 1022）。
     */
    @PostMapping
    public ApiResponse<VisitThanksResponse> thank(@PathVariable Long venueId) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(venueVisitThanksService.thank(venueId, userId));
    }
}
