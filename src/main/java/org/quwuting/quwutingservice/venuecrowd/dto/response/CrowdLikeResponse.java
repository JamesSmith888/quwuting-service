package org.quwuting.quwutingservice.venuecrowd.dto.response;

/**
 * 行级点赞操作响应（2026-09-03，docs/agents/27-venue-crowd-report.md「行级点赞」）：
 * POST like / unlike 返回更新后的权威状态，前端直接回写零拼接——
 * likeCount = 该行当前赞数（未删计数）；likedByMe = 我当前是否已赞。
 */
public record CrowdLikeResponse(
        int likeCount,
        boolean likedByMe
) {
}
