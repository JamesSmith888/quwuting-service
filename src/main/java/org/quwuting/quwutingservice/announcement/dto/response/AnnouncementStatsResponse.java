package org.quwuting.quwutingservice.announcement.dto.response;

/**
 * 公告阅读统计（GET /admin/announcements/{id}/stats，需 ADMIN）。
 * <p>
 * readCount = 已读回执数；totalUsers = <b>公告实际受众</b> = 平台真实用户数
 * （{@code UserStatsSql.USER_SCOPE}：未软删、{@code role='USER'}、非 {@code test_}
 * 开发号、非微信审核账号——2026-09-15 收敛为口径单一事实源；此前为「全部未软删非
 * 审核账号」，含 ADMIN 运营号与开发联调号）；readRate = readCount / totalUsers
 * （totalUsers=0 时取 0，避免除零）。运营侧据此判断公告触达效果。
 */
public record AnnouncementStatsResponse(
        long readCount,
        long totalUsers,
        double readRate
) {}
