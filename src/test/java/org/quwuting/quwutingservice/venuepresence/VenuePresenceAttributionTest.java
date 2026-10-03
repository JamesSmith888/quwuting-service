package org.quwuting.quwutingservice.venuepresence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.support.HqlSyntaxAssertions;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceStats;
import org.quwuting.quwutingservice.venuepresence.enums.CoLocatedAttribution;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.quwuting.quwutingservice.venuepresence.service.VenueVisitSummary;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 到访归因口径（2026-10-03，52 号 §4）：命中半径、同址组、营业状态消歧、时间窗、两条汇总路径同源。
 * <p>
 * 现网事故还原：南通五洲国际广场「一壶淡泊（120，F2，暂停营业）」与「丽莎（121，3 层，营业中）」
 * 坐标完全重合，nearby 并列时 ping 恒落到 120——丽莎永远 0。修复后：同址证据先按营业状态归属
 * （一壶淡泊不在营 ⇒ 证据并入丽莎），双方都在营时才共享（用户并集）。
 */
@ExtendWith(MockitoExtension.class)
class VenuePresenceAttributionTest {

    private static final long YIHU = 120L;
    private static final long LISA = 121L;
    /** 咸阳「开心音乐酒吧」：50m 内无其他门店（2026-10-03 生产只读核对） */
    private static final long ISOLATED = 780L;
    /** 南通京扬广场同层三家：地址写法不同，坐标两两相距 27 / 39 / 44m（2026-10-03 生产只读核对） */
    private static final long XUNMENGYUAN = 13L;
    private static final long DOUWU = 14L;
    private static final long NANLAIBEIWANG = 111L;

    @Mock
    private VenuePresencePingRepository pingRepository;
    @Mock
    private VenuePresenceConsentRepository consentRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private OpsConfigService opsConfigService;

    @InjectMocks
    private VenuePresenceService service;

    private final LocalDateTime now = LocalDateTime.now();

    @Test
    void hitRadiusIsUserToVenueToleranceNotVenueSpacing() {
        // 两个量不得再混为一个：命中容差（用户↔坐标）必须远大于同址间距（门店↔门店）
        assertTrue(VenuePresenceService.HIT_RADIUS_M > VenuePresenceService.CO_LOCATED_RADIUS_M);
        // 现场实测 86~91m 必须命中
        assertTrue(VenuePresenceService.HIT_RADIUS_M >= 91);
        // 同楼不同地址写法的坐标散布实测 27~44m（南通京扬广场同层三家）必须并组
        assertTrue(VenuePresenceService.CO_LOCATED_RADIUS_M >= 44);
        // 精度门槛派生于命中半径（iOS 室内 65m 不得被剔除）
        assertEquals(VenuePresenceService.HIT_RADIUS_M, VenuePresenceService.HIT_MAX_ACCURACY_M);
        // 采集半径（写侧限幅）必须覆盖命中半径与附近半径，否则历史回溯无数据可用
        assertTrue(VenuePresenceService.WRITE_MAX_DISTANCE_M >= VenuePresenceService.NEARBY_RADIUS_M);
        assertTrue(VenuePresenceService.NEARBY_RADIUS_M > VenuePresenceService.HIT_RADIUS_M);
    }

    @Test
    void soleOperatingVenueAbsorbsEvidenceOfNonOperatingNeighbour() {
        stubPairs(pair(YIHU, "SUSPENDED", LISA, "丽莎歌舞厅", "OPEN"),
                pair(LISA, "OPEN", YIHU, "一壶淡泊音乐酒吧", "SUSPENDED"));
        // ping 全落在 120（并列序），用户 2 在两家都有记录、用户 210 只在 120
        stubHits(row(YIHU, 2L, now.minusHours(2)), row(YIHU, 210L, now.minusHours(1)),
                row(LISA, 2L, now.minusHours(3)), row(ISOLATED, 908L, now.minusDays(1)));

        Map<Long, VenueVisitSummary> s = service.visitSummaries(List.of(YIHU, LISA, ISOLATED));

        assertEquals(2L, s.get(LISA).visitUsers30d(), "丽莎吸收同址暂停店上的证据，用户并集不重复计");
        assertEquals(CoLocatedAttribution.ABSORBED, s.get(LISA).coLocatedAttribution());
        assertEquals(0L, s.get(YIHU).visitUsers30d(), "暂停营业的一壶淡泊让渡证据，不再与丽莎同数");
        assertEquals(CoLocatedAttribution.YIELDED, s.get(YIHU).coLocatedAttribution());
        assertNull(s.get(YIHU).lastVisitAt(), "让渡后的「最近到访」与人数同口径：从无到访");
        assertEquals(1L, s.get(ISOLATED).visitUsers30d(), "无同址邻居的店归因不变");
        assertEquals(CoLocatedAttribution.NONE, s.get(ISOLATED).coLocatedAttribution());
    }

    @Test
    void coLocatedVenuesBothOperatingShareUnionWithoutDoubleCount() {
        stubPairs(pair(YIHU, "OPEN", LISA, "丽莎歌舞厅", "OPEN"),
                pair(LISA, "OPEN", YIHU, "一壶淡泊音乐酒吧", "OPEN"));
        stubHits(row(YIHU, 2L, now.minusHours(2)), row(YIHU, 210L, now.minusHours(1)), row(LISA, 2L, now));

        Map<Long, VenueVisitSummary> s = service.visitSummaries(List.of(YIHU, LISA));

        assertEquals(2L, s.get(YIHU).visitUsers30d());
        assertEquals(2L, s.get(LISA).visitUsers30d(), "都在营 = 定位分不出：共享同一组用户，不是相加");
        assertEquals(CoLocatedAttribution.SHARED, s.get(LISA).coLocatedAttribution());
    }

    @Test
    void ceasedVenueInSameBuildingYieldsToBothOperatingVenuesWhichShare() {
        // 现网事故还原（2026-10-03）：京扬广场同层，寻梦缘与抖舞面对面、实际不到 10m；已停业的南来北往
        // 坐标恰好离楼里的用户最近 ⇒ 用户 331（距其坐标 20m、精度 15m）被记给停业店
        stubPairs(pair(XUNMENGYUAN, "OPEN", DOUWU, "抖舞跳舞俱乐部", "OPEN"),
                pair(XUNMENGYUAN, "OPEN", NANLAIBEIWANG, "南来北往酒吧", "CEASED"),
                pair(DOUWU, "OPEN", XUNMENGYUAN, "寻梦缘歌舞厅", "OPEN"),
                pair(DOUWU, "OPEN", NANLAIBEIWANG, "南来北往酒吧", "CEASED"),
                pair(NANLAIBEIWANG, "CEASED", XUNMENGYUAN, "寻梦缘歌舞厅", "OPEN"),
                pair(NANLAIBEIWANG, "CEASED", DOUWU, "抖舞跳舞俱乐部", "OPEN"));
        stubHits(row(XUNMENGYUAN, 908L, now.minusDays(1)), row(DOUWU, 65L, now.minusDays(1)),
                row(NANLAIBEIWANG, 331L, now.minusDays(2)), row(NANLAIBEIWANG, 908L, now.minusDays(3)));

        Map<Long, VenueVisitSummary> s = service.visitSummaries(List.of(XUNMENGYUAN, DOUWU, NANLAIBEIWANG));

        assertEquals(0L, s.get(NANLAIBEIWANG).visitUsers30d(), "停业店不可能被到访：证据让渡给同楼在营门店");
        assertEquals(CoLocatedAttribution.YIELDED, s.get(NANLAIBEIWANG).coLocatedAttribution());
        assertEquals(3L, s.get(XUNMENGYUAN).visitUsers30d(), "908 在两处都有证据只计 1：{908, 65, 331}");
        assertEquals(3L, s.get(DOUWU).visitUsers30d(), "两家都在营 = 定位分不开：共享同一组用户，不可相加");
        assertEquals(CoLocatedAttribution.SHARED, s.get(XUNMENGYUAN).coLocatedAttribution());
        assertEquals(CoLocatedAttribution.SHARED, s.get(DOUWU).coLocatedAttribution());
    }

    @Test
    void closedTodayStillCountsAsInOperationButLongTermClosureDoesNot() {
        // 休息中是短期态（今天不开 ≠ 店不存在）；装修 / 暂停 / 停业才让渡
        assertTrue(VenuePresenceService.isInOperation(VenueStatus.OPEN));
        assertTrue(VenuePresenceService.isInOperation(VenueStatus.CLOSED));
        assertFalse(VenuePresenceService.isInOperation(VenueStatus.RENOVATING));
        assertFalse(VenuePresenceService.isInOperation(VenueStatus.SUSPENDED));
        assertFalse(VenuePresenceService.isInOperation(VenueStatus.CEASED));
    }

    @Test
    void allNonOperatingGroupFallsBackToSharing() {
        stubPairs(pair(YIHU, "CEASED", LISA, "丽莎歌舞厅", "SUSPENDED"));
        stubHits(row(YIHU, 2L, now));

        VenueVisitSummary s = service.visitSummaries(List.of(YIHU)).get(YIHU);

        assertEquals(1L, s.visitUsers30d(), "全组都不在营：无从归属，如实共享而不是让证据消失");
        assertEquals(CoLocatedAttribution.SHARED, s.coLocatedAttribution());
    }

    @Test
    void windowsAreDecidedByLatestHitNotByCalendarDay() {
        stubPairs();
        stubHits(row(ISOLATED, 1L, now.minusDays(3)), row(ISOLATED, 2L, now.minusDays(10)),
                row(ISOLATED, 3L, now.minusDays(40)));

        VenueVisitSummary s = service.visitSummaries(List.of(ISOLATED)).get(ISOLATED);

        assertEquals(1L, s.visitUsers7d());
        assertEquals(2L, s.visitUsers30d());
        assertEquals(now.minusDays(3), s.lastVisitAt(), "最近到访不设时间窗");
    }

    @Test
    void statsEchoesAttributionAndKeepsNearbyAsAreaCoverage() {
        stubPairs(pair(YIHU, "SUSPENDED", LISA, "丽莎歌舞厅", "OPEN"));
        List<Collection<Long>> queried = new ArrayList<>();
        when(pingRepository.findVisitorLastSeenByVenueIds(anyCollection(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    queried.add(List.copyOf(inv.<Collection<Long>>getArgument(0)));
                    return rows(row(LISA, 2L, now));
                });

        VenuePresenceStats stats = service.statsFor(YIHU);

        assertEquals(0L, stats.visitUsers30d());
        assertEquals(1L, stats.nearbyUsers30d(), "附近是片区语义：让渡不影响「这一带出现过多少人」");
        assertEquals(CoLocatedAttribution.YIELDED, stats.coLocatedAttribution());
        assertEquals(List.of(new VenuePresenceStats.CoLocatedVenue(LISA, "丽莎歌舞厅", "营业中", true)),
                stats.coLocatedVenues());
        assertEquals(1, queried.size(), "YIELDED 无到访证据：只查一次（片区），不查空集合");
        assertEquals(Set.of(YIHU, LISA), Set.copyOf(queried.get(0)));
    }

    @Test
    void globalRankingPathAgreesWithPagePathAndSurfacesAbsorbingNeighbour() {
        // 全量证据只在暂停的 120 上：丽莎经同址并入获得到访，必须进入排序候选
        when(pingRepository.findVisitorLastSeen(anyInt(), anyInt()))
                .thenReturn(rows(row(YIHU, 2L, now), row(YIHU, 210L, now.minusDays(2))));
        when(venueRepository.findCoLocatedPairs(anyCollection(), eq(VenuePresenceService.CO_LOCATED_RADIUS_M)))
                .thenAnswer(inv -> {
                    Collection<Long> ids = inv.getArgument(0);
                    List<VenueRepository.CoLocatedVenueRow> out = new ArrayList<>();
                    if (ids.contains(YIHU)) {
                        out.add(pair(YIHU, "SUSPENDED", LISA, "丽莎歌舞厅", "OPEN"));
                    }
                    if (ids.contains(LISA)) {
                        out.add(pair(LISA, "OPEN", YIHU, "一壶淡泊音乐酒吧", "SUSPENDED"));
                    }
                    return out;
                });

        Map<Long, VenueVisitSummary> visited = service.visitedVenueSummaries();

        assertEquals(Set.of(LISA), visited.keySet(), "让渡方从无到访 ⇒ 不在稀疏结果里");
        assertEquals(2L, visited.get(LISA).visitUsers30d());
    }

    @Test
    void hitQueriesParseAsHql() {
        HqlSyntaxAssertions.assertParses(VenuePresencePingRepository.VISITOR_LAST_SEEN_SELECT
                + "WHERE p.venueId IN :venueIds AND " + VenuePresencePingRepository.HIT_PREDICATE
                + " GROUP BY p.venueId, p.userId");
        HqlSyntaxAssertions.assertParses(VenuePresencePingRepository.VISITOR_LAST_SEEN_SELECT
                + "WHERE " + VenuePresencePingRepository.HIT_PREDICATE + " GROUP BY p.venueId, p.userId");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void stubPairs(VenueRepository.CoLocatedVenueRow... pairs) {
        when(venueRepository.findCoLocatedPairs(anyCollection(), eq(VenuePresenceService.CO_LOCATED_RADIUS_M)))
                .thenReturn(List.of(pairs));
    }

    private void stubHits(Object[]... rows) {
        when(pingRepository.findVisitorLastSeenByVenueIds(anyCollection(), eq(VenuePresenceService.HIT_RADIUS_M), anyInt()))
                .thenReturn(rows(rows));
    }

    private static Object[] row(long venueId, long userId, LocalDateTime lastSeen) {
        return new Object[]{venueId, userId, lastSeen};
    }

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    private static VenueRepository.CoLocatedVenueRow pair(long venueId, String venueStatus,
                                                          long coLocatedId, String name, String coLocatedStatus) {
        return new VenueRepository.CoLocatedVenueRow() {
            @Override
            public Long getVenueId() {
                return venueId;
            }

            @Override
            public String getVenueStatus() {
                return venueStatus;
            }

            @Override
            public Long getCoLocatedId() {
                return coLocatedId;
            }

            @Override
            public String getCoLocatedName() {
                return name;
            }

            @Override
            public String getCoLocatedStatus() {
                return coLocatedStatus;
            }
        };
    }
}
