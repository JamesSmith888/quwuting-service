package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.auth.service.WechatService;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.timershare.dto.request.CreateTimerShareRequest;
import org.quwuting.quwutingservice.timershare.dto.request.CreateTimerShareRequest.RuleInput;
import org.quwuting.quwutingservice.timershare.dto.request.CreateTimerShareRequest.TierInput;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareCloseResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareJoinResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareStatusResponse;
import org.quwuting.quwutingservice.timershare.entity.TimerShare;
import org.quwuting.quwutingservice.timershare.enums.TimerShareJoinOutcome;
import org.quwuting.quwutingservice.timershare.enums.TimerShareStatus;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.wxacode.service.WxacodeImage;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 编排层：开关 / 校验 / 限流 / 重试 / 响应装配（V42；Mockito，不连库）。
 * <p>
 * 时钟注入（可变的 {@code now[0]}）：限流与过期都是时间的函数，用例里推进「时间」而不是睡眠。
 */
@ExtendWith(MockitoExtension.class)
class TimerShareServiceTest {

    private static final long HOST = 10L;
    private static final long GUEST = 20L;
    private static final long T0 = 1_780_000_000_000L;
    private static final String TOKEN = "AbC123xyz0";

    @Mock
    private TimerShareStore store;
    @Mock
    private WechatService wechatService;
    @Mock
    private VenueRepository venueRepository;

    private final long[] now = {T0};
    private TimerShareService service;
    private TimerShareQrService qrService;

    @BeforeEach
    void setUp() {
        qrService = new TimerShareQrService(wechatService, "wx-test-appid", "release");
        service = new TimerShareService(store, qrService, venueRepository, () -> now[0]);
    }

    private static CreateTimerShareRequest request(String sessionKey) {
        return new CreateTimerShareRequest(sessionKey, 600_000L, 540, true,
                new RuleInput(List.of(new TierInput(4.0, 20.0, null))), null, null);
    }

    private static TimerShare storedShare(String token) {
        TimerShare s = new TimerShare();
        s.setId(5L);
        s.setToken(token);
        s.setHostUserId(HOST);
        s.setSessionKey("1780000000000");
        s.setStatus(TimerShareStatus.ACTIVE);
        s.setStartServerMs(T0 - 600_000L);
        s.setExcludedSeconds(60);
        s.setPausedAtServerMs(null);
        s.setRuleJson("{\"tiers\":[{\"durationMinutes\":4.0,\"price\":20.0}]}");
        s.setSnapshotAtMs(T0);
        s.setExpiresAtMs(T0 + TimerSharePolicy.SNAPSHOT_TTL_MS);
        s.setMaxJoins(TimerSharePolicy.MAX_JOINS);
        s.setJoinCount(1);
        s.setRefreshCount(1);
        return s;
    }

    private void storeReturns(TimerShare share, boolean created) {
        when(store.createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(), anyString(), anyLong()))
                .thenReturn(new TimerShareStore.Upserted(share, created));
    }

    // ── 关闭（收口）永远可用 ──────────────────────────────────────────────────

    @Test
    void closeNeverFailsBecauseCleanupMustAlwaysSucceed() {
        when(store.close(TOKEN, HOST, T0)).thenReturn(true);
        TimerShareCloseResponse r = service.close(HOST, TOKEN);
        assertTrue(r.closed());
        verifyNoInteractions(wechatService);
    }

    // ── 创建 / 刷新：校验 ─────────────────────────────────────────────────

    @Test
    void malformedRequestsAreRejectedWith1041() {
        CreateTimerShareRequest base = request("1780000000000");
        RuleInput rule = base.rule();

        assertCode(1041, () -> service.createOrRefresh(HOST, null));
        assertCode(1041, () -> service.createOrRefresh(HOST, request(null)));
        assertCode(1041, () -> service.createOrRefresh(HOST, request("")));
        assertCode(1041, () -> service.createOrRefresh(HOST, request("has space")));
        assertCode(1041, () -> service.createOrRefresh(HOST, request("x".repeat(49))));
        assertCode(1041, () -> service.createOrRefresh(HOST,
                new CreateTimerShareRequest("k", null, 540, true, rule, null, null)));
        assertCode(1041, () -> service.createOrRefresh(HOST,
                new CreateTimerShareRequest("k", 600_000L, null, true, rule, null, null)));
        assertCode(1041, () -> service.createOrRefresh(HOST,
                new CreateTimerShareRequest("k", 600_000L, 540, null, rule, null, null)));
        assertCode(1041, () -> service.createOrRefresh(HOST,
                new CreateTimerShareRequest("k", 600_000L, 9999, true, rule, null, null)), "净时长比墙钟大得多");
        assertCode(1041, () -> service.createOrRefresh(HOST,
                new CreateTimerShareRequest("k", 600_000L, 540, true, null, null, null)));
        assertCode(1041, () -> service.createOrRefresh(HOST,
                new CreateTimerShareRequest("k", 600_000L, 540, true,
                        new RuleInput(List.of(new TierInput(4.0, 20.0, "weird"))), null, null)));
        verifyNoInteractions(store);
    }

    @Test
    void validSessionKeyCharactersIncludeColonForMemberSuffix() {
        storeReturns(storedShare(TOKEN), true);
        assertNotNull(service.createOrRefresh(HOST, request("1780000000000:m2")));
        assertNotNull(service.createOrRefresh(HOST, request("a_b-C:9")));
    }

    // ── 创建 / 刷新：主路径 ───────────────────────────────────────────────

    @Test
    void createMapsReadingToAnchorsAndReturnsTokenAndQrPath() {
        TimerShare share = storedShare(TOKEN);
        storeReturns(share, true);

        TimerShareResponse r = service.createOrRefresh(HOST, request("1780000000000"));

        assertEquals(TOKEN, r.token());
        assertEquals("/timer-shares/" + TOKEN + "/wxacode.jpg", r.qrPath());
        assertEquals(share.getExpiresAtMs(), r.expiresAtMs());
        assertEquals(T0, r.serverNowMs());
        assertEquals(1, r.joinCount());
        assertEquals(TimerSharePolicy.MAX_JOINS, r.maxJoins());

        ArgumentCaptor<TimerShareClock.Anchors> anchors = ArgumentCaptor.forClass(TimerShareClock.Anchors.class);
        ArgumentCaptor<String> ruleJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(store).createOrRefresh(eq(HOST), eq("1780000000000"), anchors.capture(), ruleJson.capture(),
                eq(null), eq(null), token.capture(), eq(T0));
        assertEquals(T0 - 600_000L, anchors.getValue().startServerMs(), "起点 = 收到时刻 − 墙钟时长");
        assertEquals(60, anchors.getValue().excludedSeconds(), "墙钟 600s − 净 540s");
        assertNull(anchors.getValue().pausedAtServerMs());
        assertEquals("{\"tiers\":[{\"durationMinutes\":4.0,\"price\":20.0}]}", ruleJson.getValue());
        assertTrue(TimerShareTokens.isWellFormed(token.getValue()), "交给 Store 的新 token 必须格式合法");
    }

    @Test
    void pausedReadingProducesAPausedAnchor() {
        storeReturns(storedShare(TOKEN), false);

        service.createOrRefresh(HOST, new CreateTimerShareRequest("k", 600_000L, 540, false,
                new RuleInput(List.of(new TierInput(4.0, 20.0, null))), null, null));

        ArgumentCaptor<TimerShareClock.Anchors> anchors = ArgumentCaptor.forClass(TimerShareClock.Anchors.class);
        verify(store).createOrRefresh(eq(HOST), eq("k"), anchors.capture(), anyString(), any(), any(),
                anyString(), anyLong());
        assertEquals(T0, anchors.getValue().pausedAtServerMs());
    }

    @Test
    void unknownVenueIsDroppedInsteadOfRejectingTheRequest() {
        storeReturns(storedShare(TOKEN), true);
        when(venueRepository.findByIdAndDeletedFalse(999L)).thenReturn(Optional.empty());

        service.createOrRefresh(HOST, new CreateTimerShareRequest("k", 600_000L, 540, true,
                new RuleInput(List.of(new TierInput(4.0, 20.0, null))), 999L, null));

        verify(store).createOrRefresh(eq(HOST), eq("k"), any(), anyString(), eq(null), any(), anyString(), anyLong());
    }

    @Test
    void existingVenueIsKeptByIdOnly() {
        storeReturns(storedShare(TOKEN), true);
        Venue venue = new Venue();
        venue.setId(42L);
        venue.setName("某舞厅");
        when(venueRepository.findByIdAndDeletedFalse(42L)).thenReturn(Optional.of(venue));

        service.createOrRefresh(HOST, new CreateTimerShareRequest("k", 600_000L, 540, true,
                new RuleInput(List.of(new TierInput(4.0, 20.0, null))), 42L, null));

        verify(store).createOrRefresh(eq(HOST), eq("k"), any(), anyString(), eq(42L), any(), anyString(), anyLong());
    }

    @Test
    void parentTokenLinksTheReferralChainOnlyWhenWellFormedAndKnown() {
        storeReturns(storedShare(TOKEN), true);
        when(store.findIdByToken("ParentTok1")).thenReturn(Optional.of(77L));

        service.createOrRefresh(HOST, new CreateTimerShareRequest("k", 600_000L, 540, true,
                new RuleInput(List.of(new TierInput(4.0, 20.0, null))), null, "ParentTok1"));
        verify(store).createOrRefresh(eq(HOST), eq("k"), any(), anyString(), any(), eq(77L), anyString(), anyLong());

        // 格式非法：忽略，且不查库
        service.createOrRefresh(HOST, new CreateTimerShareRequest("k2", 600_000L, 540, true,
                new RuleInput(List.of(new TierInput(4.0, 20.0, null))), null, "bad token!"));
        verify(store, never()).findIdByToken("bad token!");
    }

    // ── 重试与限流 ────────────────────────────────────────────────────────

    private static DataIntegrityViolationException duplicateKey() {
        return new DataIntegrityViolationException("dup", new SQLException("Duplicate entry", "23000", 1062));
    }

    @Test
    void uniqueConflictIsRetriedWithAFreshToken() {
        TimerShare winner = storedShare("Winner0001");
        when(store.createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(), anyString(), anyLong()))
                .thenThrow(duplicateKey())
                .thenReturn(new TimerShareStore.Upserted(winner, false));

        TimerShareResponse r = service.createOrRefresh(HOST, request("1780000000000"));

        assertEquals("Winner0001", r.token());
        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(store, times(2)).createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(),
                tokens.capture(), anyLong());
        assertFalse(tokens.getAllValues().get(0).equals(tokens.getAllValues().get(1)), "重试换新 token（防 token 撞号）");
    }

    @Test
    void persistentConflictPropagatesAfterTheRetryBudgetInsteadOfLoopingForever() {
        when(store.createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(), anyString(), anyLong()))
                .thenThrow(duplicateKey());

        assertThrows(DataIntegrityViolationException.class,
                () -> service.createOrRefresh(HOST, request("1780000000000")));
        verify(store, times(3)).createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(),
                anyString(), anyLong());
    }

    @Test
    void lockFailuresAreRetriedBecauseMysqlRequiresTheApplicationToRetryDeadlockVictims() {
        TimerShare winner = storedShare("Winner0002");
        when(store.createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(), anyString(), anyLong()))
                .thenThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"))
                .thenThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"))
                .thenReturn(new TimerShareStore.Upserted(winner, false));

        assertEquals("Winner0002", service.createOrRefresh(HOST, request("1780000000000")).token());
        verify(store, times(3)).createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(),
                anyString(), anyLong());
    }

    @Test
    void persistentLockFailurePropagates() {
        when(store.createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(), anyString(), anyLong()))
                .thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));

        assertThrows(CannotAcquireLockException.class,
                () -> service.createOrRefresh(HOST, request("1780000000000")));
        verify(store, times(3)).createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(),
                anyString(), anyLong());
    }

    @Test
    void nonUniqueIntegrityErrorsAreNotSwallowedAsRaces() {
        when(store.createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(), anyString(), anyLong()))
                .thenThrow(new DataIntegrityViolationException("null", new SQLException("Column cannot be null", "23000", 1048)));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.createOrRefresh(HOST, request("1780000000000")));
        verify(store, times(1)).createOrRefresh(eq(HOST), anyString(), any(), anyString(), any(), any(),
                anyString(), anyLong());
    }

    @Test
    void hostWriteRateLimitIs30PerTenMinutesAndRecoversAsTimePasses() {
        storeReturns(storedShare(TOKEN), false);
        for (int i = 0; i < TimerSharePolicy.WRITE_RATE_LIMIT; i++) {
            service.createOrRefresh(HOST, request("1780000000000"));
        }
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.createOrRefresh(HOST, request("1780000000000")));
        assertEquals(1006, e.getCode());

        now[0] = T0 + TimerSharePolicy.WRITE_RATE_WINDOW_MS; // 窗口滑过
        assertNotNull(service.createOrRefresh(HOST, request("1780000000000")));
    }

    @Test
    void rateLimitIsPerUser() {
        when(store.createOrRefresh(anyLong(), anyString(), any(), anyString(), any(), any(), anyString(), anyLong()))
                .thenReturn(new TimerShareStore.Upserted(storedShare(TOKEN), false));
        for (int i = 0; i < TimerSharePolicy.WRITE_RATE_LIMIT; i++) {
            service.createOrRefresh(HOST, request("k"));
        }
        assertNotNull(service.createOrRefresh(11L, request("k")), "另一个主持方不受影响");
    }

    // ── 加入 ──────────────────────────────────────────────────────────────

    @Test
    void malformedTokenOnJoinIsNotFoundWithoutTouchingTheStore() {
        TimerShareJoinResponse r = service.join(GUEST, "nope");
        assertEquals("NOT_FOUND", r.outcome());
        verifyNoInteractions(store);
    }

    @Test
    void joinedResponseCarriesSnapshotWithServerSideVenueNameAndRule() {
        TimerShare share = storedShare(TOKEN);
        share.setVenueId(42L);
        share.setPausedAtServerMs(T0 - 1_000L);
        // DB 工作耗时 25ms：store.join 返回时时钟已走到 T0 + 25，serverNowMs 必须取这个「DB 之后」的值
        when(store.join(TOKEN, GUEST, T0)).thenAnswer(inv -> {
            now[0] = T0 + 25;
            return new TimerShareStore.JoinResult(TimerShareJoinOutcome.JOINED, share, true);
        });
        Venue venue = new Venue();
        venue.setId(42L);
        venue.setName("某舞厅");
        when(venueRepository.findByIdAndDeletedFalse(42L)).thenReturn(Optional.of(venue));

        TimerShareJoinResponse r = service.join(GUEST, TOKEN);

        assertEquals("JOINED", r.outcome());
        assertEquals(T0 + 25, r.serverNowMs(), "serverNowMs 取在 DB 工作之后");
        TimerShareJoinResponse.Snapshot snap = r.snapshot();
        assertNotNull(snap);
        assertEquals(share.getStartServerMs(), snap.startServerMs());
        assertEquals(60, snap.excludedSeconds());
        assertEquals(T0 - 1_000L, snap.pausedAtServerMs());
        assertEquals(1, snap.rule().tiers().size());
        assertEquals(4.0, snap.rule().tiers().get(0).durationMinutes());
        assertEquals(20.0, snap.rule().tiers().get(0).price());
        assertEquals(42L, snap.venue().id());
        assertEquals("某舞厅", snap.venue().name(), "名称由服务端据 id 现取，不信任客户端");
    }

    @Test
    void alreadyJoinedAlsoReturnsTheSnapshotAndMissingVenueIsNull() {
        TimerShare share = storedShare(TOKEN);
        when(store.join(TOKEN, GUEST, T0))
                .thenReturn(new TimerShareStore.JoinResult(TimerShareJoinOutcome.ALREADY_JOINED, share, false));

        TimerShareJoinResponse r = service.join(GUEST, TOKEN);

        assertEquals("ALREADY_JOINED", r.outcome());
        assertNotNull(r.snapshot());
        assertNull(r.snapshot().venue());
        assertNull(r.snapshot().pausedAtServerMs());
        verifyNoInteractions(venueRepository);
    }

    @Test
    void expectedBusinessStatesPassThroughWithoutSnapshot() {
        for (TimerShareJoinOutcome outcome : List.of(TimerShareJoinOutcome.SELF, TimerShareJoinOutcome.EXPIRED,
                TimerShareJoinOutcome.CLOSED, TimerShareJoinOutcome.FULL, TimerShareJoinOutcome.NOT_FOUND)) {
            when(store.join(TOKEN, GUEST, T0)).thenReturn(new TimerShareStore.JoinResult(outcome, null, false));
            TimerShareJoinResponse r = service.join(GUEST, TOKEN);
            assertEquals(outcome.name(), r.outcome());
            assertNull(r.snapshot());
        }
    }

    @Test
    void corruptedStoredRuleDegradesToNotFoundNotA500() {
        TimerShare share = storedShare(TOKEN);
        share.setRuleJson("garbage");
        when(store.join(TOKEN, GUEST, T0))
                .thenReturn(new TimerShareStore.JoinResult(TimerShareJoinOutcome.JOINED, share, false));

        assertEquals("NOT_FOUND", service.join(GUEST, TOKEN).outcome());
    }

    @Test
    void joinAttemptsAreRateLimitedPerUserAndReportedAsAnOutcome() {
        when(store.join(anyString(), anyLong(), anyLong()))
                .thenReturn(new TimerShareStore.JoinResult(TimerShareJoinOutcome.EXPIRED, null, false));
        for (int i = 0; i < TimerSharePolicy.JOIN_RATE_LIMIT; i++) {
            assertEquals("EXPIRED", service.join(GUEST, TOKEN).outcome());
        }
        assertEquals("TOO_FREQUENT", service.join(GUEST, TOKEN).outcome());
        assertEquals("EXPIRED", service.join(21L, TOKEN).outcome(), "别的用户不受影响");
    }

    // ── 状态轮询 / 关闭 ───────────────────────────────────────────────────

    @Test
    void statusDerivesExpiredFromTheClockAndClosedFromTheRow() {
        TimerShare share = storedShare(TOKEN);
        when(store.findOwned(TOKEN, HOST)).thenReturn(Optional.of(share));

        TimerShareStatusResponse active = service.status(HOST, TOKEN);
        assertEquals("ACTIVE", active.status());
        assertEquals(1, active.joinCount());
        assertEquals(TimerSharePolicy.MAX_JOINS, active.maxJoins());
        assertEquals(T0, active.serverNowMs());

        now[0] = share.getExpiresAtMs() + 1;
        assertEquals("EXPIRED", service.status(HOST, TOKEN).status());

        share.setStatus(TimerShareStatus.CLOSED);
        assertEquals("CLOSED", service.status(HOST, TOKEN).status(), "关闭优先于过期");
    }

    @Test
    void statusOfSomeoneElsesOrUnknownOrMalformedTokenIs1043() {
        when(store.findOwned(TOKEN, HOST)).thenReturn(Optional.empty());
        assertCode(1043, () -> service.status(HOST, TOKEN));
        assertCode(1043, () -> service.status(HOST, "bad"));
    }

    @Test
    void closeOfMalformedTokenIsHarmlessFalse() {
        assertFalse(service.close(HOST, "bad").closed());
        verifyNoInteractions(store);
    }

    // ── 码图 ──────────────────────────────────────────────────────────────

    @Test
    void qrIsOnlyGeneratedForJoinableSharesAndCachedPerToken() {
        when(store.isJoinable(TOKEN, T0)).thenReturn(true);
        when(wechatService.getUnlimitedQrCode(eq("t=" + TOKEN), eq("pages/timer-join/timer-join"), eq("release")))
                .thenReturn(new byte[]{1, 2, 3});

        Optional<WxacodeImage> first = service.renderQr(TOKEN);
        Optional<WxacodeImage> second = service.renderQr(TOKEN);

        assertTrue(first.isPresent());
        assertEquals(3, first.get().bytes().length);
        assertEquals("wx-test-appid|release|pages/timer-join/timer-join|t=" + TOKEN, first.get().etag());
        assertTrue(second.isPresent());
        verify(wechatService, times(1)).getUnlimitedQrCode(anyString(), anyString(), anyString());
    }

    @Test
    void qrForAnUnjoinableOrMalformedTokenNeverCallsWechat() {
        when(store.isJoinable(TOKEN, T0)).thenReturn(false);
        assertTrue(service.renderQr(TOKEN).isEmpty());
        assertTrue(service.renderQr("bad token").isEmpty());
        verifyNoInteractions(wechatService);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static void assertCode(int code, org.junit.jupiter.api.function.Executable call) {
        assertCode(code, call, null);
    }

    private static void assertCode(int code, org.junit.jupiter.api.function.Executable call, String message) {
        BusinessException e = assertThrows(BusinessException.class, call, message);
        assertEquals(code, e.getCode(), message);
    }
}
