package org.quwuting.quwutingservice.venuepresence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.quwuting.quwutingservice.venuepresence.service.NearbyFootprint;
import org.quwuting.quwutingservice.venuepresence.service.NearbyVisitSummary;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.quwuting.quwutingservice.venuepresence.service.ViewerFootprint;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C 侧「附近的足迹」展示口径（2026-10-08，52 号「C 侧展示：附近的足迹」节）。
 * <p>
 * 事故还原：南通五洲国际广场「一壶淡泊（120，F2，暂停营业）」与「丽莎（121，3 层，营业中）」
 * 地址级编码锚点相距 95m——> 同址半径 50m、≤ 展示半径 150m。单店坐标维护后旧实现把全部历史
 * 足迹「算到一壶淡泊头上」（丽莎不再显示）；新口径：范围内所有门店（含停业店）显示同一份
 * 附近并集，不做门店级归属、不看营业状态。
 * <p>
 * <b>2026-10-09 口径修订</b>：展示取数半径由命中带（150m）扩至「附近带」
 * （{@code NEARBY_RADIUS_M}=300m）——150~300m 段的足迹同样计入（用户裁决；判例 = user 210
 * 在丽莎 295m 上报、旧口径下被截掉的那条）。精度门槛不随半径放宽。
 */
@ExtendWith(MockitoExtension.class)
class VenueNearbyVisitsTest {

    private static final long YIHU = 120L;
    private static final long LISA = 121L;

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
    void unionRadiusCoversSameMallScatterAndStaysInsideAreaRadius() {
        // 展示半径必须覆盖五洲实测 95m 与同综合体地址级编码上界 146m（新世界广场）
        assertTrue(VenuePresenceService.NEARBY_TRACE_RADIUS_M >= 146,
                "同综合体/同楼对散布实测上界 146m 必须并入（2026-10-08 标定）");
        // 展示半径比排序同址半径宽（两值回答两个问题：50 管分摊公平、150 管声明诚实）
        assertTrue(VenuePresenceService.NEARBY_TRACE_RADIUS_M > VenuePresenceService.CO_LOCATED_RADIUS_M);
        // 不越过「片区」半径（否则整个商圈合成一个展示单元）
        assertTrue(VenuePresenceService.NEARBY_TRACE_RADIUS_M < VenuePresenceService.NEARBY_RADIUS_M);
        // 取数半径（2026-10-09）= 附近带：必须覆盖判例 295m（user 210 在丽莎）
        assertTrue(VenuePresenceService.NEARBY_RADIUS_M >= 295,
                "150~300m「附近」段必须计入展示（user 210 在丽莎 295m 的判例）");
    }

    @Test
    void everyStoreInRangeShowsSameUnionIncludingClosed() {
        // 五洲双店（120 暂停营业、121 营业中）互为同组：ping 全落在 120，两家都要显示并集
        stubPairs(pair(YIHU, "SUSPENDED", LISA, "丽莎歌舞厅", "OPEN"),
                pair(LISA, "OPEN", YIHU, "一壶淡泊音乐酒吧", "SUSPENDED"));
        stubFacts(row(YIHU, 2L, now.minusDays(1)), row(YIHU, 210L, now.minusDays(2)));
        stubDays(day(YIHU, 2L, now.toLocalDate().minusDays(1)), day(YIHU, 210L, now.toLocalDate().minusDays(2)));

        Map<Long, NearbyVisitSummary> s = service.nearbyVisitSummaries(List.of(YIHU, LISA), List.of(-1L));

        assertEquals(Set.of(YIHU, LISA), s.keySet(),
                "范围内的店都显示——含停业的一壶淡泊（营业状态不参与展示判定）");
        assertEquals(2L, s.get(YIHU).visitUsers());
        assertEquals(2L, s.get(LISA).visitUsers(), "丽莎同样拿到整组并集：无「让渡」，不做门店级归属");
        assertEquals(2L, s.get(LISA).visitEvents());
    }

    @Test
    void unionCountsEachUserOnceAndKeepsEventDaysDistinctPerUser() {
        stubPairs(pair(YIHU, "OPEN", LISA, "丽莎歌舞厅", "OPEN"),
                pair(LISA, "OPEN", YIHU, "一壶淡泊音乐酒吧", "OPEN"));
        // u1 在两家都有命中：人数只计 1；u2 只在 121
        stubFacts(row(YIHU, 1L, now.minusDays(1)), row(LISA, 1L, now.minusDays(1)),
                row(LISA, 2L, now.minusDays(1)));
        LocalDate today = now.toLocalDate();
        stubDays(day(YIHU, 1L, today.minusDays(1)), day(LISA, 1L, today.minusDays(1)), day(LISA, 2L, today.minusDays(1)));

        Map<Long, NearbyVisitSummary> s = service.nearbyVisitSummaries(List.of(YIHU, LISA), List.of(-1L));

        assertEquals(2L, s.get(LISA).visitUsers(), "同一用户在两家都有命中只计 1 人（并集，⛔ 不能相加）");
        assertEquals(2L, s.get(LISA).visitEvents(), "(人, 日) 二元组并集 = 2（u1 同日在两家只算 1 次）");
    }

    @Test
    void windowMembershipIsDecidedByLatestHit() {
        stubPairs();
        // u2 最近命中在 40 天前 ⇒ 不在 30 天窗内；u1 在 3 天前 ⇒ 在
        stubFacts(row(YIHU, 1L, now.minusDays(3)), row(YIHU, 2L, now.minusDays(40)));
        // 到访日查询带 since ⇒ 真实响应不会含窗口外日；此处只回传窗口内的 u1
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(3)));

        Map<Long, NearbyVisitSummary> s = service.nearbyVisitSummaries(List.of(YIHU), List.of(-1L));

        assertEquals(1L, s.get(YIHU).visitUsers());
        assertEquals(1L, s.get(YIHU).visitEvents());
    }

    @Test
    void excludedUsersAreRemovedFromBothCounts() {
        stubPairs();
        stubFacts(row(YIHU, 1L, now.minusDays(1)), row(YIHU, 2L, now.minusDays(1)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(1)), day(YIHU, 2L, now.toLocalDate().minusDays(1)));

        Map<Long, NearbyVisitSummary> s = service.nearbyVisitSummaries(List.of(YIHU), List.of(1L));

        assertEquals(1L, s.get(YIHU).visitUsers(), "排除账号从人数剔除（与排序口径同一集合）");
        assertEquals(1L, s.get(YIHU).visitEvents(), "次数同样剔除——两个数字同源同集合");
    }

    @Test
    void storeWithoutNearbyFootprintsIsAbsentFromResult() {
        stubPairs();
        stubFacts(row(LISA, 9L, now.minusDays(1)));
        stubDays(day(LISA, 9L, now.toLocalDate().minusDays(1)));

        Map<Long, NearbyVisitSummary> s = service.nearbyVisitSummaries(List.of(YIHU, LISA), List.of(-1L));

        assertEquals(Set.of(LISA), s.keySet(), "附近无足迹的门店缺席（= 前端不渲染，唯一硬约束）");
    }

    @Test
    void batchQueriesUseWhitelistedStoreScopes() {
        // 一次同址查询覆盖整页、一次事实查询覆盖全部范围内门店（防 N+1 的形态断言）
        List<Collection<Long>> vicinityQueries = new ArrayList<>();
        when(venueRepository.findCoLocatedPairs(anyCollection(), eq(VenuePresenceService.NEARBY_TRACE_RADIUS_M)))
                .thenAnswer(inv -> {
                    vicinityQueries.add(List.copyOf(inv.<Collection<Long>>getArgument(0)));
                    return List.of(pair(YIHU, "OPEN", LISA, "丽莎歌舞厅", "OPEN"),
                            pair(LISA, "OPEN", YIHU, "一壶淡泊音乐酒吧", "OPEN"));
                });
        List<Collection<Long>> factQueries = new ArrayList<>();
        when(pingRepository.findVisitorLastSeenByVenueIds(anyCollection(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    factQueries.add(List.copyOf(inv.<Collection<Long>>getArgument(0)));
                    return rows(row(YIHU, 1L, now.minusDays(1)));
                });
        when(pingRepository.findVisitorDaysByVenueIdsSince(anyCollection(), any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(rows(day(YIHU, 1L, now.toLocalDate().minusDays(1))));

        service.nearbyVisitSummaries(List.of(YIHU, LISA), List.of(-1L));

        assertEquals(List.of(List.of(YIHU, LISA)), vicinityQueries, "同址查询一次覆盖整页");
        assertEquals(1, factQueries.size(), "事实查询只发一次（批量）");
        assertEquals(Set.of(YIHU, LISA), Set.copyOf(factQueries.get(0)),
                "证据范围 = 范围并集（两店互为邻居时含两家）");
    }

    @Test
    void displayFetchRadiusCoversNearbyBandWithHitAccuracyGate() {
        // 2026-10-09：展示取数 = 附近带半径（300m，含 150~300m「附近」段）；
        // 精度门槛保持 HIT_MAX_ACCURACY_M（数据质量门槛不随半径放宽）
        stubPairs();
        stubFacts(row(YIHU, 1L, now.minusDays(1)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(1)));

        service.nearbyVisitSummaries(List.of(YIHU), List.of(-1L));

        verify(pingRepository).findVisitorLastSeenByVenueIds(anyCollection(),
                eq(VenuePresenceService.NEARBY_RADIUS_M), eq(VenuePresenceService.HIT_MAX_ACCURACY_M));
        verify(pingRepository).findVisitorDaysByVenueIdsSince(anyCollection(), any(LocalDateTime.class),
                eq(VenuePresenceService.NEARBY_RADIUS_M), eq(VenuePresenceService.HIT_MAX_ACCURACY_M));
    }

    // ── 展示口径一源：admin 读到的是同一次计算（2026-10-13） ─────────────────────

    @Test
    void footprintCarriesSevenDayUsersAndKeepsOldFootprintsVisibleForAdmin() {
        stubPairs();
        // 120：用户 1 两天前、用户 2 四十天前；121：只有一条四十天前的老足迹
        stubFacts(row(YIHU, 1L, now.minusDays(2)), row(YIHU, 2L, now.minusDays(40)),
                row(LISA, 3L, now.minusDays(40)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(2)));

        Map<Long, NearbyFootprint> f = service.nearbyFootprints(List.of(YIHU, LISA), List.of(-1L));

        assertEquals(1L, f.get(YIHU).visitUsers7d());
        assertEquals(1L, f.get(YIHU).visitUsers30d());
        assertEquals(1L, f.get(YIHU).visitEvents30d());
        assertEquals(now.minusDays(2), f.get(YIHU).lastVisitAt());
        assertEquals(0L, f.get(LISA).visitUsers30d(), "窗外的老足迹不计入 30 天人数");
        assertEquals(now.minusDays(40), f.get(LISA).lastVisitAt(),
                "…但 admin 的「最近」仍看得到（排序 / 最近到访列需要，C 侧不消费本字段）");
        assertEquals(Set.of(YIHU, LISA), f.keySet(), "admin 面：入参里每家店都有返回项");
    }

    @Test
    void cardTextSetIsExactlyTheNonEmptyProjectionOfTheAdminFootprints() {
        // 一源的结构保证：C 侧集合 = admin 完整事实里 30 天人数 > 0 的那部分，数字逐项相同
        stubPairs();
        stubFacts(row(YIHU, 1L, now.minusDays(2)), row(YIHU, 2L, now.minusDays(3)),
                row(LISA, 3L, now.minusDays(40)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(2)), day(YIHU, 2L, now.toLocalDate().minusDays(3)));

        Map<Long, NearbyFootprint> footprints = service.nearbyFootprints(List.of(YIHU, LISA), List.of(-1L));
        Map<Long, NearbyVisitSummary> display = service.nearbyVisitSummaries(List.of(YIHU, LISA), List.of(-1L));

        assertEquals(Set.of(YIHU), display.keySet(), "无 30 天足迹的店 C 侧缺席（不渲染）");
        assertEquals(footprints.get(YIHU).toDisplaySummary(), display.get(YIHU),
                "同一家店，C 侧与 admin 的人数 / 次数逐项相同");
    }

    @Test
    void excludedAccountsAreReportedSeparatelyNotSilentlyDropped() {
        stubPairs();
        stubFacts(row(YIHU, 1L, now.minusDays(1)), row(YIHU, 2L, now.minusDays(1)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(1)), day(YIHU, 2L, now.toLocalDate().minusDays(1)));

        NearbyFootprint f = service.nearbyFootprints(List.of(YIHU), List.of(1L)).get(YIHU);

        assertEquals(1L, f.visitUsers30d());
        assertEquals(1L, f.excludedUsers30d(), "被排除的人数明说：运营数一遍名单才不会怀疑漏了人");
    }

    @Test
    void nearbyVenueCountExplainsWhyNeighboursShowTheSameNumbers() {
        stubPairs(pair(YIHU, "OPEN", LISA, "丽莎歌舞厅", "OPEN"),
                pair(LISA, "OPEN", YIHU, "一壶淡泊音乐酒吧", "OPEN"));
        stubFacts(row(YIHU, 1L, now.minusDays(1)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(1)));

        Map<Long, NearbyFootprint> f = service.nearbyFootprints(List.of(YIHU, LISA), List.of(-1L));

        assertEquals(1, f.get(YIHU).nearbyVenueCount());
        assertEquals(1, f.get(LISA).nearbyVenueCount());
        assertEquals(f.get(YIHU).visitUsers30d(), f.get(LISA).visitUsers30d(), "同一邻域 = 同一组足迹");
    }

    @Test
    void globalDiscoveryAgreesWithPagePathAndSurfacesNeighbourOfEvidenceStore() {
        // 全量证据只在 120 上：121 在其邻域内，经并集拿到同一组足迹，必须进入候选（排序 / 只看有足迹要用）
        when(pingRepository.findVisitorLastSeen(eq(VenuePresenceService.NEARBY_RADIUS_M), anyInt()))
                .thenReturn(rows(row(YIHU, 2L, now.minusDays(1)), row(YIHU, 210L, now.minusDays(2))));
        stubPairs(pair(YIHU, "SUSPENDED", LISA, "丽莎歌舞厅", "OPEN"),
                pair(LISA, "OPEN", YIHU, "一壶淡泊音乐酒吧", "SUSPENDED"));
        stubFacts(row(YIHU, 2L, now.minusDays(1)), row(YIHU, 210L, now.minusDays(2)));
        stubDays(day(YIHU, 2L, now.toLocalDate().minusDays(1)), day(YIHU, 210L, now.toLocalDate().minusDays(2)));

        Map<Long, NearbyFootprint> visited = service.visitedNearbyFootprints(List.of(-1L));
        Map<Long, NearbyFootprint> page = service.nearbyFootprints(List.of(YIHU, LISA), List.of(-1L));

        assertEquals(Set.of(YIHU, LISA), visited.keySet(), "邻居经邻域并集获得同一组足迹，不能漏");
        assertEquals(page.get(LISA), visited.get(LISA), "全量路径与单页路径对同一家店给出同一组数字");
        assertEquals(2L, visited.get(LISA).visitUsers30d());
    }

    @Test
    void globalDiscoverySkipsStoresWhoseOnlyVisitorsAreExcluded() {
        when(pingRepository.findVisitorLastSeen(eq(VenuePresenceService.NEARBY_RADIUS_M), anyInt()))
                .thenReturn(rows(row(YIHU, 1L, now.minusDays(1))));

        Map<Long, NearbyFootprint> visited = service.visitedNearbyFootprints(List.of(1L));

        assertTrue(visited.isEmpty(), "证据只来自被排除账号 ⇒ 不算「有足迹」，与卡片「无足迹不渲染」一致");
    }

    // ── 到访感谢：可被感谢集合与卡片数字同源（2026-10-13，展示口径的第三消费面） ──────

    @Test
    void contributorsUseSameEvidenceWindowAndExclusionSetAsCardNumbers() {
        stubPairs();
        // u1 在窗口内、u2 在 40 天前（窗外）
        stubFacts(row(YIHU, 1L, now.minusDays(1)), row(YIHU, 2L, now.minusDays(40)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(1)));

        Map<Long, LocalDateTime> contributors = service.nearbyContributors(YIHU, List.of(-1L));
        Map<Long, NearbyFootprint> f = service.nearbyFootprints(List.of(YIHU), List.of(-1L));

        // 同源不变量：可被感谢集合 = 卡片人数背后的「那些人」（同一份证据、同一窗口）
        assertEquals(Set.of(1L), contributors.keySet(), "窗口外（40 天前）的命中不在集合内");
        assertEquals(1L, f.get(YIHU).visitUsers30d(),
                "卡片 N 人 = 贡献者集合大小（去掉自己后的差值由感谢 Service 承担）");
    }

    @Test
    void contributorsShareTheExclusionSetWithDisplay() {
        stubPairs();
        stubFacts(row(YIHU, 1L, now.minusDays(1)), row(YIHU, 2L, now.minusDays(1)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(1)), day(YIHU, 2L, now.toLocalDate().minusDays(1)));

        Map<Long, LocalDateTime> contributors = service.nearbyContributors(YIHU, List.of(1L));

        assertEquals(Set.of(2L), contributors.keySet(),
                "排除账号同样从「可感谢集合」剔除——一个集合、两种消费面（同 countSince 的双消费先例）");
    }

    // ── 详情页浏览者视角：「我在不在卡片的 N 位里」是结构保证（2026-10-16） ───────────────

    @Test
    void viewerMinesSumToTheCardEventsAndShareOneSnapshotWithIt() {
        // 五洲双店：u1 在两家同一天都有命中（只算 1 次）、再加一天 ⇒ 2 次；u2 只来 1 天；u3 来 1 天
        stubPairs(pair(YIHU, "OPEN", LISA, "丽莎歌舞厅", "OPEN"),
                pair(LISA, "OPEN", YIHU, "一壶淡泊音乐酒吧", "OPEN"));
        LocalDate today = now.toLocalDate();
        stubFacts(row(YIHU, 1L, now.minusDays(1)), row(LISA, 1L, now.minusDays(1)),
                row(LISA, 2L, now.minusDays(3)), row(YIHU, 3L, now.minusDays(2)));
        stubDays(day(YIHU, 1L, today.minusDays(1)), day(LISA, 1L, today.minusDays(1)),
                day(LISA, 1L, today.minusDays(5)), day(LISA, 2L, today.minusDays(3)),
                day(YIHU, 3L, today.minusDays(2)));

        ViewerFootprint v1 = service.nearbyFootprintForViewer(LISA, 1L, List.of(-1L));
        ViewerFootprint v2 = service.nearbyFootprintForViewer(LISA, 2L, List.of(-1L));
        ViewerFootprint v3 = service.nearbyFootprintForViewer(LISA, 3L, List.of(-1L));

        assertEquals(2L, v1.mine().visitEvents30d(), "同一天在两家都命中只算 1 次，加另一天 = 2（(人, 日) 并集）");
        assertEquals(1L, v2.mine().visitEvents30d());
        assertEquals(1L, v3.mine().visitEvents30d());
        // 结构不变量：全体用户「自己的次数」之和 ≡ 卡片「N 次」——它们是同一个数字的切片
        assertEquals(v1.footprint().visitEvents30d(),
                v1.mine().visitEvents30d() + v2.mine().visitEvents30d() + v3.mine().visitEvents30d(),
                "卡片次数 = 每个被计入的人自己的次数之和（共用 unionVisitDays 单点）");
        assertEquals(3L, v1.footprint().visitUsers30d(), "卡片人数不因浏览者不同而变");
        assertEquals(v1.footprint(), v2.footprint(), "足迹行事实对所有浏览者相同（只有 mine 随人变）");
    }

    @Test
    void viewerOutsideTheCountedContributorsGetsNoMine() {
        stubPairs();
        stubFacts(row(YIHU, 1L, now.minusDays(1)), row(YIHU, 2L, now.minusDays(40)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(1)));

        // 陌生人（从未到过）/ 最近命中在窗外（40 天前）/ 游客（viewerId = null）
        assertNull(service.nearbyFootprintForViewer(YIHU, 99L, List.of(-1L)).mine(), "不在贡献者之列：不对他说这句话");
        assertNull(service.nearbyFootprintForViewer(YIHU, 2L, List.of(-1L)).mine(),
                "最近命中在窗外 ⇒ 卡片不计他 ⇒ 不能对他说「你留下过」（与卡片同一条 inWindow 判据）");
        ViewerFootprint guest = service.nearbyFootprintForViewer(YIHU, null, List.of(-1L));
        assertNull(guest.mine(), "游客没有「自己」");
        assertEquals(1L, guest.footprint().visitUsers30d(), "游客照样拿到公开的足迹行事实");
    }

    @Test
    void excludedViewerIsNotToldTheyLeftFootprintsBecauseTheCardDoesNotCountThem() {
        stubPairs();
        stubFacts(row(YIHU, 1L, now.minusDays(1)), row(YIHU, 2L, now.minusDays(1)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(1)), day(YIHU, 2L, now.toLocalDate().minusDays(1)));

        ViewerFootprint excluded = service.nearbyFootprintForViewer(YIHU, 1L, List.of(1L));
        ViewerFootprint other = service.nearbyFootprintForViewer(YIHU, 2L, List.of(1L));

        assertNull(excluded.mine(), "被排除账号：卡片不计他，就不能对他说「你留下过 N 次」");
        assertEquals(1L, excluded.footprint().visitUsers30d(), "排除后卡片只剩 u2");
        assertEquals(1L, other.mine().visitEvents30d());
    }

    @Test
    void viewerInWindowButWithoutAnyVisitDayRendersNoMineInsteadOfZeroTimes() {
        // 防御：两个查询谓词一致，现实中不应出现；真出现时「0 次」比不渲染更糟（这句话的全部内容就是那个次数）
        stubPairs();
        stubFacts(row(YIHU, 1L, now.minusDays(1)));
        stubDays();

        assertNull(service.nearbyFootprintForViewer(YIHU, 1L, List.of(-1L)).mine());
    }

    @Test
    void viewerPathUsesTheSameThreeQueriesAsTheSingleVenuePaths() {
        // 与 nearbyContributors / nearbyDetailFor 同形态：一次同址 + 一次证据 + 一次到访日（无 N+1，无额外取数面）
        stubPairs();
        stubFacts(row(YIHU, 1L, now.minusDays(1)));
        stubDays(day(YIHU, 1L, now.toLocalDate().minusDays(1)));

        service.nearbyFootprintForViewer(YIHU, 1L, List.of(-1L));

        verify(venueRepository).findCoLocatedPairs(anyCollection(), eq(VenuePresenceService.NEARBY_TRACE_RADIUS_M));
        verify(pingRepository).findVisitorLastSeenByVenueIds(anyCollection(),
                eq(VenuePresenceService.NEARBY_RADIUS_M), anyInt());
        verify(pingRepository).findVisitorDaysByVenueIdsSince(anyCollection(), any(LocalDateTime.class),
                eq(VenuePresenceService.NEARBY_RADIUS_M), anyInt());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void stubPairs(VenueRepository.CoLocatedVenueRow... pairs) {
        when(venueRepository.findCoLocatedPairs(anyCollection(), eq(VenuePresenceService.NEARBY_TRACE_RADIUS_M)))
                .thenReturn(List.of(pairs));
    }

    /** 取数谓词（2026-10-09）：半径 = 附近带 {@code NEARBY_RADIUS_M}=300m（⛔ 不再是 HIT_RADIUS_M） */
    private void stubFacts(Object[]... rows) {
        when(pingRepository.findVisitorLastSeenByVenueIds(anyCollection(),
                eq(VenuePresenceService.NEARBY_RADIUS_M), anyInt()))
                .thenReturn(rows(rows));
    }

    private void stubDays(Object[]... rows) {
        when(pingRepository.findVisitorDaysByVenueIdsSince(anyCollection(), any(LocalDateTime.class),
                eq(VenuePresenceService.NEARBY_RADIUS_M), anyInt()))
                .thenReturn(rows(rows));
    }

    /** varargs → List 的显式目标类型（内联 List.of(rows) 会被推断成 List<Object> 编译失败，同既有测试 helper） */
    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    private static Object[] row(long venueId, long userId, LocalDateTime lastSeen) {
        return new Object[]{venueId, userId, lastSeen};
    }

    /** 到访日行走生产同款形态：DATE() 在 JDBC 下返回 java.sql.Date（toLocalDate 白名单消费） */
    private static Object[] day(long venueId, long userId, LocalDate visitDay) {
        return new Object[]{venueId, userId, java.sql.Date.valueOf(visitDay)};
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
