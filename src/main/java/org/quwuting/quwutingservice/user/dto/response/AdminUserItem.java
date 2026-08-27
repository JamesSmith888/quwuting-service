package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.user.enums.UserRole;

import java.time.LocalDateTime;

/**
 * 管理端用户列表条目（2026-08-27，GET /admin/users，docs/agents/23；仅 ADMIN；
 * 2026-08-27 用户管理增强扩展维度）。
 * <p>
 * 定位：运营查用户/看贡献/识别异常的列表行——用户公开资料（昵称/头像/角色/
 * 加入天数 + <b>V53 资料字段 age/gender/city</b>）+ 积分余额 + 贡献档案摘要
 * （贡献值 + 等级称号）+ <b>行为信号</b>（需求单数/履约数/最近活跃）。
 * 展示边界 = 管理端（requireAdmin），不建公开用户主页（审核红线见 AGENTS.md
 * 「小程序类目合规 UGC 红线」）；openId 等敏感字段绝不下发。
 * <p>
 * 维度完整性：列表行只放"一眼可读"的运营维度（一屏决策），完整明细在详情页
 * （GET /admin/users/{id}，含需求/上报/认领分布与打卡连续性）。
 */
public record AdminUserItem(
        Long id,
        String nickname,
        String avatarUrl,
        UserRole role,
        /** 加入天数（createdAt → 今天，最小 0） */
        long joinedDays,
        /** 加入时间（管理端列表「加入时间」展示，前端 formatTimeAgo 短化） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,
        /** 年龄（用户自主录入，null = 未填写） */
        Integer age,
        /** 性别（MALE / FEMALE，null = 未声明） */
        String gender,
        /** 常驻城市（行政区划名，null = 未填写） */
        String city,
        /** 积分余额（qwt_points_accounts.balance 快照；无账户恒 0） */
        long pointsBalance,
        /** 贡献值（贡献档案聚合，见 ContributionService） */
        long contributionScore,
        /** 贡献等级称号（后端权威展示名） */
        String contributionLevelName,
        /** 需求单总数（qwt_demand_records；存量 NULL 状态等价 APPROVED 计入） */
        long demandCount,
        /** 履约次数（fulfilled_at 非空；2026-08-27 V54 履约闭环） */
        long fulfilledCount,
        /** 最近活跃时间（资料更新/积分流水/邀约/打卡 四源 MAX，最低回退加入时间——
         *  从未有任何行为 = 加入时间，见 AdminUserStatsService lastActive 定义） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastActiveAt
) {}
