package org.quwuting.quwutingservice.user.dto.response;

import org.quwuting.quwutingservice.points.dto.ContributionBrief;
import org.quwuting.quwutingservice.user.enums.UserRole;

/**
 * 管理端用户详情（2026-08-27，GET /admin/users/{id}，docs/agents/23；仅 ADMIN）。
 * <p>
 * 定位：管理端用户列表行点击 → 用户详情——公开资料（昵称/头像/角色/加入天数）+
 * 积分余额 + <b>贡献档案完整明细</b>（等级 + 各维度计数，ContributionBrief）。
 * 展示边界 = 管理端（requireAdmin）；openId 等敏感字段绝不下发；
 * 不建公开用户主页（审核红线见 AGENTS.md「小程序类目合规 UGC 红线」）。
 */
public record AdminUserDetailResponse(
        Long id,
        String nickname,
        String avatarUrl,
        UserRole role,
        /** 加入天数（createdAt → 今天，最小 0） */
        long joinedDays,
        /** 积分余额（qwt_points_accounts.balance 快照；无账户恒 0） */
        long pointsBalance,
        /** 贡献档案完整明细（等级 + 各维度计数，见 ContributionBrief） */
        ContributionBrief contribution
) {}
