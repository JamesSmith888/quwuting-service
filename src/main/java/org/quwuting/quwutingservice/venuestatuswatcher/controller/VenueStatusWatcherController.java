package org.quwuting.quwutingservice.venuestatuswatcher.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuestatuswatcher.service.VenueStatusWatcherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 关注门店营业状态（2026-08-12 新增，见 AGENTS.md「关注门店营业状态通知」）。
 * <ul>
 *   <li>POST /venues/{id}/status-watch — 开启关注（幂等；需登录）</li>
 *   <li>POST /venues/{id}/status-watch/cancel — 关闭关注（幂等；需登录）</li>
 *   <li>GET /venues/{id}/status-watch — 我的关注态（{@code {watching: boolean}}；需登录）</li>
 * </ul>
 * 2026-08-19：PUT/DELETE → POST 对齐项目「只允许 GET 和 POST」约定（关闭关注走
 * /cancel 动作路径，同门店 favorite /remove 先例）。
 * 关注是用户级资源：一律以当前登录用户为边界（user_id = 本人）。
 * 通知（站内信）在门店状态变更挂点发送，见 VenueStatusWatcherService#notifyStatusChanged。
 */
@RestController
@RequestMapping("/venues/{venueId}/status-watch")
@RequiredArgsConstructor
public class VenueStatusWatcherController {

    private final VenueStatusWatcherService watcherService;

    /** 开启关注（幂等：已关注直接成功） */
    @PostMapping
    public ApiResponse<Void> watch(@PathVariable Long venueId) {
        Long userId = UserContext.requireAuth();
        watcherService.watch(userId, venueId);
        return ApiResponse.ok(null);
    }

    /** 关闭关注（幂等：未关注静默成功） */
    @PostMapping("/cancel")
    public ApiResponse<Void> unwatch(@PathVariable Long venueId) {
        Long userId = UserContext.requireAuth();
        watcherService.unwatch(userId, venueId);
        return ApiResponse.ok(null);
    }

    /** 我的关注态（详情页开关初始化；未登录走 401，前端未登录不展示开关） */
    @GetMapping
    public ApiResponse<Map<String, Boolean>> isWatching(@PathVariable Long venueId) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(Map.of("watching", watcherService.isWatching(userId, venueId)));
    }
}
