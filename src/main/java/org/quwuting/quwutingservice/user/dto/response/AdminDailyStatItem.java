package org.quwuting.quwutingservice.user.dto.response;

import java.time.LocalDate;

/**
 * 管理端运营大盘「单日统计」项（2026-09-06，GET /admin/users/daily-stats；仅 ADMIN）。
 * <p>
 * 口径权威定义见 {@code UserDailyStatsRepository} 类注释（与 2026-09-06 生产库
 * 真实画像分析一致，docs/agents/35-dashboard-stats.md）：
 * registered = 当日注册真实舞友（剔 ADMIN/开发号）；opened = 当日打卡去重用户
 * （登录自动打卡，仅代表打开过）；interactive = 当日真实互动用户（有效日活）；
 * noisy = 当日注册中「打卡型噪音」（注册后从未真实互动，疑似微信审核/自动巡检）。
 * noisy/registered = 当日注册噪音占比（前端派生，不做除法避免除零）。
 */
public record AdminDailyStatItem(
        /** 统计日（Asia/Shanghai 自然日） */
        LocalDate day,
        /** 当日注册数（role='USER' 且非 test_ 开发号，未软删） */
        long registered,
        /** 当日打开数 = 打卡去重用户（登录自动打卡口径） */
        long opened,
        /** 当日真实互动用户数（浏览/分享/收藏/表情/邀约/关注/上报等去重） */
        long interactive,
        /** 当日注册中「打卡型噪音」数（注册后从未真实互动） */
        long noisy
) {
}
