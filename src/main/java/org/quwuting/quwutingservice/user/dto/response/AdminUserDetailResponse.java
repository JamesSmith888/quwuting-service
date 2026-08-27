package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.points.dto.ContributionBrief;
import org.quwuting.quwutingservice.user.enums.UserRole;

import java.time.LocalDateTime;

/**
 * 管理端用户详情（2026-08-27，GET /admin/users/{id}，docs/agents/23；仅 ADMIN；
 * 2026-08-27 用户管理增强扩展维度）。
 * <p>
 * 定位：管理端用户列表行点击 → 用户详情——运营查看任意用户的<b>完整画像</b>：
 * 公开资料（昵称/头像/角色/加入时间 + V53 age/gender/city）+ 积分账户收支 +
 * 贡献档案（等级 + 六维度计数）+ 行为概览（需求单分布/履约/上报/认领/打卡）。
 * 识别异常/刷分/流失的决策页。
 * <p>
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
        /** 加入时间（管理端详情「加入时间」展示） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,
        /** 年龄（用户自主录入，null = 未填写） */
        Integer age,
        /** 性别（MALE / FEMALE，null = 未声明） */
        String gender,
        /** 常驻城市（行政区划名，null = 未填写） */
        String city,
        /** 最近活跃时间（资料更新/积分流水/邀约/打卡 四源 MAX，最低回退加入时间） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastActiveAt,
        /** 积分账户（余额 + 累计收支 + 流水条数） */
        PointsSummary points,
        /** 贡献档案完整明细（等级 + 各维度计数，见 ContributionBrief） */
        ContributionBrief contribution,
        /** 需求单概览（总数 + 履约数 + 按状态分布；存量 NULL 状态等价 APPROVED） */
        DemandSummary demand,
        /** 上报概览（门店信息上报 + 暂停营业报告合并；总数 + 待处理） */
        ReportSummary reports,
        /** 认领概览（总数 + 按状态分布） */
        ClaimSummary claims,
        /** 打卡概览（总天数 + 连续天数 + 最近打卡时间） */
        CheckinSummary checkin
) {}
