package org.quwuting.quwutingservice.taginteraction.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.taginteraction.dto.request.ScoreTagRequest;
import org.quwuting.quwutingservice.taginteraction.dto.response.TagStatsResponse;
import org.quwuting.quwutingservice.taginteraction.service.TagInteractionService;
import org.springframework.web.bind.annotation.*;

/**
 * 评分交互接口：维度评分。
 * <p>
 * 路由挂载在 /venues/{venueId}/tags 下。原"标签点赞"（/tags/like）已被 Reaction 快速反馈
 * 系统替代，见 {@link org.quwuting.quwutingservice.venuereaction.controller.VenueReactionController}
 * （/venues/{venueId}/reactions）与 AGENTS.md「Reaction 快速反馈系统」章节。
 */
@RestController
@RequestMapping("/venues/{venueId}/tags")
@RequiredArgsConstructor
public class TagInteractionController {

    private final TagInteractionService tagInteractionService;

    /**
     * 获取场所评分统计（公开，软鉴权：登录时返回个人评分状态）
     * GET /venues/{venueId}/tags/stats
     */
    @GetMapping("/stats")
    public ApiResponse<TagStatsResponse> getTagStats(@PathVariable Long venueId) {
        Long userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(tagInteractionService.getTagStats(venueId, userId));
    }

    /**
     * 维度评分（upsert：首次=打分，再次=修改覆盖）
     * POST /venues/{venueId}/tags/score
     */
    @PostMapping("/score")
    public ApiResponse<Void> score(@PathVariable Long venueId,
                                   @Valid @RequestBody ScoreTagRequest req) {
        Long userId = UserContext.requireAuth();
        tagInteractionService.score(userId, venueId, req.tag(), req.score());
        return ApiResponse.ok(null);
    }
}
