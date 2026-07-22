package org.quwuting.quwutingservice.taginteraction.dto.response;

/**
 * 单个标签的点赞统计。
 *
 * @param tag        标签文本
 * @param likeCount  点赞总数
 * @param likedByMe  当前用户是否已赞（未登录时恒为 false）
 */
public record TagLikeStats(
        String tag,
        long likeCount,
        boolean likedByMe
) {}
