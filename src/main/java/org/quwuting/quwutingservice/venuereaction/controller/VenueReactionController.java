package org.quwuting.quwutingservice.venuereaction.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionStatsResponse;
import org.quwuting.quwutingservice.venuereaction.service.VenueReactionService;
import org.springframework.web.bind.annotation.*;

/**
 * Reaction 交互接口：详情页完整统计 + toggle 参与。
 * 路由挂载在 /venues/{venueId}/reactions 下，替代原 /venues/{venueId}/tags/like。
 */
@RestController
@RequestMapping("/venues/{venueId}/reactions")
@RequiredArgsConstructor
public class VenueReactionController {

    private final VenueReactionService venueReactionService;

    /**
     * 场所 Reaction 完整统计（公开，软鉴权：登录时返回个人参与状态）
     * GET /venues/{venueId}/reactions/stats
     */
    @GetMapping("/stats")
    public ApiResponse<ReactionStatsResponse> getStats(@PathVariable Long venueId) {
        Long userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(venueReactionService.getStats(venueId, userId));
    }

    /**
     * 切换 Reaction 参与（toggle：首次=参与，再次=取消）
     * POST /venues/{venueId}/reactions/{code}
     */
    @PostMapping("/{code}")
    public ApiResponse<Boolean> toggle(@PathVariable Long venueId, @PathVariable String code) {
        Long userId = UserContext.requireAuth();
        boolean reacted = venueReactionService.toggle(userId, venueId, code);
        return ApiResponse.ok(reacted);
    }
}
