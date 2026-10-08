package org.quwuting.quwutingservice.venuepresence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.venue.service.HeatAccountExclusionService;
import org.quwuting.quwutingservice.venuepresence.service.NearbyVisitSummary;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.quwuting.quwutingservice.venuepresence.service.VenueVisitBadgeService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 到店足迹行文案映射（2026-10-08，52 号「C 侧展示：附近的足迹」节）。
 * <p>
 * 两条硬约束在此钉住：① 文案恒为「附近」语义——⛔ <b>任何情况下都不出现「真实」</b>
 * （五洲事故 + V40 映射接反复盘的直接沉淀）；② 同一附近范围内的多家门店文案逐字一致（「都显示」）。
 */
@ExtendWith(MockitoExtension.class)
class VenueVisitBadgeTextTest {

    @Mock
    private VenuePresenceService venuePresenceService;
    @Mock
    private HeatAccountExclusionService heatAccountExclusionService;

    @InjectMocks
    private VenueVisitBadgeService badgeService;

    @Test
    void rendersNearbyWordingWithUnionNumbersForEveryStoreInRange() {
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(List.of(-1L));
        when(venuePresenceService.nearbyVisitSummaries(anyCollection(), anyCollection()))
                .thenReturn(Map.of(120L, new NearbyVisitSummary(6, 10), 121L, new NearbyVisitSummary(6, 10)));

        Map<Long, String> badges = badgeService.visitBadgeTextsByVenue(List.of(120L, 121L));

        assertEquals("感谢 6 位舞友 · 10 次到过这附近的足迹", badges.get(120L));
        assertEquals(badges.get(120L), badges.get(121L),
                "同一范围内的门店文案逐字一致——「范围内的店我们都显示为附近的足迹」");
    }

    @Test
    void smallCountsUseShortFormAndWordingNeverClaimsRealStoreVisit() {
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(List.of(-1L));
        when(venuePresenceService.nearbyVisitSummaries(anyCollection(), anyCollection()))
                .thenReturn(Map.of(
                        1L, new NearbyVisitSummary(1, 1),
                        2L, new NearbyVisitSummary(2, 3),
                        3L, new NearbyVisitSummary(3, 3),
                        4L, new NearbyVisitSummary(12, 25)));

        Map<Long, String> badges = badgeService.visitBadgeTextsByVenue(List.of(1L, 2L, 3L, 4L));

        assertEquals("感谢 1 位舞友来过这附近", badges.get(1L), "1~2 人省「次」（两个数字同数零信息量）");
        assertEquals("感谢 2 位舞友来过这附近", badges.get(2L));
        assertEquals("感谢 3 位舞友 · 3 次到过这附近的足迹", badges.get(3L), "≥3 人亮出「次」");
        assertEquals("感谢 12 位舞友 · 25 次到过这附近的足迹", badges.get(4L));

        for (String text : badges.values()) {
            assertFalse(text.contains("真实"),
                    "「真实到店足迹」措辞已退役（2026-10-08 用户裁决：只承诺是附近）——不得以任何形态回填");
            assertTrue(text.contains("附近"), "展示语义恒为「附近」");
        }
    }

    @Test
    void storesWithoutNearbyFootprintsAreNotRendered() {
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(List.of(-1L));
        when(venuePresenceService.nearbyVisitSummaries(anyCollection(), anyCollection()))
                .thenReturn(Map.of());

        assertEquals(Map.of(), badgeService.visitBadgeTextsByVenue(List.of(120L)),
                "附近无足迹 = 缺席（前端按 null 不渲染，卡片零布局变化）");
    }

    @Test
    void emptyPageShortCircuitsWithoutTouchingDependencies() {
        // 空页直接返回：不查事实、不取排除集合（省一次 caching 查询）
        assertEquals(Map.of(), badgeService.visitBadgeTextsByVenue(List.of()));
    }
}
