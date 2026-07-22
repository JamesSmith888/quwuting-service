package org.quwuting.quwutingservice.venue.dto.response;

/**
 * 场所热度响应体（GET /venues/{id}/heat）。
 * <p>
 * 综合浏览量、收藏、评价、营业稳定性等多维度统计。
 * 权重公式收敛在 VenueHeatService 内部，前端只消费最终分值与已公开的分项统计。
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

        // ── 动态 ──
        /** 动态总数 */
        long postCount,
        /** 近30天新增动态 */
        long newPostCount30d,

        // ── 评价互动 ──
        /** 近30天评价数（维度评分记录数） */
        long ratingCount30d,
        /** 近30天点赞数 */
        long likeCount30d,

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
        String currentStatusDisplay
) {}
