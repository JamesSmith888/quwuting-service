package org.quwuting.quwutingservice.venuecrowd;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdLikeResponse;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdLikersResponse;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReport;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReportLike;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportLikeRepository;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdReportLikeService;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdTrustService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点赞与「谁觉得有用」：分层披露（本人 / 管理员看完整名单，其余只看汇总）、自赞放开、通知合并。
 */
@ExtendWith(MockitoExtension.class)
class CrowdReportLikeServiceTest {

    private static final Long VENUE_ID = 100L;
    private static final Long REPORT_ID = 501L;
    private static final Long REPORTER = 10L;

    @Mock
    private VenueCrowdReportLikeRepository likeRepository;
    @Mock
    private VenueCrowdReportRepository crowdReportRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private MessageService messageService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CrowdTrustService crowdTrustService;

    private CrowdReportLikeService service;

    @BeforeEach
    void setUp() {
        service = new CrowdReportLikeService(likeRepository, crowdReportRepository, venueRepository,
                messageService, userRepository, crowdTrustService);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private VenueCrowdReport reportCreatedMinutesAgo(int minutes) {
        VenueCrowdReport r = new VenueCrowdReport();
        r.setId(REPORT_ID);
        r.setVenueId(VENUE_ID);
        r.setUserId(REPORTER);
        r.setCreatedAt(LocalDateTime.now().minusMinutes(minutes));
        return r;
    }

    private static VenueCrowdReportLike like(long likerId, int minutesAgo) {
        VenueCrowdReportLike l = new VenueCrowdReportLike();
        l.setReportId(REPORT_ID);
        l.setLikerId(likerId);
        l.setUpdatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        return l;
    }

    private static User user(long id, String nickname, String avatar) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        u.setAvatarUrl(avatar);
        return u;
    }

    private static Venue venue() {
        Venue v = new Venue();
        v.setId(VENUE_ID);
        v.setName("抖舞");
        return v;
    }

    // ── 汇总文案 ─────────────────────────────────────────────────────────────

    @Test
    void summaryTextOrdersBadgesByWeightAndOmitsZeroCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("普通", 2L);
        counts.put("资深", 1L);
        counts.put("常客", 0L);
        assertEquals("3 人觉得有用 · 其中 1 位资深、2 位普通", CrowdReportLikeService.summaryText(3, counts, true));
    }

    @Test
    void summaryTextWithoutTiersIsJustTheCount() {
        assertEquals("3 人觉得有用", CrowdReportLikeService.summaryText(3, Map.of("资深", 3L), false));
        assertEquals("1 人觉得有用", CrowdReportLikeService.summaryText(1, Map.of(), true));
    }

    @Test
    void ownerBadgeComesLast() {
        assertEquals("2 人觉得有用 · 其中 1 位普通、1 位店家",
                CrowdReportLikeService.summaryText(2, Map.of("店家", 1L, "普通", 1L), true));
    }

    // ── 分层披露 ─────────────────────────────────────────────────────────────

    private void stubLikers() {
        when(crowdReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(reportCreatedMinutesAgo(30)));
        when(likeRepository.findByReportIdAndDeletedFalseOrderByUpdatedAtDesc(REPORT_ID))
                .thenReturn(List.of(like(20, 1), like(21, 5), like(REPORTER, 9)));   // 含自赞
        when(crowdTrustService.weights(any())).thenReturn(Map.of(20L, 3.0, 21L, 1.0, REPORTER, 1.0));
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue()));
    }

    @Test
    void strangersOnlySeeTheTieredSummaryNeverTheNames() {
        stubLikers();
        UserContext.set(99L, UserRole.USER);

        CrowdLikersResponse resp = service.likers(VENUE_ID, REPORT_ID);

        assertEquals(CrowdLikersResponse.MODE_SUMMARY, resp.viewMode());
        assertEquals(3, resp.likeCount());
        assertEquals("3 人觉得有用 · 其中 1 位资深、2 位普通", resp.summaryText());
        assertTrue(resp.likers().isEmpty(), "路人看不到任何点赞者条目");
        verify(userRepository, never()).findByIdInAndDeletedFalse(any());
    }

    @Test
    void anonymousVisitorsAreStrangersToo() {
        stubLikers();
        CrowdLikersResponse resp = service.likers(VENUE_ID, REPORT_ID);
        assertEquals(CrowdLikersResponse.MODE_SUMMARY, resp.viewMode());
        assertTrue(resp.likers().isEmpty());
    }

    @Test
    void theReporterSeesTheFullListIncludingTheirOwnLike() {
        stubLikers();
        when(userRepository.findByIdInAndDeletedFalse(any())).thenReturn(List.of(
                user(20, "阿杰", "https://img/20.png"), user(21, "  ", null), user(REPORTER, "小雅", null)));
        UserContext.set(REPORTER, UserRole.USER);

        CrowdLikersResponse resp = service.likers(VENUE_ID, REPORT_ID);

        assertEquals(CrowdLikersResponse.MODE_FULL, resp.viewMode());
        assertEquals("3 人觉得有用", resp.summaryText());
        assertEquals(3, resp.likers().size());
        CrowdLikersResponse.Liker first = resp.likers().get(0);
        assertEquals("阿杰", first.nickname());
        assertEquals("资深", first.badgeText());
        assertEquals("https://img/20.png", first.avatarUrl());
        assertEquals("1 分钟前", first.ageText());
        assertEquals("匿名", resp.likers().get(1).nickname(), "空昵称兜底");
        assertEquals(null, resp.likers().get(1).avatarUrl());
        CrowdLikersResponse.Liker self = resp.likers().get(2);
        assertTrue(self.isMine(), "自赞放开：上报者可能在名单里，标「我」");
    }

    @Test
    void adminsSeeTheFullListToo() {
        stubLikers();
        when(userRepository.findByIdInAndDeletedFalse(any())).thenReturn(List.of());
        UserContext.set(1L, UserRole.ADMIN);
        assertEquals(CrowdLikersResponse.MODE_FULL, service.likers(VENUE_ID, REPORT_ID).viewMode());
    }

    @Test
    void noLikesYieldsAnEmptyStateWithoutQueryingUsers() {
        when(crowdReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(reportCreatedMinutesAgo(30)));
        when(likeRepository.findByReportIdAndDeletedFalseOrderByUpdatedAtDesc(REPORT_ID)).thenReturn(List.of());
        CrowdLikersResponse resp = service.likers(VENUE_ID, REPORT_ID);
        assertEquals(0, resp.likeCount());
        assertEquals(CrowdReportLikeService.NO_LIKES_TEXT, resp.summaryText());
        verify(crowdTrustService, never()).weights(any());
    }

    @Test
    void likersIsReadableAfterTheWindowButNotForMissingOrForeignReports() {
        // 只读名单不受 6h 窗口限制（历史页的「有用 N」同样可点开）
        when(crowdReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(reportCreatedMinutesAgo(60 * 20)));
        when(likeRepository.findByReportIdAndDeletedFalseOrderByUpdatedAtDesc(REPORT_ID)).thenReturn(List.of());
        assertEquals(0, service.likers(VENUE_ID, REPORT_ID).likeCount());

        BusinessException foreign = assertThrows(BusinessException.class, () -> service.likers(999L, REPORT_ID));
        assertEquals(1019, foreign.getCode());
    }

    // ── 赞 / 取消赞 / 通知合并 ────────────────────────────────────────────────

    @Test
    void firstLikeByAnotherUserMergesIntoTheUnreadNotification() {
        UserContext.set(20L, UserRole.USER);
        VenueCrowdReport report = reportCreatedMinutesAgo(30);
        when(crowdReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(likeRepository.like(eq(REPORT_ID), eq(20L), any(), any())).thenReturn(1);
        when(likeRepository.countByReportIdAndDeletedFalse(REPORT_ID)).thenReturn(2L);
        when(venueRepository.findById(VENUE_ID)).thenReturn(Optional.of(venue()));

        CrowdLikeResponse resp = service.like(VENUE_ID, REPORT_ID);

        assertEquals(2, resp.likeCount());
        assertTrue(resp.likedByMe());
        // 合并窗口 = 这条上报创建之后；正文携带累计数
        verify(messageService).createOrMergeUnread(eq(REPORTER), eq(MessageType.CROWD_REPORT_LIKED), anyString(),
                contains("收到 2 个赞"), eq("VENUE"), eq(VENUE_ID), eq(report.getCreatedAt()));
    }

    @Test
    void selfLikeIsAllowedButNeverNotifiesTheReporterAboutThemselves() {
        UserContext.set(REPORTER, UserRole.USER);
        when(crowdReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(reportCreatedMinutesAgo(30)));
        when(likeRepository.like(eq(REPORT_ID), eq(REPORTER), any(), any())).thenReturn(1);
        when(likeRepository.countByReportIdAndDeletedFalse(REPORT_ID)).thenReturn(1L);

        CrowdLikeResponse resp = service.like(VENUE_ID, REPORT_ID);

        assertTrue(resp.likedByMe());
        verify(messageService, never()).createOrMergeUnread(anyLong(), any(), anyString(), anyString(), anyString(),
                anyLong(), any());
    }

    @Test
    void restoredLikeDoesNotNotifyAgain() {
        UserContext.set(20L, UserRole.USER);
        when(crowdReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(reportCreatedMinutesAgo(30)));
        when(likeRepository.like(eq(REPORT_ID), eq(20L), any(), any())).thenReturn(2);
        when(likeRepository.countByReportIdAndDeletedFalse(REPORT_ID)).thenReturn(3L);

        service.like(VENUE_ID, REPORT_ID);

        verify(messageService, never()).createOrMergeUnread(anyLong(), any(), anyString(), anyString(), anyString(),
                anyLong(), any());
    }

    @Test
    void likingAnExpiredReportIsRefusedWithBusinessCode1020() {
        UserContext.set(20L, UserRole.USER);
        when(crowdReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(reportCreatedMinutesAgo(60 * 7)));
        BusinessException e = assertThrows(BusinessException.class, () -> service.like(VENUE_ID, REPORT_ID));
        assertEquals(1020, e.getCode());
        verify(likeRepository, never()).like(anyLong(), anyLong(), any(), any());
    }
}
