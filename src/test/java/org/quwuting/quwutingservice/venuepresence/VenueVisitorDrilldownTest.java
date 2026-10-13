package org.quwuting.quwutingservice.venuepresence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.support.HqlSyntaxAssertions;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserVisitRecord;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserVisitsResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminVenueVisitorItem;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminVenueVisitorPage;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceStats;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.quwuting.quwutingservice.venuepresence.service.NearbyFootprint;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 足迹名单下钻口径（2026-10-06，52 号 §6.2；<b>2026-10-13 并入展示口径一源</b>）：名单是卡片数字的展开
 * （同窗口下人数 / 次数逐数相等）、内部账号打标签、被排除者明说、窗口按有效范围钳制；
 * 另含用户个人到访足迹的逐桶合并「一次次到店」（命中带 150m，与展示口径是两个问题，见 52 号）。
 * <p>
 * 这些断言守的都是<b>「运营看到两个数字时能不能自洽」</b>：
 * 名单说 12 人而卡片写 5 人（不同源）、名单把平台自己人混进舞友（未打标签）、
 * 7 天卡片点进去却是 30 天的人（窗口被钳）、一次跳舞被记成十几次到店（未合并）——
 * 每一条都会让 admin 页面上出现一个无法解释的数字，进而让整套数据失去可信度。
 */
@ExtendWith(MockitoExtension.class)
class VenueVisitorDrilldownTest {

    private static final long VENUE = 120L;
    private static final long PEER = 121L;

    @Mock
    private VenuePresencePingRepository pingRepository;
    @Mock
    private VenuePresenceConsentRepository consentRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private OpsConfigService opsConfigService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private VenuePresenceService service;

    private final LocalDateTime now = LocalDateTime.now();

    // ── 名单：卡片数字的展开（2026-10-13 并入展示口径一源） ─────────────────────

    @Test
    void visitorListIsTheExpansionOfTheCardNumbersInEveryWindow() {
        // 核心不变量：同窗口下 名单总人数 = 卡片人数、名单次数合计 = 卡片「N 次」。
        // 这是「点开数字看到的人」与「数字」永远对得上的结构保证——此前两者各算各的，
        // 详情页因此被迫规定「四张统计卡不可点」
        stubPairs();
        stubHits(hit(VENUE, 2L, now.minusHours(3)), hit(VENUE, 210L, now.minusDays(2)),
                hit(VENUE, 908L, now.minusDays(20)), hit(VENUE, 77L, now.minusDays(40)));
        stubVisitDays(day(VENUE, 2L, now.minusDays(3).toLocalDate()),
                day(VENUE, 2L, now.toLocalDate()),
                day(VENUE, 210L, now.minusDays(2).toLocalDate()),
                day(VENUE, 908L, now.minusDays(20).toLocalDate()));
        stubUsers(user(2L, "小李", true, UserRole.USER, false),
                user(210L, null, false, UserRole.USER, false),
                user(908L, null, false, UserRole.USER, false));

        NearbyFootprint card = service.nearbyFootprints(List.of(VENUE), List.of(-1L)).get(VENUE);
        AdminVenueVisitorPage thirty = service.nearbyVisitorsFor(VENUE, 30, 0, 20, List.of(-1L));
        AdminVenueVisitorPage seven = service.nearbyVisitorsFor(VENUE, 7, 0, 20, List.of(-1L));

        assertEquals(3L, card.visitUsers30d(), "40 天前那人不在 30 天窗");
        assertEquals(card.visitUsers30d(), thirty.totalElements(), "30 天名单人数 = 卡片「30 天 N 人」");
        assertEquals(card.visitEvents30d(), thirty.totalVisitTimes(), "30 天名单次数合计 = 卡片「N 次」");
        assertEquals(2L, card.visitUsers7d());
        assertEquals(card.visitUsers7d(), seven.totalElements(), "7 天名单人数 = 卡片「7 天 N 人」——7 天也必须能点开对上");
        assertEquals(30, thirty.windowDays());
        assertEquals(7, seven.windowDays());
        assertEquals(VenuePresenceService.NEARBY_RADIUS_M, thirty.nearbyRadiusM(), "口径必须随响应回显");
        assertEquals(VenuePresenceService.NEARBY_TRACE_RADIUS_M, thirty.vicinityRadiusM());
    }

    @Test
    void windowIsClampedToValidRangeNotToTheOldThirtyDayFloor() {
        stubPairs();
        stubHits(hit(VENUE, 2L, now.minusHours(3)), hit(VENUE, 210L, now.minusDays(20)));
        stubVisitDays();
        stubUsers(user(2L, "小李", true, UserRole.USER, false));

        // 7 天就是 7 天：旧约束「不能小于 30」的前提（行里只有 30 天一个数字）已不成立
        AdminVenueVisitorPage week = service.nearbyVisitorsFor(VENUE, 7, 0, 20, List.of(-1L));
        AdminVenueVisitorPage zero = service.nearbyVisitorsFor(VENUE, 0, 0, 20, List.of(-1L));
        AdminVenueVisitorPage huge = service.nearbyVisitorsFor(VENUE, 999_999, 0, 20, List.of(-1L));

        assertEquals(7, week.windowDays());
        assertEquals(1L, week.totalElements(), "20 天前那人不在 7 天窗里");
        assertEquals(1, zero.windowDays(), "非正数钳到 1 天，不抛异常");
        assertEquals(3650, huge.windowDays(), "上限钳制，防无界扫描");
    }

    @Test
    void visitorRowsIncludeNeighbourStoreFootprintsAndSortByLatestFirst() {
        // 名单的范围 = 本店 + 邻域（≤150m）内其他门店的附近带足迹——与小程序文案同一范围，
        // 不是「只有本店坐标上的 ping」
        stubPairs(pair(VENUE, "OPEN", PEER, "丽莎歌舞厅", "OPEN"));
        stubHits(hit(VENUE, 2L, now.minusHours(5)), hit(PEER, 2L, now.minusHours(1)),
                hit(PEER, 210L, now.minusHours(2)));
        stubVisitDays(day(VENUE, 2L, now.minusDays(3).toLocalDate()),
                day(PEER, 2L, now.minusDays(2).toLocalDate()),
                day(PEER, 2L, now.toLocalDate()),
                day(PEER, 210L, now.minusDays(1).toLocalDate()));
        stubUsers(user(2L, "小李", true, UserRole.USER, false),
                user(210L, null, false, UserRole.USER, false));

        AdminVenueVisitorPage page = service.nearbyVisitorsFor(VENUE, 30, 0, 20, List.of(-1L));
        List<AdminVenueVisitorItem> rows = page.content();

        assertEquals(2L, page.totalElements(), "用户 2 在两家都有足迹只计 1 人（并集，不是相加）");
        assertEquals(2L, rows.get(0).userId(), "最近足迹的排前面（用户 2 在邻居店 1 小时前）");
        assertEquals(3L, rows.get(0).visitTimes(), "次数 = 邻域内 (用户, 自然日) 并集：3 个不同日期，不是采样次数");
        assertEquals(1L, rows.get(1).visitTimes());
        assertEquals(List.of(new VenuePresenceStats.VicinityVenue(PEER, "丽莎歌舞厅", "营业中", true)),
                page.vicinityVenues(), "邻域内的其他门店随响应回显：名单可能含「其实在隔壁店」的人");
    }

    @Test
    void excludedAccountsAreOutOfTheListAndCountedSeparatelyNotSilentlyDropped() {
        // 排除集合 = 小程序文案与热度排序同一集合：名单不含被排除者（否则比卡片数字多），
        // 但人数明说（excludedUsers），运营数一遍才不会怀疑「是不是漏了」
        stubPairs();
        stubHits(hit(VENUE, 1L, now.minusHours(1)), hit(VENUE, 2L, now.minusHours(2)));
        stubVisitDays(day(VENUE, 1L, now.toLocalDate()), day(VENUE, 2L, now.toLocalDate()));
        stubUsers(user(2L, "小李", true, UserRole.USER, false));

        AdminVenueVisitorPage page = service.nearbyVisitorsFor(VENUE, 30, 0, 20, List.of(1L));
        NearbyFootprint card = service.nearbyFootprints(List.of(VENUE), List.of(1L)).get(VENUE);

        assertEquals(1L, page.totalElements());
        assertEquals(2L, page.content().get(0).userId());
        assertEquals(1L, page.excludedUsers());
        assertEquals(card.visitUsers30d(), page.totalElements(), "排除后名单与卡片仍逐人相等");
        assertEquals(card.excludedUsers30d(), page.excludedUsers());
        assertEquals(1L, page.totalVisitTimes(), "次数合计同样剔除被排除者");
    }

    @Test
    void anonymousUserShowsUserCodeInsteadOfDefaultNickname() {
        // 现网 98% 用户昵称仍是注册默认值（873 个存活用户里 857 个没改过）：
        // 用昵称作主标题会得到一屏「微信用户」，无从辨认谁是谁
        stubPairs();
        stubHits(hit(VENUE, 210L, now));
        stubVisitDays(day(VENUE, 210L, now.toLocalDate()));
        stubUsers(user(210L, "微信用户", false, UserRole.USER, false));

        AdminVenueVisitorItem row = service.nearbyVisitorsFor(VENUE, 30, 0, 20, List.of(-1L)).content().get(0);

        assertFalse(row.nicknameCustom(), "默认昵称不算自定义");
        assertEquals("U#00210", row.userCode(), "主标题回退到代号（前端据 nicknameCustom 选，这里只保证判据下发）");
    }

    @Test
    void internalAccountsAreTaggedNotExcluded() {
        // 现网 ADMIN 一人占到访记录约一半。计入与否只由「排除账号集合」决定；ADMIN / 审核号只是识别标签，
        // 让运营数得出「这里面有几个是自己人」
        stubPairs();
        stubHits(hit(VENUE, 1L, now.minusHours(1)), hit(VENUE, 2L, now.minusHours(2)),
                hit(VENUE, 3L, now.minusHours(3)));
        stubVisitDays(day(VENUE, 1L, now.toLocalDate()), day(VENUE, 2L, now.toLocalDate()),
                day(VENUE, 3L, now.toLocalDate()));
        stubUsers(user(1L, "管理员", true, UserRole.ADMIN, false),
                user(2L, "小李", true, UserRole.USER, false),
                user(3L, "审核号", true, UserRole.USER, true));

        List<AdminVenueVisitorItem> rows = service.nearbyVisitorsFor(VENUE, 30, 0, 20, List.of(-1L)).content();

        assertEquals(3L, rows.size(), "内部账号也在名单里（未被排除集合命中即计入）");
        assertTrue(rows.get(0).internalAccount(), "ADMIN 打标签");
        assertFalse(rows.get(1).internalAccount(), "普通舞友不打标签");
        assertTrue(rows.get(2).internalAccount(), "微信审核号也算内部账号");
    }

    @Test
    void softDeletedVisitorStillGetsARow() {
        // 到访痕迹是核查线索；静默丢行会让 totalElements 与行数对不上，又是说不清的不一致
        stubPairs();
        stubHits(hit(VENUE, 999L, now));
        stubVisitDays(day(VENUE, 999L, now.toLocalDate()));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        List<AdminVenueVisitorItem> rows = service.nearbyVisitorsFor(VENUE, 30, 0, 20, List.of(-1L)).content();

        assertEquals(1, rows.size());
        assertEquals(999L, rows.get(0).userId());
        assertEquals("U#00999", rows.get(0).userCode(), "资料缺失也给代号（纯派生，不依赖用户行存在）");
        assertTrue(rows.get(0).internalAccount(), "查不到资料按内部账号处理，宁可多打一个标签");
    }

    @Test
    void visitorPageIsBoundedAndClampsNegativeInputs() {
        stubPairs();
        stubHits(hit(VENUE, 2L, now));
        stubVisitDays(day(VENUE, 2L, now.toLocalDate()));
        stubUsers(user(2L, "小李", true, UserRole.USER, false));

        AdminVenueVisitorPage page = service.nearbyVisitorsFor(VENUE, 30, -5, 9999, List.of(-1L));

        assertEquals(0, page.number(), "负页码归零，不抛异常");
        assertTrue(page.size() <= 100, "每页条数有上限，防深翻页拖库");
        assertTrue(page.last());
    }

    // ── 用户足迹：逐桶合并 ──────────────────────────────────────────────────

    @Test
    void consecutiveBucketsMergeIntoOneVisit() {
        stubUserExists(2L);
        // 18:00~21:00 连续 12 个 15 分钟桶 = 一次跳舞，绝不能被记成 12 次到店
        stubHitBuckets(bucket(VENUE, 100L, now.minusHours(3), 88),
                bucket(VENUE, 101L, now.minusHours(2).minusMinutes(45), 91),
                bucket(VENUE, 102L, now.minusHours(2).minusMinutes(30), 86),
                bucket(VENUE, 103L, now.minusHours(2).minusMinutes(15), 84));
        stubVenues(venue(VENUE, "一壶淡泊音乐酒吧", "南通", VenueStatus.OPEN));

        AdminUserVisitsResponse resp = service.visitsFor(2L, 90);

        assertEquals(1L, resp.visitCount(), "一次到店 = 一条，不是每个采样桶一条");
        assertEquals(4, resp.venues().get(0).records().get(0).sampleCount(), "采样次数保留（4 次被记录到）");
        assertEquals(84, resp.venues().get(0).records().get(0).minDistanceM(), "最近距离取桶内最小值");
        assertEquals(45L, resp.venues().get(0).records().get(0).stayMinutes());
        assertFalse(resp.truncated());
    }

    @Test
    void gapBeyondThresholdStartsANewVisit() {
        stubUserExists(2L);
        // 相邻桶（间隔 1）合并；隔 4 桶（1 小时）必须断成两次——离店 15~30 分钟又回来是舞厅常态
        stubHitBuckets(bucket(VENUE, 100L, now.minusHours(5), 90),
                bucket(VENUE, 101L, now.minusHours(4).minusMinutes(45), 88),
                bucket(VENUE, 110L, now.minusHours(2), 95));
        stubVenues(venue(VENUE, "一壶淡泊音乐酒吧", "南通", VenueStatus.OPEN));

        AdminUserVisitsResponse resp = service.visitsFor(2L, 90);

        assertEquals(2L, resp.visitCount(), "间隔超阈值 = 又来了一次");
    }

    @Test
    void singleBucketVisitHasZeroStayRatherThanNegative() {
        stubUserExists(2L);
        stubHitBuckets(bucket(VENUE, 100L, now, 70));
        stubVenues(venue(VENUE, "一壶淡泊音乐酒吧", "南通", VenueStatus.OPEN));

        AdminUserVisitRecord record = service.visitsFor(2L, 90).venues().get(0).records().get(0);

        assertEquals(1, record.sampleCount());
        assertEquals(0L, record.stayMinutes(), "单桶记录 = 只被记录过一次，停留时长不可考（不是负数）");
        assertEquals(70, record.minDistanceM());
    }

    @Test
    void visitsAreGroupedByVenueAndNewestVisitLeadsEachGroup() {
        stubUserExists(2L);
        // 输入按 (venueId, writeBucket) 升序：120 的两个桶在前，121 在后
        stubHitBuckets(bucket(VENUE, 100L, now.minusDays(3), 90),
                bucket(VENUE, 104L, now.minusDays(1), 88),
                bucket(PEER, 200L, now, 95));
        stubVenues(venue(VENUE, "一壶淡泊音乐酒吧", "南通", VenueStatus.OPEN),
                venue(PEER, "丽莎歌舞厅", "南通", VenueStatus.OPEN));

        AdminUserVisitsResponse resp = service.visitsFor(2L, 90);

        assertEquals(2, resp.venueCount());
        assertEquals(PEER, resp.venues().get(0).venueId(), "组间按最近一次到访倒序");
        assertEquals(2, resp.venues().get(1).visitCount());
        assertEquals(now, resp.lastVisitAt());
    }

    @Test
    void unknownUserIsRejectedRatherThanReturningAnEmptyFootprint() {
        when(userRepository.findByIdAndDeletedFalse(404L)).thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class, () -> service.visitsFor(404L, 90));

        assertEquals(1004, e.getCode());
        verify(pingRepository, never()).findHitsByUserIdSince(any(), any(), anyInt(), anyInt());
    }

    @Test
    void emptyWindowReturnsEmptyFootprintWithoutTouchingVenues() {
        // 空集合进原生 IN () 是语法错误——必须短路而不是把空 list 交给仓储
        stubUserExists(2L);
        stubHitBuckets();

        AdminUserVisitsResponse resp = service.visitsFor(2L, 30);

        assertEquals(0, resp.venueCount());
        assertEquals(0L, resp.visitCount());
        assertEquals(null, resp.lastVisitAt(), "窗口内无到访 ≠ 从无到访，这里只能是窗口内无");
        assertFalse(resp.truncated());
        verify(venueRepository, never()).findByIdInAndDeletedFalse(anyListOfIds());
    }

    @Test
    void drilldownQueriesParseAsHql() {
        // JPQL 侧绿灯 ≠ MySQL 侧绿灯（HQL 词法会容忍「数字紧跟标识符」）：
        // 新查询过一遍真解析，避免上线才发现语法错
        HqlSyntaxAssertions.assertParses(
                "SELECT DISTINCT p.venueId, p.userId, DATE(p.createdAt) FROM VenuePresencePing p "
                        + "WHERE p.venueId IN :venueIds AND " + VenuePresencePingRepository.HIT_PREDICATE
                        + " AND p.createdAt >= :since");
        HqlSyntaxAssertions.assertParses(
                "SELECT p.venueId, p.writeBucket, p.createdAt, p.updatedAt, p.distanceM, p.accuracyM "
                        + "FROM VenuePresencePing p WHERE p.userId = :userId AND "
                        + VenuePresencePingRepository.HIT_PREDICATE
                        + " AND p.createdAt >= :since ORDER BY p.venueId ASC, p.writeBucket ASC");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void stubPairs(VenueRepository.CoLocatedVenueRow... pairs) {
        // 展示口径的邻域半径（NEARBY_TRACE_RADIUS_M=150m）；⛔ 不是排序用的同址半径 CO_LOCATED_RADIUS_M
        when(venueRepository.findCoLocatedPairs(anyCollection(), eq(VenuePresenceService.NEARBY_TRACE_RADIUS_M)))
                .thenReturn(List.of(pairs));
    }

    /** 取数谓词 = 附近带 NEARBY_RADIUS_M=300m（展示口径；⛔ 不是命中带 HIT_RADIUS_M） */
    private void stubHits(Object[]... rows) {
        when(pingRepository.findVisitorLastSeenByVenueIds(
                anyCollection(), eq(VenuePresenceService.NEARBY_RADIUS_M), anyInt()))
                .thenReturn(List.of(rows));
    }

    private void stubVisitDays(Object[]... rows) {
        when(pingRepository.findVisitorDaysByVenueIdsSince(
                anyCollection(), any(LocalDateTime.class), eq(VenuePresenceService.NEARBY_RADIUS_M), anyInt()))
                .thenReturn(List.of(rows));
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

    private void stubUsers(User... users) {
        when(userRepository.findAllById(anyListOfIds())).thenReturn(List.of(users));
    }

    private void stubUserExists(long id) {
        when(userRepository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(user(id, "小李", true, UserRole.USER, false)));
    }

    private void stubHitBuckets(Object[]... rows) {
        when(pingRepository.findHitsByUserIdSince(
                any(Long.class), any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(List.of(rows));
    }

    private void stubVenues(Venue... venues) {
        when(venueRepository.findByIdInAndDeletedFalse(anyListOfIds())).thenReturn(List.of(venues));
    }

    /**
     * 两个仓储方法的入参静态类型都是 {@code List<Long>}（派生查询），不是 {@code Collection}——
     * {@code anyCollection()} 在泛型推断上匹配不上，编译期即报错（比运行期 stub 失配好）。
     */
    private static List<Long> anyListOfIds() {
        return org.mockito.ArgumentMatchers.anyList();
    }

    private static Object[] hit(long venueId, long userId, LocalDateTime lastSeen) {
        return new Object[]{venueId, userId, lastSeen};
    }

    private static Object[] day(long venueId, long userId, java.time.LocalDate visitDay) {
        return new Object[]{venueId, userId, visitDay};
    }

    /** 命中桶行：(venueId, writeBucket, createdAt, updatedAt, distanceM)，updatedAt 复用 createdAt */
    private static Object[] bucket(long venueId, long writeBucket, LocalDateTime at, int distanceM) {
        return new Object[]{venueId, writeBucket, at, at, distanceM, 30};
    }

    private static User user(long id, String nickname, boolean custom, UserRole role, boolean review) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        u.setRole(role);
        u.setWechatReview(review);
        return u;
    }

    private static Venue venue(long id, String name, String city, VenueStatus status) {
        Venue v = new Venue();
        v.setId(id);
        v.setName(name);
        v.setCity(city);
        v.setStatus(status);
        return v;
    }
}
