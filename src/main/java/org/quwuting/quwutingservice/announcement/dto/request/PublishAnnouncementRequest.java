package org.quwuting.quwutingservice.announcement.dto.request;

import java.time.LocalDateTime;

/**
 * 发布公告请求（POST /admin/announcements/{id}/publish，需 ADMIN）。
 * <p>
 * publishAt 可空：
 * <ul>
 *   <li>缺省 = 立即发布（publish_at = now，状态直接置 PUBLISHED）；</li>
 *   <li>指定未来时刻 = 定时发布（publish_at 写入但状态保持 DRAFT，
 *       由 @Scheduled 到点强转 PUBLISHED——状态权威在后端定时任务）。</li>
 * </ul>
 */
public record PublishAnnouncementRequest(
        LocalDateTime publishAt
) {}
