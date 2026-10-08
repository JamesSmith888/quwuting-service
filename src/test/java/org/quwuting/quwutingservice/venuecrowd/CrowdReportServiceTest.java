package org.quwuting.quwutingservice.venuecrowd;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuecrowd.dto.request.SubmitCrowdReportRequest;
import org.quwuting.quwutingservice.venuecrowd.dto.response.AdminCrowdReportSummary;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdSummary;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReport;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdFemaleLevel;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdMaleLevel;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdReportLikeService;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdReportService;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdTrustService;
import org.quwuting.quwutingservice.venuecrowd.stat.BusinessDay;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdPolicy;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 热度上报服务测试：取数 → 票 → 统计 → 落库 的<b>编排</b>（统计本身的规则在 CrowdConsensusTest 表驱动守住）。
 * 重点守：营业日/自然日分坐标写入、认领人不进统计、确认积分与详情页 CONFIRMED 同源、管理端冲突同口径。
 */
@ExtendWith(MockitoExtension.class)
class CrowdReportServiceTest {

    private static final Long VENUE_ID = 100L;

    @Mock
    private VenueCrowdReportRepository crowdReportRepository;

    @Mock
    private VenueRepository venueRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CrowdTrustService crowdTrustService;

    @Mock
    private PointsService pointsService;

    @Mock
    private MessageService messageService;

    @Mock
    private FavoriteRepository favoriteRepository;

    @Mock
    private CrowdReportLikeService crowdReportLikeService;

    private CrowdReportService crowdReportService;

    @BeforeEach
    void setUp() {
        crowdReportService = new CrowdReportService(
                crowdReportRepository,
                venueRepository,
                userRepository,
                crowdTrustService,
                pointsService,
                messageService,
                favoriteRepository,
                crowdReportLikeService
        );
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private static Venue venue(Long claimedBy) {
        Venue v = new Venue();
        v.setId(VENUE_ID);
        v.setName("抖舞");
        v.setStatus(VenueStatus.OPEN);
        v.setClaimedBy(claimedBy);
        return v;
    }

    private static VenueCrowdReport report(long id, long userId, int femaleLevel, Integer maleLevel,
                                           LocalDateTime createdAt) {
        VenueCrowdReport r = new VenueCrowdReport();
        r.setId(id);
        r.setVenueId(VENUE_ID);
        r.setUserId(userId);
        r.setFemaleLevel(femaleLevel);
        r.setMaleLevel(maleLevel);
        r.setReportDate(createdAt.toLocalDate());
        r.setBusinessDate(BusinessDay.of(createdAt));
        r.setCreatedAt(createdAt);
        r.setModifyCount(0);
        return r;
    }

    private static User user(long id, String nickname) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        return u;
    }

    /**
     * 读路径共用桩：权重默认 1.0；用户资料；点赞聚合为空。
     *
     * <p><b>两条取数都要桩</b>（2026-10-07）：{@code summary()} 现在刻意分成两路——
     * 统计走有效期窗口（{@code findByVenueIdAndCreatedAtAfterAndDeletedFalse}，2026-10-08 起 1 天），
     * 展示走最近 N 条（{@code findByVenueIdAndDeletedFalseOrderByCreatedAtDesc}，
     * 含过期）。只桩一条会让明细取数拿到 Mockito 默认的 null → NPE，
     * 且这类失败会被误读成"环境问题"，故在此一次性说明白。
     *
     * @param detailRows 明细展示行（不传 = 与窗口行相同，即"窗口内数据足够"的常见场景）
     */
    private void stubReadPath(Long claimedBy, List<VenueCrowdReport> windowRows, long... userIds) {
        stubReadPath(claimedBy, windowRows, List.of(), userIds);
    }

    /** 读路径共用桩（显式区分统计窗口行与展示明细行 —— 窗口外仍有历史数据的场景）。 */
    private void stubReadPath(Long claimedBy, List<VenueCrowdReport> windowRows,
                              List<VenueCrowdReport> detailRows, long... userIds) {
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue(claimedBy)));
        when(crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(eq(VENUE_ID), any()))
                .thenReturn(windowRows);
        when(crowdReportRepository.findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(eq(VENUE_ID), any()))
                .thenReturn(new PageImpl<>(detailRows.isEmpty() ? windowRows : detailRows,
                        PageRequest.of(0, CrowdPolicy.DETAIL_ROWS_LIMIT), (long) detailRows.size()));
        Map<Long, Double> weights = new java.util.HashMap<>();
        List<User> users = new java.util.ArrayList<>();
        for (long id : userIds) {
            weights.put(id, 1.0);
            users.add(user(id, "舞友" + id));
        }
        when(crowdTrustService.weights(any())).thenReturn(weights);
        when(userRepository.findByIdInAndDeletedFalse(any())).thenReturn(users);
        when(crowdReportLikeService.likeCountsByReportIds(any())).thenReturn(Map.of());
        when(crowdReportLikeService.likedReportIds(any(), any())).thenReturn(Set.of());
    }

    // ── 档位枚举 ─────────────────────────────────────────────────────────────

    @Test
    void testLevelsAndDisplayNames() {
        assertEquals("0-20", CrowdFemaleLevel.RANGE_0_20.getDisplayName());
        assertEquals("约30", CrowdFemaleLevel.RANGE_30.getDisplayName());
        assertEquals("约50", CrowdFemaleLevel.RANGE_50.getDisplayName());
        assertEquals("约80", CrowdFemaleLevel.RANGE_80.getDisplayName());
        assertEquals("约100", CrowdFemaleLevel.RANGE_100.getDisplayName());
        assertEquals("约150", CrowdFemaleLevel.RANGE_150.getDisplayName());
        assertEquals("约200", CrowdFemaleLevel.RANGE_200.getDisplayName());
        assertEquals("约300+", CrowdFemaleLevel.RANGE_300_PLUS.getDisplayName());

        assertEquals(8, CrowdFemaleLevel.values().length);
        assertEquals(8, CrowdMaleLevel.values().length);
        assertEquals(CrowdFemaleLevel.RANGE_100, CrowdFemaleLevel.of(5));
        assertEquals(CrowdMaleLevel.RANGE_80, CrowdMaleLevel.of(4));
    }

    // ── 读：摘要 ─────────────────────────────────────────────────────────────

    @Test
    void summaryCarriesMainTextHeadlineAndLikeWindow() {
        LocalDateTime now = LocalDateTime.now();
        VenueCrowdReport r1 = report(1L, 10L, 5, 3, now.minusMinutes(10));
        stubReadPath(null, List.of(r1), 10L);

        CrowdSummary summary = crowdReportService.summary(VENUE_ID);

        assertTrue(summary.hasData());
        assertEquals(5, summary.female().level());
        assertEquals("约100", summary.female().levelName());
        assertEquals(1, summary.female().count(), "count = 独立投票人数");
        assertTrue(summary.mainText().startsWith("舞伴 约100 · 1 位舞友 · "));
        assertEquals("男客 约50 · 1 人", summary.maleText());
        // 结论值由本测试验证；「今晚 / 昨晚」措辞按营业日分流（时段敏感），
        // 措辞分支由 CrowdHeadlineTest 用可控时钟 100% 覆盖。
        assertNotNull(summary.headlineText());
        assertTrue(summary.headlineText().contains("约100 · 1人"), "实际：" + summary.headlineText());
        assertEquals(1, summary.rows().size());
        CrowdSummary.CrowdReportRow row = summary.rows().get(0);
        assertEquals("约100", row.femaleLevelName());
        assertEquals("约50", row.maleLevelName());
        assertFalse(row.expired(), "10 分钟前的上报仍在有效期窗口（1 天）内");
    }

    @Test
    void medianNotModeDrivesTheHeadlineLevel() {
        // 众数口径下这组是「各执一词」（三档各一人），中位口径下是相邻档一致——两种口径给出不同结论
        LocalDateTime now = LocalDateTime.now();
        stubReadPath(null, List.of(
                report(1L, 10L, 4, null, now.minusMinutes(30)),
                report(2L, 11L, 5, null, now.minusMinutes(20)),
                report(3L, 12L, 6, null, now.minusMinutes(10))), 10L, 11L, 12L);

        CrowdSummary summary = crowdReportService.summary(VENUE_ID);

        assertEquals(5, summary.female().level());
        assertEquals("CONFIRMED", summary.tier(), "4/5/6 中位 5，三人均在 ±1 内 ⇒ 确认（旧众数口径会判说法不一）");
        assertNotNull(summary.headlineText());
        assertTrue(summary.headlineText().contains("约100 · 3人"), "实际：" + summary.headlineText());
    }

    @Test
    void solidSampleShowsTheMiddleHalfRangeInTheMainText() {
        LocalDateTime now = LocalDateTime.now();
        stubReadPath(null, List.of(
                report(1L, 10L, 3, null, now.minusMinutes(50)),
                report(2L, 11L, 4, null, now.minusMinutes(40)),
                report(3L, 12L, 5, null, now.minusMinutes(30)),
                report(4L, 13L, 6, null, now.minusMinutes(20)),
                report(5L, 14L, 7, null, now.minusMinutes(10))), 10L, 11L, 12L, 13L, 14L);

        CrowdSummary summary = crowdReportService.summary(VENUE_ID);

        assertTrue(summary.mainText().startsWith("舞伴 约100（约80~约150） · 5 位舞友 · "),
                "样本充足时带中间一半区间，实际：" + summary.mainText());
    }

    @Test
    void claimantsReportIsShownButNotCounted() {
        LocalDateTime now = LocalDateTime.now();
        // 认领人 10 报约300+，舞友 11 报约50：统计里只有 11
        stubReadPath(10L, List.of(
                report(1L, 10L, 8, null, now.minusMinutes(5)),
                report(2L, 11L, 3, null, now.minusMinutes(30))), 10L, 11L);

        CrowdSummary summary = crowdReportService.summary(VENUE_ID);

        assertEquals(3, summary.female().level(), "认领人的 8 档不参与中位数");
        assertEquals(1, summary.reporterCount());
        assertNotNull(summary.headlineText());
        assertTrue(summary.headlineText().contains("约50 · 1人"), "实际：" + summary.headlineText());
        assertEquals(2, summary.rows().size(), "明细照常展示两行");
        CrowdSummary.CrowdReportRow ownerRow = summary.rows().stream()
                .filter(r -> r.userId().equals(10L)).findFirst().orElseThrow();
        assertEquals(CrowdTrustService.BADGE_OWNER, ownerRow.badgeText(), "店家行如实标注");
        assertTrue(summary.ageText().contains("30"), "「N 分钟前」取最新一条计入统计的上报，不被店家刷新");
    }

    @Test
    void windowWithOnlyTheClaimantStillTellsTheTruth() {
        LocalDateTime now = LocalDateTime.now();
        stubReadPath(10L, List.of(report(1L, 10L, 8, null, now.minusMinutes(5))), 10L);

        CrowdSummary summary = crowdReportService.summary(VENUE_ID);

        assertTrue(summary.hasData());
        assertNull(summary.female());
        assertEquals(0, summary.reporterCount());
        assertEquals(CrowdReportService.OWNER_ONLY_TEXT, summary.mainText());
        assertEquals("EMPTY", summary.tier());
        assertNull(summary.headlineText(), "回看范围内也没有舞友的票");
    }

    @Test
    void emptyWindowFallsBackToLastNightInTheHeadline() {
        LocalDateTime now = LocalDateTime.now();
        // 上一个营业日内的时刻（营业日起点前 1 小时）
        LocalDateTime lastNight = BusinessDay.startOf(BusinessDay.of(now)).minusHours(1);
        VenueCrowdReport lastNightReport = report(9L, 10L, 3, null, lastNight);
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue(null)));
        when(crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(eq(VENUE_ID), any()))
                .thenReturn(List.of())                                             // 统计窗口（mock 为空 ⇒ 走回看路径）
                .thenReturn(List.of(lastNightReport));                             // 回看范围
        // 明细展示（2026-10-07）：统计窗口无有效票但历史有行 ⇒ 明细照常展示这 1 条
        // （本测试直接 mock 掉统计窗口、只验回看摘要链路，不依赖真实窗口边界）
        when(crowdReportRepository.findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(eq(VENUE_ID), any()))
                .thenReturn(new PageImpl<>(List.of(lastNightReport),
                        PageRequest.of(0, CrowdPolicy.DETAIL_ROWS_LIMIT), 1L));
        when(crowdTrustService.weights(any())).thenReturn(Map.of(10L, 1.0));
        when(crowdReportLikeService.likeCountsByReportIds(any())).thenReturn(Map.of());
        when(crowdReportLikeService.likedReportIds(any(), any())).thenReturn(Set.of());

        CrowdSummary summary = crowdReportService.summary(VENUE_ID);

        assertFalse(summary.hasData(), "窗口内无有效票 ⇒ 统计仍为空态（统计口径一步不退）");
        assertEquals("昨晚 " + String.format("%02d:%02d", lastNight.getHour(), lastNight.getMinute()) + " 约50 · 1人",
                summary.headlineText());
        assertEquals(CrowdReportService.EMPTY_TEXT, summary.emptyText());
    }

    @Test
    void noReportsAtAllMeansNoHeadlineAndNoRows() {
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue(null)));
        when(crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(eq(VENUE_ID), any()))
                .thenReturn(List.of());
        when(crowdReportRepository.findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(eq(VENUE_ID), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, CrowdPolicy.DETAIL_ROWS_LIMIT), 0L));
        CrowdSummary summary = crowdReportService.summary(VENUE_ID);
        assertNull(summary.headlineText());
        assertFalse(summary.hasData());
        assertTrue(summary.rows().isEmpty(), "从来没人报过 ⇒ 明细确实为空（此时才轮到空态文案）");
    }

    // ── 明细展示 vs 统计口径的边界（2026-10-07 用户拍板「最近三条、不管过没过期」）────

    /**
     * 本轮的核心契约：<b>展示放宽、统计不放宽</b>。
     * 场景 = 用户最在意的那种：清晨进门店页，昨晚有 3 条上报但已全出有效期窗口（1 天）。
     * 期望 = 明细照常展示那 3 条（每行标 expired），而统计字段一律按窗口外处理
     * （hasData=false / mainText=null / headline 走回看）——两条取数互不串味。
     */
    @Test
    void expiredRowsAreStillShownButNeverCountedIntoTheStatistics() {
        LocalDateTime now = LocalDateTime.now();
        // 三条都是前一夜（窗口外，1 天前）的上报
        VenueCrowdReport r1 = report(1L, 10L, 5, null, now.minusHours(28));
        VenueCrowdReport r2 = report(2L, 11L, 6, null, now.minusHours(29));
        VenueCrowdReport r3 = report(3L, 12L, 5, null, now.minusHours(30));
        stubReadPath(null, List.of(), List.of(r1, r2, r3), 10L, 11L, 12L);

        CrowdSummary summary = crowdReportService.summary(VENUE_ID);

        // 展示侧：三条都在，且逐行标过期
        assertEquals(3, summary.rows().size(), "窗口外也要展示最近 3 条（本轮根因：这里原本是空表）");
        assertTrue(summary.rows().stream().allMatch(CrowdSummary.CrowdReportRow::expired),
                "全部出有效期窗口（1 天）⇒ 每行都要标 expired，前端据此置灰 +「已过期」");
        // 统计侧：一步不退
        assertFalse(summary.hasData(), "过期票绝不能让「今晚有数据」为真");
        assertNull(summary.female(), "过期票绝不能进中位数");
        assertNull(summary.mainText(), "过期票绝不能被写成「今晚人气」");
        assertEquals("EMPTY", summary.tier(), "置信度分层同样按窗口外处理");
    }

    /** 新旧行为的关键差别：窗口内只有 1 条 + 历史另有 2 条 ⇒ 展示补齐到 3 条，但统计仍只看那 1 条。 */
    @Test
    void detailRowsTopUpToThreeWhileStatisticsStayWindowOnly() {
        LocalDateTime now = LocalDateTime.now();
        VenueCrowdReport live = report(1L, 10L, 5, null, now.minusMinutes(20));   // 窗口内
        VenueCrowdReport old1 = report(2L, 11L, 8, null, now.minusHours(30));     // 窗口外（1 天前）
        VenueCrowdReport old2 = report(3L, 12L, 8, null, now.minusHours(31));     // 窗口外
        stubReadPath(null, List.of(live), List.of(live, old1, old2), 10L, 11L, 12L);

        CrowdSummary summary = crowdReportService.summary(VENUE_ID);

        assertEquals(3, summary.rows().size(), "最近 3 条全展示，不因为过期被裁掉");
        assertEquals(1, summary.rows().stream().filter(r -> !r.expired()).count(),
                "只有窗口内那 1 条未过期");
        assertTrue(summary.hasData());
        assertEquals(5, summary.female().level(), "中位数只由窗口内那 1 条决定（历史两档 8 不参与）");
        assertEquals(1, summary.reporterCount());
    }

    /**
     * 展示条数上限 = {@link CrowdPolicy#DETAIL_ROWS_LIMIT}（3，用户拍板），且**分页参数取自该常量**。
     * 这里用 Mockito 捕获传给仓储的 PageRequest，而不是断言一个写死的字面量——
     * 后者只在"恰好等于 3"时通过，改成 5 也会红，而真正该防的是
     * 「有人另写一个 PageRequest.of(0, 5) 绕过口径常量」。
     */
    @Test
    void detailRowsArePagedWithThePolicyLimit() {
        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        // 只桩本测试真正走到的两路：统计窗口（空）+ 明细分页（空列表 ⇒ 不触发后续用户/点赞查询）
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue(null)));
        when(crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(eq(VENUE_ID), any()))
                .thenReturn(List.of());
        when(crowdReportRepository.findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(eq(VENUE_ID), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, CrowdPolicy.DETAIL_ROWS_LIMIT), 0L));

        crowdReportService.summary(VENUE_ID);

        verify(crowdReportRepository).findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(eq(VENUE_ID), captor.capture());
        assertEquals(CrowdPolicy.DETAIL_ROWS_LIMIT, captor.getValue().getPageSize(),
                "明细分页条数必须取自 CrowdPolicy.DETAIL_ROWS_LIMIT（禁内联字面量）");
        assertEquals(3, CrowdPolicy.DETAIL_ROWS_LIMIT, "用户拍板：最近三条");
    }

    // ── 写：营业日 / 自然日分坐标 ───────────────────────────────────────────────

    @Test
    void submitWritesBusinessDateAsTheUniqueKeyAndKeepsReportDateNatural() {
        UserContext.set(10L, UserRole.USER);
        LocalDateTime now = LocalDateTime.now();
        stubReadPath(null, List.of(report(1L, 10L, 5, null, now)), 10L);

        crowdReportService.submit(VENUE_ID, new SubmitCrowdReportRequest(5, null));

        ArgumentCaptor<LocalDate> reportDate = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> businessDate = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDateTime> createdAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> updatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(crowdReportRepository).upsert(eq(VENUE_ID), eq(10L), eq(5), isNull(),
                reportDate.capture(), businessDate.capture(), createdAt.capture(), updatedAt.capture());

        assertEquals(createdAt.getValue(), updatedAt.getValue(), "一次请求只取一次时钟");
        assertEquals(createdAt.getValue().toLocalDate(), reportDate.getValue(), "report_date 保持自然日（行为统计口径）");
        assertEquals(BusinessDay.of(createdAt.getValue()), businessDate.getValue(), "唯一键用营业日（05:00 分界）");
    }

    @Test
    void submitRejectsAVenueThatIsNotOpen() {
        UserContext.set(10L, UserRole.USER);
        Venue closed = venue(null);
        closed.setStatus(VenueStatus.SUSPENDED);
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(closed));

        org.junit.jupiter.api.Assertions.assertThrows(org.quwuting.quwutingservice.exception.BusinessException.class,
                () -> crowdReportService.submit(VENUE_ID, new SubmitCrowdReportRequest(5, null)));
        verify(crowdReportRepository, never()).upsert(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), any());
    }

    // ── 确认积分：与详情页 CONFIRMED 同一个 verdict ────────────────────────────

    @Test
    void confirmRewardsGoToVotersWithinToleranceOfTheMedianOnly() {
        UserContext.set(10L, UserRole.USER);
        LocalDateTime now = LocalDateTime.now();
        VenueCrowdReport r10 = report(101L, 10L, 5, null, now.minusMinutes(3));
        VenueCrowdReport r11 = report(102L, 11L, 6, null, now.minusMinutes(20));
        VenueCrowdReport r12 = report(103L, 12L, 8, null, now.minusMinutes(40));
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue(null)));
        // 第 1 次（提交前基线）：只有 10 之外的两人 5 与 8 ⇒ 未确认；之后（提交后）三人 ⇒ 中位 6，5/6 一致 ⇒ 确认
        when(crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(eq(VENUE_ID), any()))
                .thenReturn(List.of(r11, r12))
                .thenReturn(List.of(r10, r11, r12));
        when(crowdTrustService.weights(any())).thenReturn(Map.of(10L, 1.0, 11L, 1.0, 12L, 1.0));
        when(userRepository.findByIdInAndDeletedFalse(any()))
                .thenReturn(List.of(user(10L, "舞友10"), user(11L, "舞友11"), user(12L, "舞友12")));
        when(crowdReportLikeService.likeCountsByReportIds(any())).thenReturn(Map.of());
        when(crowdReportLikeService.likedReportIds(any(), any())).thenReturn(Set.of());
        // 明细展示取数（2026-10-07 summary() 的第二路）：submit() 末尾也会组摘要，
        // 未桩会拿到 null Page → NPE（与统计窗口那路是两回事，故单独一条）
        when(crowdReportRepository.findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(eq(VENUE_ID), any()))
                .thenReturn(new PageImpl<>(List.of(r10, r11, r12),
                        PageRequest.of(0, CrowdPolicy.DETAIL_ROWS_LIMIT), 3L));
        when(pointsService.crowdConfirmReward()).thenReturn(3);
        when(pointsService.rewardCrowdConfirm(10L, 101L)).thenReturn(13L);
        when(pointsService.rewardCrowdConfirm(11L, 102L)).thenReturn(23L);
        when(favoriteRepository.findUserIdsByVenueId(VENUE_ID)).thenReturn(List.of(10L, 11L, 50L));

        CrowdSummary resp = crowdReportService.submit(VENUE_ID, new SubmitCrowdReportRequest(5, null));

        assertEquals("您的上报被 2 位舞友确认 · +3 积分已到账", resp.rewardText());
        verify(pointsService).rewardCrowdConfirm(10L, 101L);
        verify(pointsService).rewardCrowdConfirm(11L, 102L);
        verify(pointsService, never()).rewardCrowdConfirm(eq(12L), anyLong());
        // 触发者本人不发站内信（提交响应已即时告知）；其余受奖者收到确认信
        verify(messageService).create(eq(11L), eq(MessageType.CROWD_CONFIRMED), anyString(),
                contains("已被 2 位舞友确认"), eq("VENUE"), eq(VENUE_ID));
        verify(messageService, never()).create(eq(10L), any(), anyString(), anyString(), anyString(), anyLong());
        // 该店首次达确认 ⇒ 收藏联动：只通知未受奖、非触发者的收藏者
        verify(messageService).create(eq(50L), eq(MessageType.CROWD_CONFIRMED), anyString(),
                contains("今晚热度已被 2 位舞友确认"), eq("VENUE"), eq(VENUE_ID));
    }

    @Test
    void theClaimantNeverEarnsAConfirmReward() {
        UserContext.set(13L, UserRole.USER);
        LocalDateTime now = LocalDateTime.now();
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue(10L)));
        List<VenueCrowdReport> rows = List.of(
                report(101L, 10L, 5, null, now.minusMinutes(5)),   // 认领人
                report(102L, 11L, 5, null, now.minusMinutes(4)),
                report(103L, 12L, 5, null, now.minusMinutes(3)),
                report(104L, 13L, 5, null, now.minusMinutes(1)));
        when(crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(eq(VENUE_ID), any())).thenReturn(rows);
        when(crowdTrustService.weights(any())).thenReturn(Map.of(10L, 1.0, 11L, 1.0, 12L, 1.0, 13L, 1.0));
        when(userRepository.findByIdInAndDeletedFalse(any())).thenReturn(List.of());
        when(crowdReportLikeService.likeCountsByReportIds(any())).thenReturn(Map.of());
        when(crowdReportLikeService.likedReportIds(any(), any())).thenReturn(Set.of());
        // 明细展示取数（2026-10-07 summary() 的第二路，与统计窗口那路分开）
        when(crowdReportRepository.findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(eq(VENUE_ID), any()))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, CrowdPolicy.DETAIL_ROWS_LIMIT), rows.size()));
        when(pointsService.crowdConfirmReward()).thenReturn(3);
        when(pointsService.rewardCrowdConfirm(anyLong(), anyLong())).thenReturn(1L);

        crowdReportService.submit(VENUE_ID, new SubmitCrowdReportRequest(5, null));

        verify(pointsService, never()).rewardCrowdConfirm(eq(10L), anyLong());
        verify(pointsService).rewardCrowdConfirm(11L, 102L);
        verify(pointsService).rewardCrowdConfirm(12L, 103L);
        verify(pointsService).rewardCrowdConfirm(13L, 104L);
    }

    // ── 管理端：说法不一与详情页同一个判定函数 ──────────────────────────────────

    @Test
    void adminConflictFlagUsesTheSameToleranceAsTheDetailPage() {
        LocalDateTime now = LocalDateTime.now();
        VenueCrowdReport a1 = report(1L, 10L, 5, null, now.minusHours(1));
        VenueCrowdReport a2 = report(2L, 11L, 6, null, now.minusHours(2));    // 相邻档 ⇒ 不冲突（旧口径 50% < 60% 会判冲突）
        VenueCrowdReport b1 = report(3L, 12L, 1, null, now.minusHours(1));
        b1.setVenueId(200L);
        VenueCrowdReport b2 = report(4L, 13L, 8, null, now.minusHours(2));    // 相隔七档 ⇒ 冲突
        b2.setVenueId(200L);
        when(crowdReportRepository.findByCreatedAtAfterAndDeletedFalse(any())).thenReturn(List.of(a1, a2, b1, b2));
        Venue v100 = venue(null);
        Venue v200 = venue(null);
        v200.setId(200L);
        v200.setName("南来北往");
        when(venueRepository.findAllById(any())).thenReturn(List.of(v100, v200));
        when(userRepository.findByIdInAndDeletedFalse(any())).thenReturn(List.of());
        when(crowdTrustService.weights(any())).thenReturn(Map.of(10L, 1.0, 11L, 1.0, 12L, 1.0, 13L, 1.0));

        List<AdminCrowdReportSummary> summaries = crowdReportService.adminSummaries();

        AdminCrowdReportSummary s100 = summaries.stream().filter(s -> s.venueId().equals(VENUE_ID)).findFirst().orElseThrow();
        AdminCrowdReportSummary s200 = summaries.stream().filter(s -> s.venueId().equals(200L)).findFirst().orElseThrow();
        assertFalse(s100.conflict());
        assertTrue(s200.conflict());
    }

    // ── 列表公共面 ───────────────────────────────────────────────────────────

    @Test
    void badgeTextsToleratesWideNumericTypesFromNativeSql() {
        // 原生 SQL 的数值列类型由驱动决定（BIGINT 可能是 Long，也可能是 BigInteger）：直接强转会 ClassCastException
        when(crowdReportRepository.countDistinctUsersByVenueIdsSince(any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{BigInteger.valueOf(5), BigInteger.valueOf(3)},
                        new Object[]{6L, 2L}));

        Map<Long, String> badges = crowdReportService.badgeTextsByVenue(List.of(5L, 6L));

        assertEquals("3人报过", badges.get(5L));
        assertNull(badges.get(6L), "不足 3 人不上列表");
    }
}
