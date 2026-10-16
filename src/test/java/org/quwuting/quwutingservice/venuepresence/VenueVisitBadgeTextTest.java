package org.quwuting.quwutingservice.venuepresence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.venue.service.HeatAccountExclusionService;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenueVisitFootprintResponse;
import org.quwuting.quwutingservice.venuepresence.repository.VenueVisitThankRepository;
import org.quwuting.quwutingservice.venuepresence.service.NearbyFootprint;
import org.quwuting.quwutingservice.venuepresence.service.NearbyVisitSummary;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.quwuting.quwutingservice.venuepresence.service.VenueVisitBadgeService;
import org.quwuting.quwutingservice.venuepresence.service.ViewerFootprint;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @Mock
    private VenueVisitThankRepository visitThankRepository;

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

    // ── 展示口径一源：admin 面与 C 侧共用同一渲染函数与同一排除集合（2026-10-13） ──

    @Test
    void adminEchoesTheExactTextTheMiniProgramCardShows() {
        LocalDateTime recent = LocalDateTime.now().minusDays(1);
        // 同样的人数 / 次数，admin 回显的原文 = C 侧文案（同一渲染函数，所见即所得）
        assertEquals("感谢 6 位舞友 · 10 次到过这附近的足迹",
                badgeService.badgeTextOf(new NearbyFootprint(2, 6, 10, recent, 0, 1)));
        assertEquals("感谢 2 位舞友来过这附近",
                badgeService.badgeTextOf(new NearbyFootprint(1, 2, 3, recent, 0, 0)));
        // 30 天内无足迹 ⇒ 卡片不渲染 ⇒ admin 回显 null（窗外老足迹只体现在「最近」列，不冒充卡片文案）
        assertNull(badgeService.badgeTextOf(new NearbyFootprint(0, 0, 0, recent.minusDays(60), 0, 0)));
        assertNull(badgeService.badgeTextOf(null));
    }

    @Test
    void cardAndAdminReadTheSameExclusionSet() {
        // 「同一集合」不靠约定：两个面都经 displayExclusions() 取，这里钉住传给事实层的就是同一个集合
        List<Long> exclusions = List.of(-1L, 7L);
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(exclusions);

        badgeService.visitBadgeTextsByVenue(List.of(120L));
        badgeService.footprintsForAdmin(List.of(120L));
        badgeService.visitedFootprintsForAdmin();

        verify(venuePresenceService).nearbyVisitSummaries(anyCollection(), eq(exclusions));
        verify(venuePresenceService).nearbyFootprints(anyCollection(), eq(exclusions));
        verify(venuePresenceService).visitedNearbyFootprints(eq(exclusions));
    }

    // ── 详情页「到店足迹」行 + 贡献者回路（2026-10-16） ─────────────────────────────

    private static final long ME = 7L;
    private static final LocalDateTime RECENT = LocalDateTime.now().minusDays(1);

    @Test
    void detailBadgeTextIsByteIdenticalToTheListCardText() {
        // 同一家店、同一份事实：详情页 badgeText 与列表卡片文案必须逐字相同（展示口径一源，
        // ⛔ 详情页不得另拼一份——否则列表写 6 位、详情写 5 位这类割裂又会出现）
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(List.of(-1L));
        NearbyFootprint fact = new NearbyFootprint(2, 6, 10, RECENT, 0, 1);
        when(venuePresenceService.nearbyVisitSummaries(anyCollection(), anyCollection()))
                .thenReturn(Map.of(120L, fact.toDisplaySummary()));
        when(venuePresenceService.nearbyFootprintForViewer(eq(120L), eq(ME), anyCollection()))
                .thenReturn(new ViewerFootprint(fact, null));

        String listText = badgeService.visitBadgeTextsByVenue(List.of(120L)).get(120L);
        VenueVisitFootprintResponse detail = badgeService.footprintViewOf(120L, ME);

        assertEquals(listText, detail.badgeText(), "详情页足迹行 ≡ 列表卡片文案（同一渲染函数）");
    }

    @Test
    void detailAndCardReadTheSameExclusionSet() {
        List<Long> exclusions = List.of(-1L, 9L);
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(exclusions);
        when(venuePresenceService.nearbyFootprintForViewer(eq(120L), eq(ME), anyCollection()))
                .thenReturn(new ViewerFootprint(NearbyFootprint.EMPTY, null));

        badgeService.footprintViewOf(120L, ME);

        verify(venuePresenceService).nearbyFootprintForViewer(120L, ME, exclusions);
    }

    @Test
    void contributorSeesTheirOwnLineWithThanksCountFromThisVenue() {
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(List.of(-1L));
        when(venuePresenceService.nearbyFootprintForViewer(eq(121L), eq(ME), anyCollection()))
                .thenReturn(new ViewerFootprint(new NearbyFootprint(2, 5, 8, RECENT, 0, 1),
                        new ViewerFootprint.Mine(3)));
        // 站内信正文的 N 与这里取的是同一个数（countThankers(本店, 我)）——点信落在本页，数字必须逐字一致
        when(visitThankRepository.countThankers(121L, ME)).thenReturn(2L);

        VenueVisitFootprintResponse resp = badgeService.footprintViewOf(121L, ME);

        assertEquals("你在这附近留下过 3 次足迹 · 2 位舞友感谢了你", resp.mineText());
        assertEquals("感谢 5 位舞友 · 8 次到过这附近的足迹", resp.badgeText());
    }

    @Test
    void notYetThankedContributorGetsNoGapMessage() {
        assertEquals("你在这附近留下过 1 次足迹", VenueVisitBadgeService.renderMineText(1, 0),
                "没人感谢时只陈述自己的痕迹——⛔ 不写「暂无人感谢」：把没有的东西说成缺口等于催促去要感谢");
        assertEquals("你在这附近留下过 12 次足迹 · 1 位舞友感谢了你", VenueVisitBadgeService.renderMineText(12, 1));
    }

    @Test
    void mineLineNeverUsesTheShareWordOrNamesAnyone() {
        for (String text : List.of(VenueVisitBadgeService.renderMineText(1, 0),
                VenueVisitBadgeService.renderMineText(5, 3))) {
            assertFalse(text.contains("分享"),
                    "⛔ 禁用「分享」：用户没有主动外发任何东西，与首问承诺的「不会对外展示」观感冲突");
            assertFalse(text.contains("真实"), "「真实」措辞已退役（2026-10-08），不得以任何形态回填");
        }
    }

    @Test
    void anonymousAndNonContributorViewersGetNoMineLineAndZeroThanksQueries() {
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(List.of(-1L));
        NearbyFootprint fact = new NearbyFootprint(1, 3, 3, RECENT, 0, 0);
        when(venuePresenceService.nearbyFootprintForViewer(eq(120L), eq(null), anyCollection()))
                .thenReturn(new ViewerFootprint(fact, null));
        when(venuePresenceService.nearbyFootprintForViewer(eq(120L), eq(99L), anyCollection()))
                .thenReturn(new ViewerFootprint(fact, null));

        VenueVisitFootprintResponse guest = badgeService.footprintViewOf(120L, null);
        VenueVisitFootprintResponse stranger = badgeService.footprintViewOf(120L, 99L);

        assertNull(guest.mineText(), "游客：没有「自己」可说");
        assertNull(stranger.mineText(), "登录但不在被计入的贡献者之列：不对他说这句话");
        assertEquals(guest.badgeText(), stranger.badgeText(), "足迹行原文是公开信息，对所有人相同");
        verify(visitThankRepository, never()).countThankers(anyLong(), anyLong());
    }

    @Test
    void venueWithoutFootprintsRendersNothingAtAll() {
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(List.of(-1L));
        when(venuePresenceService.nearbyFootprintForViewer(eq(120L), eq(ME), anyCollection()))
                .thenReturn(new ViewerFootprint(NearbyFootprint.EMPTY, null));

        VenueVisitFootprintResponse resp = badgeService.footprintViewOf(120L, ME);

        assertNull(resp.badgeText(), "附近无足迹 ⇒ 详情页整行不渲染（与列表同一硬约束）");
        assertNull(resp.mineText());
    }
}
