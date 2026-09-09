package org.quwuting.quwutingservice.user.dto.request;

/**
 * 微信审核账号标记请求（2026-09-09 V17，POST /admin/users/{id}/wechat-review，
 * 仅 ADMIN）：marked=true 标记（统计口径排除）/ false 取消。幂等。
 */
public record MarkWechatReviewRequest(
        /** true = 标记为微信审核账号（管理端统计排除）；false = 取消标记 */
        boolean marked
) {}
