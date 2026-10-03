package org.quwuting.quwutingservice.venuepresence.service;

import org.quwuting.quwutingservice.venuepresence.enums.CoLocatedAttribution;

import java.time.LocalDateTime;

/**
 * 单店到访摘要（2026-10-03，52 号 §6.1）：admin 门店列表行、足迹排序 / 筛选、详情统计卡
 * 三处消费<b>同一次</b>归因计算的结果（{@code VenuePresenceService#visitSummaries} /
 * {@code #visitedVenueSummaries}），禁止任何消费方另起一套到访计数。
 *
 * @param visitUsers7d         近 7 天到访人数（命中谓词 × 同址归因 × 用户并集）
 * @param visitUsers30d        近 30 天到访人数
 * @param lastVisitAt          最近一次到访时刻（同口径，不设时间窗）；null = 从无到访
 * @param coLocatedAttribution 同址归因方式
 * @param coLocatedCount       同址门店数（不含本店）
 */
public record VenueVisitSummary(
        long visitUsers7d,
        long visitUsers30d,
        LocalDateTime lastVisitAt,
        CoLocatedAttribution coLocatedAttribution,
        int coLocatedCount) {

    /** 无同址门店、无到访的店（批量结果里缺席的门店按此兜底） */
    public static final VenueVisitSummary EMPTY =
            new VenueVisitSummary(0, 0, null, CoLocatedAttribution.NONE, 0);
}
