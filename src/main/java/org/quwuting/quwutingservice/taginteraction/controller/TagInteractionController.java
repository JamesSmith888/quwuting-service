package org.quwuting.quwutingservice.taginteraction.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.taginteraction.dto.request.LikeTagRequest;
import org.quwuting.quwutingservice.taginteraction.dto.request.ScoreTagRequest;
import org.quwuting.quwutingservice.taginteraction.dto.response.TagStatsResponse;
import org.quwuting.quwutingservice.taginteraction.service.TagInteractionService;
import org.springframework.web.bind.annotation.*;

/**
 * 标签交互接口：点赞 + 维度评分。
 * <p>
 * 路由挂载在 /venues/{venueId}/tags 下，语义为"场所的标签交互"。
 * 注意：字面量路径 "tags" 与 VenueController 的 /venues/{id} 不冲突（多一级路径段）。
 */
@RestController
@RequestMapping("/venues/{venueId}/tags")
@RequiredArgsConstructor
public class TagInteractionController {

    private final TagInteractionService tagInteractionService;

    /**
     * 获取场所标签交互统计（公开，软鉴权：登录时返回个人状态）
     * GET /venues/{venueId}/tag-stats
     */
    @GetMapping("/stats")
    public ApiResponse<TagStatsResponse> getTagStats(@PathVariable Long venueId) {
        Long userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(tagInteractionService.getTagStats(venueId, userId));
    }

    /**
     * 切换标签点赞（toggle：首次=赞，再次=取消）
     * POST /venues/{venueId}/tags/like
     */
    @PostMapping("/like")
    public ApiResponse<Boolean> toggleLike(@PathVariable Long venueId,
                                           @Valid @RequestBody LikeTagRequest req) {
        Long userId = UserContext.requireAuth();
        boolean liked = tagInteractionService.toggleLike(userId, venueId, req.tag());
        return ApiResponse.ok(liked);
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
