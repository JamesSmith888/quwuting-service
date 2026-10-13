package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.timershare.dto.request.SetTimerShareLikeRequest;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeSetResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikesResponse;
import org.quwuting.quwutingservice.timershare.entity.TimerShare;
import org.quwuting.quwutingservice.timershare.entity.TimerShareJoin;
import org.quwuting.quwutingservice.timershare.entity.TimerShareLike;
import org.quwuting.quwutingservice.timershare.repository.TimerShareJoinRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareLikeRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 点赞编排（V48；Mockito，不连库）：开关 → 速率 → 请求形状 → 成员 → 目标 → 窗口 → 写入，
 * 以及「被赞通知只在首次赞触发、同一场合并、不点名」。
 * <p>
 * 时钟注入（可变的 {@code now[0]}）：窗口与限流都是时间的函数，用例里推进「时间」而不是睡眠。
 */
@ExtendWith(MockitoExtension.class)
class TimerShareLikeServiceTest {

    private static final long HOST = 10L;
    private static final long A = 21L;
    private static final long B = 22L;
    private static final long STRANGER = 99L;
    private static final long SHARE_ID = 7L;
    private static final long T0 = 1_780_000_000_000L;
    private static final long DAY = TimerSharePolicy.LIKE_WINDOW_MS;
    private static final String TOKEN = "AbC123xyz0";

    @Mock
    private TimerShareRepository shareRepository;
    @Mock
    private TimerShareJoinRepository joinRepository;
    @Mock
    private TimerShareLikeRepository likeRepository;
    @Mock
    private MessageService messageService;
    @Mock
    private OpsConfigService opsConfigService;

    private final long[] now = {T0 + 60_000};
    private TimerShareLikeService service;
    private TimerShare share;

    @BeforeEach
    void setUp() {
        service = new TimerShareLikeService(shareRepository, joinRepository, likeRepository, messageService,
                opsConfigService, () -> now[0]);
        lenient().when(opsConfigService.isEnabled(OpsConfigService.KEY_TIMER_SHARE_LIKE_ENABLED, true)).thenReturn(true);

        share = new TimerShare();
        share.setId(SHARE_ID);
        share.setToken(TOKEN);
        share.setHostUserId(HOST);
        share.setSnapshotAtMs(T0);
        share.setCreatedAt(localOf(T0));
        lenient().when(shareRepository.findByTokenAndDeletedFalse(TOKEN)).thenReturn(Optional.of(share));
        lenient().when(joinRepository.findByShareIdAndDeletedFalseOrderByIdAsc(SHARE_ID))
                .thenReturn(List.of(join(A, T0 + 1_000), join(B, T0 + 2_000)));
    }

    private static LocalDateTime localOf(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    private static TimerShareJoin join(long userId, long joinedAtMs) {
        TimerShareJoin join = new TimerShareJoin();
        join.setShareId(SHARE_ID);
        join.setUserId(userId);
        join.setCreatedAt(localOf(joinedAtMs));
        return join;
    }

    private static TimerShareLike like(long liker, long liked) {
        TimerShareLike row = new TimerShareLike();
        row.setShareId(SHARE_ID);
        row.setLikerId(liker);
        row.setLikedUserId(liked);
        return row;
    }

    private static SetTimerShareLikeRequest req(String relation, Integer seq, Boolean liked) {
        return new SetTimerShareLikeRequest(relation, seq, liked);
    }

    // ── 读 ───────────────────────────────────────────────────────────────────

    @Test
    void hostReadsEveryJoinerWithBothDirectionsOfLikeState() {
        when(likeRepository.findByShareIdAndLikerIdAndDeletedFalse(SHARE_ID, HOST)).thenReturn(List.of(like(HOST, A)));
        when(likeRepository.findByShareIdAndLikedUserIdAndDeletedFalse(SHARE_ID, HOST)).thenReturn(List.of(like(B, HOST)));

        TimerShareLikesResponse res = service.likes(HOST, TOKEN);

        assertTrue(res.enabled());
        assertEquals(2, res.items().size());
        TimerShareLikesResponse.Item first = res.items().get(0);
        assertEquals("JOINER", first.relation());
        assertEquals(1, first.seq());
        assertTrue(first.likedByMe());
        assertFalse(first.likesMe());
        assertTrue(first.likeable());
        TimerShareLikesResponse.Item second = res.items().get(1);
        assertEquals(2, second.seq());
        assertFalse(second.likedByMe());
        assertTrue(second.likesMe());
    }

    @Test
    void joinerReadsOnlyTheHost() {
        when(likeRepository.findByShareIdAndLikerIdAndDeletedFalse(SHARE_ID, A)).thenReturn(List.of());
        when(likeRepository.findByShareIdAndLikedUserIdAndDeletedFalse(SHARE_ID, A)).thenReturn(List.of());

        TimerShareLikesResponse res = service.likes(A, TOKEN);

        assertEquals(1, res.items().size());
        assertEquals("HOST", res.items().get(0).relation());
        assertEquals(0, res.items().get(0).seq());
    }

    @Test
    void nonMemberAndMalformedTokenReadAsEmptyNotAsAnError() {
        assertTrue(service.likes(STRANGER, TOKEN).items().isEmpty());
        assertTrue(service.likes(A, "bad").items().isEmpty());
        verify(shareRepository, never()).findByTokenAndDeletedFalse("bad");
    }

    @Test
    void disabledSwitchHidesEverythingAndTouchesNoTable() {
        when(opsConfigService.isEnabled(OpsConfigService.KEY_TIMER_SHARE_LIKE_ENABLED, true)).thenReturn(false);

        TimerShareLikesResponse res = service.likes(HOST, TOKEN);

        assertFalse(res.enabled());
        assertTrue(res.items().isEmpty());
        verifyNoInteractions(shareRepository, joinRepository, likeRepository);
    }

    @Test
    void likeableTurnsFalseOncePastTheWindowButTheLikeStateIsStillReported() {
        when(likeRepository.findByShareIdAndLikerIdAndDeletedFalse(SHARE_ID, HOST)).thenReturn(List.of(like(HOST, A)));
        when(likeRepository.findByShareIdAndLikedUserIdAndDeletedFalse(SHARE_ID, HOST)).thenReturn(List.of());
        now[0] = T0 + 2_000 + DAY + 1;

        TimerShareLikesResponse.Item item = service.likes(HOST, TOKEN).items().get(0);

        assertFalse(item.likeable());
        assertTrue(item.likedByMe(), "窗口外仍要告诉客户端「已赞」——只是不再可改");
    }

    // ── 写 ───────────────────────────────────────────────────────────────────

    @Test
    void firstLikeWritesTheRowAndNotifiesTheRecipientOnce() {
        when(likeRepository.like(eq(SHARE_ID), eq(A), eq(HOST), eq("LIKE"), any())).thenReturn(1);
        when(likeRepository.countByShareIdAndLikedUserIdAndDeletedFalse(SHARE_ID, HOST)).thenReturn(1L);

        TimerShareLikeSetResponse res = service.set(A, TOKEN, req("HOST", 0, true));

        assertEquals("OK", res.outcome());
        assertTrue(res.liked());
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(messageService).createOrMergeUnread(eq(HOST), eq(MessageType.TIMER_SHARE_LIKED), eq("收到点赞"),
                eq("一同计时的舞伴给你点了个赞"), eq("TIMER_SHARE"), eq(SHARE_ID), since.capture());
        assertEquals(share.getCreatedAt(), since.getValue(), "合并窗口起点 = 会话创建：更早的未读属于别的会话");
    }

    @Test
    void notificationBodyCarriesTheRunningCountWhenSeveralJoinersLike() {
        when(likeRepository.like(eq(SHARE_ID), eq(B), eq(HOST), eq("LIKE"), any())).thenReturn(1);
        when(likeRepository.countByShareIdAndLikedUserIdAndDeletedFalse(SHARE_ID, HOST)).thenReturn(2L);

        service.set(B, TOKEN, req("host", null, true));

        verify(messageService).createOrMergeUnread(eq(HOST), eq(MessageType.TIMER_SHARE_LIKED), anyString(),
                eq("一同计时的舞伴共给你点了 2 个赞"), eq("TIMER_SHARE"), eq(SHARE_ID), any());
    }

    @Test
    void relikingAfterAnUndoDoesNotNotifyAgain() {
        // 受影响行数 2 = 取消后恢复 / 重复赞：义务已履行，不重发
        when(likeRepository.like(eq(SHARE_ID), eq(A), eq(HOST), eq("LIKE"), any())).thenReturn(2);

        TimerShareLikeSetResponse res = service.set(A, TOKEN, req("HOST", 0, true));

        assertEquals("OK", res.outcome());
        verifyNoInteractions(messageService);
    }

    @Test
    void undoSoftDeletesAndNeverNotifies() {
        TimerShareLikeSetResponse res = service.set(A, TOKEN, req("HOST", 0, false));

        assertEquals("OK", res.outcome());
        assertFalse(res.liked());
        verify(likeRepository).unlike(eq(SHARE_ID), eq(A), eq(HOST), any());
        verify(likeRepository, never()).like(anyLong(), anyLong(), anyLong(), anyString(), any());
        verifyNoInteractions(messageService);
    }

    @Test
    void hostLikesTheSecondJoinerBySeq() {
        when(likeRepository.like(eq(SHARE_ID), eq(HOST), eq(B), eq("LIKE"), any())).thenReturn(1);
        when(likeRepository.countByShareIdAndLikedUserIdAndDeletedFalse(SHARE_ID, B)).thenReturn(1L);

        assertEquals("OK", service.set(HOST, TOKEN, req("JOINER", 2, true)).outcome());

        verify(messageService).createOrMergeUnread(eq(B), eq(MessageType.TIMER_SHARE_LIKED), anyString(), anyString(),
                eq("TIMER_SHARE"), eq(SHARE_ID), any());
    }

    @Test
    void joinersCannotLikeEachOtherAndNobodyCanLikeThemselves() {
        assertEquals("INVALID_TARGET", service.set(A, TOKEN, req("JOINER", 2, true)).outcome());
        assertEquals("INVALID_TARGET", service.set(HOST, TOKEN, req("HOST", 0, true)).outcome());
        verify(likeRepository, never()).like(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void malformedRequestShapesAreInvalidTargetsNotErrors() {
        assertEquals("INVALID_TARGET", service.set(HOST, TOKEN, null).outcome());
        assertEquals("INVALID_TARGET", service.set(HOST, TOKEN, req("nonsense", 1, true)).outcome());
        assertEquals("INVALID_TARGET", service.set(HOST, TOKEN, req(null, 1, true)).outcome());
        assertEquals("INVALID_TARGET", service.set(HOST, TOKEN, req("JOINER", null, true)).outcome());
        assertEquals("INVALID_TARGET", service.set(HOST, TOKEN, req("JOINER", 0, true)).outcome());
        assertEquals("INVALID_TARGET", service.set(HOST, TOKEN, req("JOINER", 3, true)).outcome(), "第 3 位不存在");
        assertEquals("INVALID_TARGET", service.set(HOST, TOKEN, req("JOINER", 1, null)).outcome(), "没有默认赞");
        verifyNoInteractions(likeRepository, messageService);
    }

    @Test
    void nonMemberAndBadTokenAreIndistinguishable() {
        assertEquals("NOT_MEMBER", service.set(STRANGER, TOKEN, req("HOST", 0, true)).outcome());
        assertEquals("NOT_MEMBER", service.set(A, "bad", req("HOST", 0, true)).outcome());
        when(shareRepository.findByTokenAndDeletedFalse("Zz9Zz9Zz9Z")).thenReturn(Optional.empty());
        assertEquals("NOT_MEMBER", service.set(A, "Zz9Zz9Zz9Z", req("HOST", 0, true)).outcome());
        verifyNoInteractions(likeRepository, messageService);
    }

    @Test
    void windowFreezesBothLikingAndUndoing() {
        now[0] = T0 + 2_000 + DAY + 1;

        assertEquals("WINDOW_CLOSED", service.set(HOST, TOKEN, req("JOINER", 1, true)).outcome());
        assertEquals("WINDOW_CLOSED", service.set(HOST, TOKEN, req("JOINER", 1, false)).outcome(),
                "窗口外取消也冻结——不存在「赞得了但取消不了」");
        verifyNoInteractions(likeRepository, messageService);
    }

    @Test
    void aSettlementInsideTheWindowKeepsItOpenPastTwentyFourHoursFromTheJoin() {
        TimerShareJoin settled = join(A, T0 + 1_000);
        settled.setSettledAtMs(T0 + DAY);
        when(joinRepository.findByShareIdAndDeletedFalseOrderByIdAsc(SHARE_ID)).thenReturn(List.of(settled));
        now[0] = T0 + DAY + 3_600_000;
        when(likeRepository.like(eq(SHARE_ID), eq(HOST), eq(A), eq("LIKE"), any())).thenReturn(1);
        when(likeRepository.countByShareIdAndLikedUserIdAndDeletedFalse(SHARE_ID, A)).thenReturn(1L);

        assertEquals("OK", service.set(HOST, TOKEN, req("JOINER", 1, true)).outcome());
    }

    @Test
    void disabledSwitchRejectsWritesBeforeAnyLookup() {
        when(opsConfigService.isEnabled(OpsConfigService.KEY_TIMER_SHARE_LIKE_ENABLED, true)).thenReturn(false);

        assertEquals("DISABLED", service.set(A, TOKEN, req("HOST", 0, true)).outcome());
        assertEquals("DISABLED", service.set(A, TOKEN, req("HOST", 0, false)).outcome(), "关闭 = 读写入口都停");
        verifyNoInteractions(shareRepository, joinRepository, likeRepository, messageService);
    }

    @Test
    void writeRateIsGuardedPerUserAndIndependentOfOtherUsers() {
        for (int i = 0; i < TimerSharePolicy.LIKE_WRITE_RATE_LIMIT; i++) {
            assertEquals("OK", service.set(A, TOKEN, req("HOST", 0, false)).outcome());
        }
        assertEquals("TOO_FREQUENT", service.set(A, TOKEN, req("HOST", 0, false)).outcome());
        assertEquals("OK", service.set(B, TOKEN, req("HOST", 0, false)).outcome(), "限流按用户隔离");

        // 窗口滑过之后恢复
        now[0] += TimerSharePolicy.LIKE_WRITE_RATE_WINDOW_MS + 1;
        assertEquals("OK", service.set(A, TOKEN, req("HOST", 0, false)).outcome());
        verify(messageService, times(0)).createOrMergeUnread(anyLong(), any(), anyString(), anyString(), anyString(),
                anyLong(), any());
    }

    @Test
    void notificationCopyAvoidsEvaluativeAndGiftWording() {
        // 29 号驳回史：评价 / 好评 / 打赏 / 送礼 / 心动一类字眼不得出现（54 号 §六）
        for (long n : new long[]{1, 2, 5}) {
            String body = TimerShareLikeService.likedContent(n) + TimerShareLikeService.LIKED_TITLE;
            for (String banned : List.of("评价", "好评", "打赏", "送礼", "礼物", "心动", "喜欢你", "陌生人", "交友", "匹配")) {
                assertFalse(body.contains(banned), "被赞通知文案含红线词：" + banned);
            }
        }
    }

    // ── 点赞记录的焦点解析（2026-10-13）──────────────────────────────────────

    @Test
    void resolvePeerUserIdMapsRoleAndSeqToTheOtherUserWithoutCheckingTheWindow() {
        // 主持方看第 2 位 = B；加入者看主持方 = HOST。窗口外也解析（记录是历史，窗口只管能不能赞）
        now[0] = T0 + 10 * DAY;
        assertEquals(B, service.resolvePeerUserId(HOST, TOKEN, "JOINER", 2));
        assertEquals(HOST, service.resolvePeerUserId(A, TOKEN, "HOST", 0));
        assertEquals(HOST, service.resolvePeerUserId(A, TOKEN, "HOST", null), "主持方目标不需要 seq");
    }

    @Test
    void resolvePeerUserIdReturnsNullForEveryUnresolvableRequest() {
        assertEquals(null, service.resolvePeerUserId(STRANGER, TOKEN, "HOST", 0), "非成员");
        assertEquals(null, service.resolvePeerUserId(HOST, TOKEN, "JOINER", 9), "序号越界");
        assertEquals(null, service.resolvePeerUserId(HOST, TOKEN, "JOINER", null), "加入者目标必须带 seq");
        assertEquals(null, service.resolvePeerUserId(HOST, TOKEN, "NOPE", 1), "协议值非法");
        assertEquals(null, service.resolvePeerUserId(A, TOKEN, "JOINER", 2), "加入者之间不开放");
        assertEquals(null, service.resolvePeerUserId(HOST, "bad token", "JOINER", 1), "token 非法");
    }
}
