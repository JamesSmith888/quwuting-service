package org.quwuting.quwutingservice.venue.service;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.venue.enums.AdminVenueSort;
import org.quwuting.quwutingservice.venuepresence.service.NearbyFootprint;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 管理端门店列表足迹排序 / 筛选（2026-10-03，52 号 §6.1）：派生量稳定排序，并列按最近到访、
 * 再并列（含从无到访）回落默认序（id 倒序）。
 */
class AdminVenueFootprintOrderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 22, 0);

    /** 默认序 = id 倒序（findAdminIds 的输出形态） */
    private static final List<Long> DEFAULT_ORDER = List.of(900L, 800L, 700L, 600L, 500L);

    /** 800：3 人 / 1 小时前；600：3 人 / 2 天前；500：1 人 / 刚刚；700：仅 40 天前到访过（30 天 0 人）；900：从无 */
    private static final Map<Long, NearbyFootprint> VISITED = Map.of(
            800L, summary(3, NOW.minusHours(1)),
            600L, summary(3, NOW.minusDays(2)),
            500L, summary(1, NOW),
            700L, summary(0, NOW.minusDays(40)));

    @Test
    void visitsSortBreaksTiesByLastVisitAndPutsNeverVisitedLast() {
        assertEquals(List.of(800L, 600L, 500L, 700L, 900L),
                AdminVenueQueryService.orderByFootprint(DEFAULT_ORDER, VISITED, AdminVenueSort.VISITS_30D, false),
                "30 天人数倒序；并列（同 3 人 / 同 0 人）按最近到访倒序，从无到访的排最后");
    }

    @Test
    void lastVisitSortPutsNeverVisitedLast() {
        assertEquals(List.of(500L, 800L, 600L, 700L, 900L),
                AdminVenueQueryService.orderByFootprint(DEFAULT_ORDER, VISITED, AdminVenueSort.LAST_VISIT, false));
    }

    @Test
    void visitedOnlyMeansFootprintWithinThirtyDays() {
        assertEquals(List.of(800L, 600L, 500L),
                AdminVenueQueryService.orderByFootprint(DEFAULT_ORDER, VISITED, AdminVenueSort.LATEST, true),
                "只看有足迹 = 近 30 天附近足迹 > 0，与列表行「30 天」同一口径（= 小程序卡片是否渲染）；默认序不变");
    }

    private static NearbyFootprint summary(long uv30, LocalDateTime last) {
        return new NearbyFootprint(0, uv30, uv30, last, 0, 0);
    }
}
