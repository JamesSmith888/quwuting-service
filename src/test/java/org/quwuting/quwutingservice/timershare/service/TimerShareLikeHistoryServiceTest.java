package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeHistoryResponse;
import org.quwuting.quwutingservice.timershare.entity.TimerShareLike;
import org.quwuting.quwutingservice.timershare.repository.TimerShareJoinRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareLikeRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareRepository;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 点赞记录编排（Mockito，不连库）：开关 / 速率 / 取数范围（本人 / 对方）/ 授权边界（解析不出 ⇒ UNRESOLVED，
 * 绝不退回本人）/ 调用者自己那组最前 / 上限与 truncated / 昵称头像换算。时钟注入。
 */
@ExtendWith(MockitoExtension.class)
class TimerShareLikeHistoryServiceTest {

    private static final long ME = 10L;
    private static final long A = 21L;
    private static final long B = 22L;
    private static final long T0 = 1_780_000_000_000L;
    private static final String TOKEN = "AbC123xyz0";

    @Mock
    private TimerShareRepository shareRepository;
    @Mock
    private TimerShareJoinRepository joinRepository;
    @Mock
    private TimerShareLikeRepository likeRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TimerShareLikeService likeService;
    @Mock
    private OpsConfigService opsConfigService;

    private final long[] now = {T0 + 60_000};
    private TimerShareLikeHistoryService service;

    @BeforeEach
    void setUp() {
        service = new TimerShareLikeHistoryService(shareRepository, joinRepository, likeRepository, userRepository,
                likeService, opsConfigService, () -> now[0]);
        lenient().when(opsConfigService.isEnabled(OpsConfigService.KEY_TIMER_SHARE_LIKE_ENABLED, true)).thenReturn(true);
        lenient().when(shareRepository.findRecentIdsByHost(eq(ME), any(Pageable.class))).thenReturn(List.of(1L));
        lenient().when(joinRepository.findRecentShareIdsByUser(eq(ME), any(Pageable.class))).thenReturn(List.of(2L));
    }

    private static LocalDateTime localOf(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    private static TimerShareLike like(long shareId, long liker, long liked, long atMs) {
        TimerShareLike row = new TimerShareLike();
        row.setShareId(shareId);
        row.setLikerId(liker);
        row.setLikedUserId(liked);
        row.setUpdatedAt(localOf(atMs));
        return row;
    }

    private static User user(long id, String nickname, String avatar) {
        User user = new User();
        user.setId(id);
        user.setNickname(nickname);
        user.setAvatarUrl(avatar);
        return user;
    }

    @Test
    void disabledSwitchReturnsEnabledFalseAndTouchesNothing() {
        when(opsConfigService.isEnabled(OpsConfigService.KEY_TIMER_SHARE_LIKE_ENABLED, true)).thenReturn(false);

        TimerShareLikeHistoryResponse res = service.history(ME, null, null, null);

        assertFalse(res.enabled());
        assertTrue(res.groups().isEmpty());
        verifyNoInteractions(shareRepository, joinRepository, likeRepository, userRepository, likeService);
    }

    @Test
    void readsLikesOfEveryShareTheCallerIsAMemberOfAndGroupsThemByPerson() {
        when(likeRepository.findInvolving(anyCollection(), eq(ME), any(Pageable.class))).thenReturn(List.of(
                like(1, A, ME, T0 + 3_000), like(1, ME, A, T0 + 2_000), like(2, B, ME, T0 + 1_000)));
        when(userRepository.findByIdInAndDeletedFalse(anyCollection())).thenReturn(List.of(
                user(A, "小美", "http://a.png"), user(B, null, null)));

        TimerShareLikeHistoryResponse res = service.history(ME, null, null, null);

        assertTrue(res.enabled());
        assertFalse(res.truncated());
        assertEquals(2, res.summary().received());
        assertEquals(1, res.summary().given());
        assertEquals(2, res.summary().people());
        assertEquals(2, res.groups().size());
        TimerShareLikeHistoryResponse.Group first = res.groups().get(0);
        assertEquals("小美", first.nickname());
        assertEquals("http://a.png", first.avatarUrl());
        assertEquals(1, first.received());
        assertEquals(1, first.given());
        assertFalse(first.me());
        assertEquals("RECEIVED", first.events().get(0).direction());
        assertEquals(T0 + 3_000, first.events().get(0).atMs());
        // 昵称 / 头像不可查 → null 下发，客户端兜底
        assertNull(res.groups().get(1).nickname());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<Long>> ids = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(likeRepository).findInvolving(ids.capture(), eq(ME), any(Pageable.class));
        assertEquals(java.util.Set.of(1L, 2L), java.util.Set.copyOf(ids.getValue()));
    }

    @Test
    void noSharesMeansNoLikeQueryAndAnEmptyRecord() {
        when(shareRepository.findRecentIdsByHost(eq(ME), any(Pageable.class))).thenReturn(List.of());
        when(joinRepository.findRecentShareIdsByUser(eq(ME), any(Pageable.class))).thenReturn(List.of());

        TimerShareLikeHistoryResponse res = service.history(ME, null, null, null);

        assertTrue(res.enabled());
        assertTrue(res.groups().isEmpty());
        assertEquals(0, res.summary().people());
        verify(likeRepository, never()).findInvolving(anyCollection(), anyLong(), any(Pageable.class));
        verify(userRepository, never()).findByIdInAndDeletedFalse(anyCollection());
    }

    @Test
    void selfRequestIsTheSelfSubjectAndNeverResolvesAPeer() {
        when(likeRepository.findInvolving(anyCollection(), eq(ME), any(Pageable.class)))
                .thenReturn(List.of(like(1, A, ME, T0 + 3_000)));
        when(userRepository.findByIdInAndDeletedFalse(anyCollection())).thenReturn(List.of(user(A, "A", null)));

        TimerShareLikeHistoryResponse res = service.history(ME, null, null, null);

        assertEquals("SELF", res.subject());
        assertFalse(res.groups().get(0).me());
        verifyNoInteractions(likeService);
    }

    // ── 看别人的记录（用户 2026-10-13：点头像看「该用户」所有的点赞记录）──

    private void stubSubjectShares(long subject, Long... shareIds) {
        lenient().when(shareRepository.findRecentIdsByHost(eq(subject), any(Pageable.class))).thenReturn(List.of(shareIds));
        lenient().when(joinRepository.findRecentShareIdsByUser(eq(subject), any(Pageable.class))).thenReturn(List.of());
    }

    @Test
    void aPeersRecordIsReadFromThePeersSessionsNotTheCallers() {
        stubSubjectShares(A, 7L, 8L);
        when(likeService.resolvePeerUserId(ME, TOKEN, "JOINER", 2)).thenReturn(A);
        // A 的记录：B 赞了 A、A 赞了 B、ME 赞了 A
        when(likeRepository.findInvolving(anyCollection(), eq(A), any(Pageable.class))).thenReturn(List.of(
                like(7, B, A, T0 + 3_000), like(7, A, B, T0 + 2_000), like(8, ME, A, T0 + 1_000)));
        when(userRepository.findByIdInAndDeletedFalse(anyCollection())).thenReturn(List.of(
                user(B, "阿强", "http://b.png"), user(ME, "我", null)));

        TimerShareLikeHistoryResponse res = service.history(ME, TOKEN, "JOINER", 2);

        assertEquals("PEER", res.subject());
        // 总览是 A 的：收到 2（B、ME）· 送出 1（A→B）· 共 2 位
        assertEquals(2, res.summary().received());
        assertEquals(1, res.summary().given());
        assertEquals(2, res.summary().people());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<Long>> ids = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(likeRepository).findInvolving(ids.capture(), eq(A), any(Pageable.class));
        assertEquals(java.util.Set.of(7L, 8L), java.util.Set.copyOf(ids.getValue()));
        // 绝不顺手去取调用者自己的记录
        verify(likeRepository, never()).findInvolving(anyCollection(), eq(ME), any(Pageable.class));
        verify(shareRepository, never()).findRecentIdsByHost(eq(ME), any(Pageable.class));
    }

    @Test
    void theViewersOwnGroupComesFirstInAPeersRecordEvenWhenOlder() {
        stubSubjectShares(A, 7L);
        when(likeService.resolvePeerUserId(ME, TOKEN, "HOST", 0)).thenReturn(A);
        when(likeRepository.findInvolving(anyCollection(), eq(A), any(Pageable.class))).thenReturn(List.of(
                like(7, B, A, T0 + 3_000), like(7, ME, A, T0 + 1_000)));
        when(userRepository.findByIdInAndDeletedFalse(anyCollection())).thenReturn(List.of(user(B, "B", null), user(ME, "我", null)));

        TimerShareLikeHistoryResponse res = service.history(ME, TOKEN, "HOST", 0);

        assertEquals("我", res.groups().get(0).nickname());
        assertTrue(res.groups().get(0).me());
        assertEquals("B", res.groups().get(1).nickname());
        assertFalse(res.groups().get(1).me());
        assertEquals(2, res.summary().people());
    }

    @Test
    void aPeerWithNoLikesAtAllIsAnEmptyRecordNotAnError() {
        stubSubjectShares(A, 7L);
        when(likeService.resolvePeerUserId(ME, TOKEN, "HOST", 0)).thenReturn(A);
        when(likeRepository.findInvolving(anyCollection(), eq(A), any(Pageable.class))).thenReturn(List.of());

        TimerShareLikeHistoryResponse res = service.history(ME, TOKEN, "HOST", 0);

        assertEquals("PEER", res.subject());
        assertTrue(res.enabled());
        assertTrue(res.groups().isEmpty());
        assertEquals(0, res.summary().people());
    }

    @Test
    void anUnresolvablePeerIsUnresolvedAndNeverFallsBackToTheCallersOwnRecord() {
        when(likeService.resolvePeerUserId(ME, TOKEN, "JOINER", 9)).thenReturn(null);

        TimerShareLikeHistoryResponse res = service.history(ME, TOKEN, "JOINER", 9);

        assertEquals("UNRESOLVED", res.subject());
        assertTrue(res.enabled());
        assertTrue(res.groups().isEmpty());
        assertEquals(0, res.summary().received());
        // 授权边界：解析不出 ⇒ 一条点赞数据都不读（尤其不能退回读调用者自己的）
        verifyNoInteractions(shareRepository, joinRepository, likeRepository, userRepository);
    }

    @Test
    void anyOfTheThreeParametersAloneMeansAPeerWasRequested() {
        // 只带 relation（没有 token）：不是「要本人的」，是一个解析不出的对方请求
        TimerShareLikeHistoryResponse res = service.history(ME, null, "HOST", 0);

        assertEquals("UNRESOLVED", res.subject());
        verifyNoInteractions(likeService, likeRepository);
    }

    @Test
    void disabledSwitchEchoesTheRequestedSubject() {
        when(opsConfigService.isEnabled(OpsConfigService.KEY_TIMER_SHARE_LIKE_ENABLED, true)).thenReturn(false);

        assertEquals("SELF", service.history(ME, null, null, null).subject());
        assertEquals("PEER", service.history(ME, TOKEN, "HOST", 0).subject());
        verifyNoInteractions(likeService);
    }

    @Test
    void hittingTheRowCapMarksTruncated() {
        List<TimerShareLike> rows = new java.util.ArrayList<>();
        for (int i = 0; i < TimerSharePolicy.LIKE_HISTORY_MAX_ROWS; i++) {
            rows.add(like(1, A, ME, T0 + i));
        }
        when(likeRepository.findInvolving(anyCollection(), eq(ME), any(Pageable.class))).thenReturn(rows);
        when(userRepository.findByIdInAndDeletedFalse(anyCollection())).thenReturn(List.of(user(A, "A", null)));

        TimerShareLikeHistoryResponse res = service.history(ME, null, null, null);

        assertTrue(res.truncated());
        assertEquals(TimerSharePolicy.LIKE_HISTORY_MAX_ROWS, res.summary().received());
        assertEquals(TimerSharePolicy.LIKE_HISTORY_EVENTS_PER_GROUP, res.groups().get(0).events().size());
    }

    @Test
    void moreGroupsThanTheCapAreTruncatedButPeopleStaysExact() {
        List<TimerShareLike> rows = new java.util.ArrayList<>();
        int people = TimerSharePolicy.LIKE_HISTORY_MAX_GROUPS + 5;
        for (int i = 0; i < people; i++) {
            rows.add(like(1, 1000L + i, ME, T0 + i));
        }
        when(likeRepository.findInvolving(anyCollection(), eq(ME), any(Pageable.class))).thenReturn(rows);
        when(userRepository.findByIdInAndDeletedFalse(anyCollection())).thenReturn(List.of());

        TimerShareLikeHistoryResponse res = service.history(ME, null, null, null);

        assertEquals(TimerSharePolicy.LIKE_HISTORY_MAX_GROUPS, res.groups().size());
        assertEquals(people, res.summary().people());
        assertTrue(res.truncated());
    }

    @Test
    void rateLimitSurfacesAsAnErrorNotAnEmptyRecord() {
        when(likeRepository.findInvolving(anyCollection(), eq(ME), any(Pageable.class))).thenReturn(List.of());
        for (int i = 0; i < TimerSharePolicy.LIKE_HISTORY_READ_RATE_LIMIT; i++) {
            service.history(ME, null, null, null);
        }

        assertThrows(BusinessException.class, () -> service.history(ME, null, null, null));
        // 窗口过后恢复
        now[0] += TimerSharePolicy.LIKE_HISTORY_READ_RATE_WINDOW_MS + 1;
        assertTrue(service.history(ME, null, null, null).enabled());
    }
}
