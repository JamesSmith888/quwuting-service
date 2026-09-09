package org.quwuting.quwutingservice.announcement.dto.response;

/**
 * 公告阅读统计（GET /admin/announcements/{id}/stats，需 ADMIN）。
 * <p>
 * readCount = 已读回执数；totalUsers = 平台有效用户数（未软删且非微信审核，
 * UserRepository.countByDeletedFalseAndWechatReviewFalse，2026-09-09 V17 统计口径）；readRate = readCount / totalUsers
 * （totalUsers=0 时取 0，避免除零）。运营侧据此判断公告触达效果。
 */
public record AnnouncementStatsResponse(
        long readCount,
        long totalUsers,
        double readRate
) {}
