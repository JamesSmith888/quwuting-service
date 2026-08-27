package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 每日打卡概览（管理端用户详情内嵌，2026-08-27 用户管理增强）。
 * <p>
 * 语义：qwt_daily_checkins 锚点表聚合（UNIQUE(user_id, checkin_date) 保证一人
 * 一天一行，条数 = 天数）——总天数反映活跃持续性；连续天数 = 从最近一次打卡
 * 往回数连续日期（今天或昨天为锚点，今天未打不打断连续）反映打卡习惯强度；
 * lastAt = 最近打卡时间。总天数与 ContributionBrief.checkInDays 同源（同一
 * 表同一口径，贡献档案嵌入时已含，此处为详情页打卡区块单独呈现）。
 */
public record CheckinSummary(
        /** 打卡总天数 */
        long totalDays,
        /** 连续打卡天数（今天或昨天为锚点往回数；0 = 无打卡） */
        long currentStreak,
        /** 最近打卡时间（null = 从未打卡） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastAt
) {}
