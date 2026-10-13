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
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.quwuting.quwutingservice.venuepresence.service.VenueVisitShare;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 到访归因口径（2026-10-03，52 号 §4）：命中半径、同址组、营业状态消歧、时间窗。
 * <p>
 * <b>2026-10-13 范围收窄</b>：归因（NONE/SHARED/ABSORBED/YIELDED）不再进任何展示数字——admin 与 C 侧已并入
 * 展示口径（{@code NearbyFootprint}，见 {@link VenueNearbyVisitsTest}）；归因只剩<b>热度排序</b>在用，
 * 所以这里的断言改为经 {@code visitSharesForRanking} 观察归因的<b>可见后果</b>（份额的人数与分摊），
 * 而不是对已删除的 admin 归因明细枚举做断言。
 * <p>
 * 现网事故还原：南通五洲国际广场「一壶淡泊（120，F2，暂停营业）」与「丽莎（121，3 层，营业中）」
 * 坐标完全重合，nearby 并列时 ping 恒落到 120——丽莎永远 0。修复后：同址证据先按营业状态归属
 * （一壶淡泊不在营 ⇒ 证据并入丽莎），双方都在营时才共享（用户并集，排序按 1/k 分摊）。
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

        Map<Long, VenueVisitShare> s = service.visitSharesForRanking(List.of(-1L));

        assertEquals(bd("2.00"), s.get(LISA).visitUsers30d(), "丽莎吸收同址暂停店上的证据，用户并集不重复计，且不被分摊");
        assertFalse(s.containsKey(YIHU), "暂停营业的一壶淡泊让渡证据 ⇒ 排序记 0（缺席）");
        assertEquals(bd("1.00"), s.get(ISOLATED).visitUsers30d(), "无同址邻居的店归因不变");
    }

    @Test
    void coLocatedVenuesBothOperatingShareUnionAndSplitWithoutDoubleCount() {
        stubPairs(pair(YIHU, "OPEN", LISA, "丽莎歌舞厅", "OPEN"),
                pair(LISA, "OPEN", YIHU, "一壶淡泊音乐酒吧", "OPEN"));
        stubHits(row(YIHU, 2L, now.minusHours(2)), row(YIHU, 210L, now.minusHours(1)), row(LISA, 2L, now));

        Map<Long, VenueVisitShare> s = service.visitSharesForRanking(List.of(-1L));

        // 都在营 = 定位分不出：证据并集 {2, 210}，排序按 1/k(k=2) 分摊 ⇒ 各 1.00（⛔ 不是各记 2 人）
        assertEquals(bd("1.00"), s.get(YIHU).visitUsers30d());
        assertEquals(bd("1.00"), s.get(LISA).visitUsers30d());
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

        Map<Long, VenueVisitShare> s = service.visitSharesForRanking(List.of(-1L));

        assertFalse(s.containsKey(NANLAIBEIWANG), "停业店不可能被到访：证据让渡给同楼在营门店");
        // 908 在两处都有证据只计 1：{908, 65, 331} = 3 人，在营 2 家 ⇒ 各 1.50
        assertEquals(bd("1.50"), s.get(XUNMENGYUAN).visitUsers30d());
        assertEquals(bd("1.50"), s.get(DOUWU).visitUsers30d());
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

        Map<Long, VenueVisitShare> s = service.visitSharesForRanking(List.of(-1L));

        assertEquals(bd("1.00"), s.get(YIHU).visitUsers30d(), "全组都不在营：无从归属，如实共享而不是让证据消失（分摊分母兜底为 1）");
    }

    @Test
    void windowsAreDecidedByLatestHitNotByCalendarDay() {
        stubPairs();
        stubHits(row(ISOLATED, 1L, now.minusDays(3)), row(ISOLATED, 2L, now.minusDays(10)),
                row(ISOLATED, 3L, now.minusDays(40)));

        VenueVisitShare s = service.visitSharesForRanking(List.of(-1L)).get(ISOLATED);

        assertEquals(bd("1.00"), s.visitUsers7d());
        assertEquals(bd("2.00"), s.visitUsers30d(), "40 天前的命中在 30 天窗外（SQL 已按窗口过滤，这里是内存二次判定的兜底）");
    }

    @Test
    void hitQueriesParseAsHql() {
        HqlSyntaxAssertions.assertParses(VenuePresencePingRepository.VISITOR_LAST_SEEN_SELECT
                + "WHERE p.venueId IN :venueIds AND " + VenuePresencePingRepository.HIT_PREDICATE
                + " GROUP BY p.venueId, p.userId");
        HqlSyntaxAssertions.assertParses(VenuePresencePingRepository.VISITOR_LAST_SEEN_SELECT
                + "WHERE " + VenuePresencePingRepository.HIT_PREDICATE + " GROUP BY p.venueId, p.userId");
    }

    @Test
    void rankingKeepsHitBandWhileDisplayUsesNearbyBand() {
        // 两条线的取数带必须不同：排序 = 命中带 150m（热度公式输入，改带宽 = 改榜单），
        // 展示 = 附近带 300m。若有人"顺手统一"成同一个值，这条测试先红
        assertTrue(VenuePresenceService.NEARBY_RADIUS_M > VenuePresenceService.HIT_RADIUS_M);
        stubPairs();
        stubHits(row(ISOLATED, 1L, now));

        service.visitSharesForRanking(List.of(-1L));

        org.mockito.Mockito.verify(pingRepository).findVisitorLastSeenSinceExcluding(
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                eq(VenuePresenceService.HIT_RADIUS_M), eq(VenuePresenceService.HIT_MAX_ACCURACY_M),
                anyCollection());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void stubPairs(VenueRepository.CoLocatedVenueRow... pairs) {
        when(venueRepository.findCoLocatedPairs(anyCollection(), eq(VenuePresenceService.CO_LOCATED_RADIUS_M)))
                .thenReturn(List.of(pairs));
    }

    /** 排序路径的取数：窗口内 + 排除后的 (venueId, userId, 最近命中时刻) */
    private void stubHits(Object[]... rows) {
        when(pingRepository.findVisitorLastSeenSinceExcluding(
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                eq(VenuePresenceService.HIT_RADIUS_M), anyInt(), anyCollection()))
                .thenReturn(List.of(rows));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static Object[] row(long venueId, long userId, LocalDateTime lastSeen) {
        return new Object[]{venueId, userId, lastSeen};
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
