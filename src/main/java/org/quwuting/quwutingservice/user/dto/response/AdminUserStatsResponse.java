package org.quwuting.quwutingservice.user.dto.response;

/**
 * 管理端用户统计概览（2026-08-27 用户管理增强，GET /admin/users/stats；仅 ADMIN）。
 * <p>
 * 定位：用户列表页顶部统计条 + 数据看板顶卡——运营进入用户管理的<b>第一眼大盘</b>：
 * 用户盘子多大（totalUsers）、今天来了多少（todayNewUsers）、有多少管理员
 * （adminUsers）、最近 7 日有多少人在真的用（activeUsers7d）。
 * <p>
 * <b>口径（2026-09-15 统一，四项可交叉验算）</b>：前三项的「用户」一律指
 * <b>真实舞友</b>——{@code UserStatsSql.USER_SCOPE}（未软删、{@code role='USER'}、
 * 非 {@code test_} 开发号、非微信审核账号），与数据看板按日注册序列<b>同一分母</b>
 * （故 30 天注册之和与 totalUsers 对得上账）。{@code activeUsers7d} = <b>有效活跃</b>
 * ——近 7 日出现在用户主动行为事实集（12 表去重）的用户数，与看板「真实互动」序列、
 * 留存分析同一事实源；<b>不含登录自动打卡</b>（打卡只代表「打开过」，把它当活跃正是
 * 2026-09-15 修复的错误决策，见 docs/agents/35）。
 * <p>
 * adminUsers 口径正交：统计未软删、非微信审核的 {@code role='ADMIN'} 账号——运营身份数，
 * 不进真实舞友分母。
 */
public record AdminUserStatsResponse(
        /** 真实用户总数（未软删真实舞友，见类注释口径） */
        long totalUsers,
        /** 今日新增真实用户数（createdAt >= 今日 00:00） */
        long todayNewUsers,
        /** 管理员账号数（未软删非审核的 ADMIN，与真实用户口径正交） */
        long adminUsers,
        /** 近 7 日有效活跃用户数（用户主动行为去重；不含登录自动打卡） */
        long activeUsers7d
) {}
