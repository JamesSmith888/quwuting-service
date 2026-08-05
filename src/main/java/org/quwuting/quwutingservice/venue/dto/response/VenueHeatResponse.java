package org.quwuting.quwutingservice.venue.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场所热度响应体（GET /venues/{id}/heat）。
 * <p>
 * 综合浏览量、收藏、评价、营业稳定性等多维度统计。
 * 权重公式与公式文案（formulaText/formulaDetail）收敛在 VenueHeatService 内部——
 * 前端直接渲染公式文案，禁止硬编码权重（2026-08 确立，消灭"权重调整后展示失真"）。
 */
public record VenueHeatResponse(
        /** 综合热度指数（加权公式见 VenueHeatService） */
        long heatScore,

        // ── 浏览 ──
        /** 近30天浏览量（含匿名，UV+PV 混合口径） */
        long viewCount30d,
        /** 近30天独立用户浏览数（仅已登录用户去重 UV） */
        long viewUv30d,

        // ── 收藏 ──
        /** 收藏总数 */
        long favoriteCount,
        /** 近30天新增收藏 */
        long newFavoriteCount30d,
        /** 近14天每日新增收藏趋势（收藏趋势图用），按日期升序，无收藏的日期已补零 */
        List<FavoriteTrendPoint> favoriteTrend,

        // ── 动态 ──
        /** 动态总数 */
        long postCount,
        /** 近30天新增动态 */
        long newPostCount30d,

        // ── 评价互动 ──
        /** 近30天评分数（按 created_at 窗口，改分不刷新窗口，防刷分） */
        long ratingCount30d,
        /** 近30天正向 Reaction 数（仅 Polarity.POSITIVE，热度公式计入项） */
        long positiveReactionCount30d,
        /** 近30天负向 Reaction 数（仅 Polarity.NEGATIVE，不计入公式，供详情页展示负面信号） */
        long negativeReactionCount30d,

        // ── 满意度 ──
        /** 综合满意度（1-10，各维度等权均分），评价人数不足时为 null */
        Double satisfactionScore,
        /** 评价总人数（去重用户） */
        long ratingTotalCount,

        // ── 营业稳定性 ──
        /** 近30天暂停营业次数（状态变更为 SUSPENDED 的次数） */
        long suspensionCount30d,
        /** 当前状态持续天数 */
        long currentStatusDays,
        /** 当前状态枚举值 */
        String currentStatus,
        /** 当前状态展示名 */
        String currentStatusDisplay,

        /**
         * 状态可信度等级（HIGH / MEDIUM / LOW），由「当前状态持续天数」与「近30天暂停次数」二维矩阵派生。
         * 稳定门店（30天内0次暂停）恒为 HIGH；不稳定 + 状态持续 ≤7天为 MEDIUM；不稳定 + >7天为 LOW。
         * 前端据此渲染颜色分级与行动建议，不持有判定逻辑。
         */
        String statusConfidence,

        // ── 用户实时状态报告（独立信号层，不修改 Venue.status） ──
        /**
         * 活跃报告数：最近 TTL（当前 4 小时）内用户上报"暂停营业"的数量。
         * 与 suspensionCount30d（管理通道审计）不同，这是众包实时信号。
         * 当此值 > 0 时，statusConfidence 被 override 为 LOW（见 VenueHeatService）。
         */
        int activeReportCount,

        /**
         * 最新活跃报告时间（用于"X分钟前"展示）。
         * null = 无活跃报告。此时间是实时事实，不受 statsAsOfDate 窗口约束。
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime latestReportTime,

        // ── 公式文案（后端生成，前端直接渲染） ──
        /** 热度公式简述（含实际数据，如 "1520 = 120×1 + 30×10 + 5×15 + 2×5 + 4×8 + 10×3 + 2.5×20"） */
        String formulaText,
        /** 热度公式详情（问号弹窗完整说明，含满意度中性偏移规则与负向反馈说明） */
        String formulaDetail,

        /**
         * 滚动窗口统计口径的截止日期（yyyy-MM-dd，即"昨天"）。
         * 除 currentStatusDays/currentStatus/activeReportCount/latestReportTime 外的所有统计字段
         * 均只统计到该日期 24 点为止，不含当天尚未走完的数据。
         * 前端必须在页面醒目展示该字段，避免用户误将"半天数据"当作完整趋势解读。
         */
        String statsAsOfDate
) {}
