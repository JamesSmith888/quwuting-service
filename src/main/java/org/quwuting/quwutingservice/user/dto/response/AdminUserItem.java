package org.quwuting.quwutingservice.user.dto.response;

import org.quwuting.quwutingservice.user.enums.UserRole;

/**
 * 管理端用户列表条目（2026-08-27，GET /admin/users，docs/agents/23；仅 ADMIN）。
 * <p>
 * 定位：运营查用户/看贡献/识别异常的列表行——用户公开资料（昵称/头像/角色/
 * 加入天数）+ 积分余额 + 贡献档案摘要（贡献值 + 等级称号）。展示边界 = 管理端
 * （requireAdmin），不建公开用户主页（审核红线见 AGENTS.md「小程序类目合规
 * UGC 红线」）；openId 等敏感字段绝不下发。
 */
public record AdminUserItem(
        Long id,
        String nickname,
        String avatarUrl,
        UserRole role,
        /** 加入天数（createdAt → 今天，最小 0） */
        long joinedDays,
        /** 积分余额（qwt_points_accounts.balance 快照；无账户恒 0） */
        long pointsBalance,
        /** 贡献值（贡献档案聚合，见 ContributionService） */
        long contributionScore,
        /** 贡献等级称号（后端权威展示名） */
        String contributionLevelName
) {}
