package org.quwuting.quwutingservice.bulletin.dto.request;

import java.time.LocalDateTime;

/**
 * 发布快讯请求（POST /admin/bulletins/{id}/publish，需 ADMIN）。
 * <p>
 * publishAt 缺省（含 body 缺省） = 立即发布；指定未来时刻 = 定时发布
 * （写计划时间，状态保持 DRAFT，由 @Scheduled 到点强转），语义与公告域一致。
 */
public record PublishBulletinRequest(LocalDateTime publishAt) {}
