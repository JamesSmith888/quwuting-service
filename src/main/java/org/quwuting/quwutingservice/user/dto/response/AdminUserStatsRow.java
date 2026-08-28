package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 管理端用户统计明细行（2026-08-28，GET /admin/users/{id}/stats-detail，docs/agents/23；
 * 仅 ADMIN）——用户详情页<b>每条统计数据可点击下钻</b>：查看该统计的每条详细列表。
 * <p>
 * 统一行结构（前端零分支渲染）：title（主标题：来源类型/舞伴名/门店名）+ subtitle
 * （副标题：金额/日期/状态）+ time（时间）+ 可选状态徽标（badgeText/badgeCls，
 * badge--warning/success/muted 全局配色类，空串 = 不渲染）。openId 绝不下发。
 */
public record AdminUserStatsRow(
        /** 源记录 id（需求单行 = qwt_demand_records.id，可跳转邀约单详情） */
        Long id,
        /** 主标题（积分来源中文 / 舞伴名 / 门店名 / "每日打卡"） */
        String title,
        /** 副标题（"12 积分 · 余额 30" / 日期 / 分享渠道） */
        String subtitle,
        /** 时间（yyyy-MM-dd HH:mm:ss；打卡等无时刻记录 = 日期字符串） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime time,
        /** 状态徽标文本（空串 = 不渲染徽标） */
        String badgeText,
        /** 状态徽标配色类（badge--warning/success/muted，空串 = 不渲染） */
        String badgeCls
) {}
