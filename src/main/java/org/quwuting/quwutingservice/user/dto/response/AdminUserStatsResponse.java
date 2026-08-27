package org.quwuting.quwutingservice.user.dto.response;

/**
 * 管理端用户统计概览（2026-08-27 用户管理增强，GET /admin/users/stats；仅 ADMIN）。
 * <p>
 * 定位：用户列表页顶部统计条——运营进入用户管理的<b>第一眼大盘</b>：用户盘子
 * 多大（totalUsers）、今天来了多少（todayNewUsers）、有多少管理员（adminUsers）、
 * 最近 7 日活跃多少（activeUsers7d，活跃口径 = 与列表 lastActiveAt 同源的四源
 * MAX）。活跃口径定义见 AdminUserStatsService.lastActive 聚合注释。
 */
public record AdminUserStatsResponse(
        /** 用户总数（未软删） */
        long totalUsers,
        /** 今日新增用户数（createdAt >= 今日 00:00） */
        long todayNewUsers,
        /** 管理员数 */
        long adminUsers,
        /** 近 7 日活跃用户数（四源 MAX >= now-7d） */
        long activeUsers7d
) {}
