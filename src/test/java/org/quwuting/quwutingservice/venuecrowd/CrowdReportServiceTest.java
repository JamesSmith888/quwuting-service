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

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** 读路径共用桩：权重默认 1.0；用户资料；点赞聚合为空。 */
    private void stubReadPath(Long claimedBy, List<VenueCrowdReport> windowRows, long... userIds) {
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue(claimedBy)));
        when(crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(eq(VENUE_ID), any()))
                .thenReturn(windowRows);
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
        assertEquals("今晚 约100 · 1人", summary.headlineText());
        assertEquals(1, summary.rows().size());
        CrowdSummary.CrowdReportRow row = summary.rows().get(0);
        assertEquals("约100", row.femaleLevelName());
        assertEquals("约50", row.maleLevelName());
        assertTrue(row.likeExpiresInSec() > 5 * 3600, "还能赞的剩余秒数 ≈ 窗口 6h − 已过 10 分钟");
        assertTrue(row.likeExpiresInSec() <= 6 * 3600 - 10 * 60);
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
        assertEquals("今晚 约100 · 3人", summary.headlineText());
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
        assertEquals("今晚 约50 · 1人", summary.headlineText());
        assertEquals(2, summary.rows().size(), "明细表照常展示两行");
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
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue(null)));
        when(crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(eq(VENUE_ID), any()))
                .thenReturn(List.of())                                             // 今晚窗口
                .thenReturn(List.of(report(9L, 10L, 3, null, lastNight)));         // 回看范围
        when(crowdTrustService.weights(any())).thenReturn(Map.of(10L, 1.0));

        CrowdSummary summary = crowdReportService.summary(VENUE_ID);

        assertFalse(summary.hasData());
        assertEquals("昨晚 " + String.format("%02d:%02d", lastNight.getHour(), lastNight.getMinute()) + " 约50 · 1人",
                summary.headlineText());
        assertEquals(CrowdReportService.EMPTY_TEXT, summary.emptyText());
    }

    @Test
    void noReportsAtAllMeansNoHeadline() {
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue(null)));
        when(crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(eq(VENUE_ID), any()))
                .thenReturn(List.of());
        CrowdSummary summary = crowdReportService.summary(VENUE_ID);
        assertNull(summary.headlineText());
        assertFalse(summary.hasData());
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

    @Test
    void likeExpiresInSecIsTheWindowMinusAge() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 7, 23, 0);
        assertEquals(6 * 3600 - 600, CrowdReportService.likeExpiresInSec(now.minusMinutes(10), now));
        assertEquals(0, CrowdReportService.likeExpiresInSec(now.minusHours(6), now), "恰好 6h ⇒ 已到期");
        assertEquals(0, CrowdReportService.likeExpiresInSec(now.minusHours(7), now));
        assertEquals(0, CrowdReportService.likeExpiresInSec(null, now));
    }
}
