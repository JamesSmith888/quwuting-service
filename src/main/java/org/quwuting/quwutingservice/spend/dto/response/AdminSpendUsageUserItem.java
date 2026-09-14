package org.quwuting.quwutingservice.spend.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端「记账用户」列表项（2026-09-14，GET /admin/spend/usage-users；仅 ADMIN）。
 * <p>
 * 口径权威 = {@code SpendStatsRepository}（docs/agents/35-dashboard-stats.md）：
 * 有账目（未软删）的去重真实用户——剔 ADMIN 运营号 / test_ 开发联调号 /
 * 微信审核账号（wechat_review=true）。逐用户聚合笔数 / 计时结算笔数 /
 * 手动笔数 / 收支金额 / 最近记账时刻；admin-web「记账用户」列表数据源，
 * 行点击下钻用户详情（复用资料协作 /users/{id} 路由）。
 */
public record AdminSpendUsageUserItem(
        /** 用户 id（下钻 user-detail 路由参数） */
        long userId,
        /** 昵称（可空 = 未署名，前端兜底文案） */
        String nickname,
        /** 头像 URL（可空 = 未设置，前端占位首字） */
        String avatarUrl,
        /** 账目总笔数（未软删） */
        long entryCount,
        /** 计时结算笔数（source=DANCE，≈ 计时场次） */
        long danceEntries,
        /** 手动补记笔数（source=MANUAL） */
        long manualEntries,
        /** 支出总金额（EXPENSE，元） */
        BigDecimal expenseTotal,
        /** 收入总金额（INCOME，元） */
        BigDecimal incomeTotal,
        /** 最近记账时刻（账目业务 ts 的 MAX） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastEntryAt
) {
}
