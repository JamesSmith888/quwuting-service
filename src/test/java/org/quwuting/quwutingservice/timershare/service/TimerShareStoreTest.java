package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.timershare.entity.TimerShare;
import org.quwuting.quwutingservice.timershare.entity.TimerShareJoin;
import org.quwuting.quwutingservice.timershare.enums.TimerShareJoinOutcome;
import org.quwuting.quwutingservice.timershare.enums.TimerShareStatus;
import org.quwuting.quwutingservice.timershare.repository.TimerShareJoinRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareRepository;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分享会话的事务性逻辑（V42；Mockito，不连库）。
 * <p>
 * 用例按「决策顺序」组织：Store 的 join 是一条有优先级的判定链（不存在 → 自己 → 已关闭 → 已过期 →
 * 已加入 → 已满 → 规则损坏 → 新加入），每一环都有「此环命中、后面环不该被触达」的断言。
 * 并发正确性由 SELECT ... FOR UPDATE 保证，无法在单测里复现——此处锁定的是「持锁后的串行逻辑是对的」，
 * 真库行为见 54 号文档「验证」节。
 */
@ExtendWith(MockitoExtension.class)
class TimerShareStoreTest {

    private static final long HOST = 10L;
    private static final long GUEST = 20L;
    private static final long NOW = 1_780_000_000_000L;
    private static final String TOKEN = "AbC123xyz0";
    private static final String RULE_JSON = "{\"tiers\":[{\"durationMinutes\":4.0,\"price\":20.0}]}";

    @Mock
    private TimerShareRepository shareRepository;
    @Mock
    private TimerShareJoinRepository joinRepository;
    @Mock
    private UserRepository userRepository;

    private TimerShareStore store;

    @BeforeEach
    void setUp() {
        store = new TimerShareStore(shareRepository, joinRepository, userRepository);
    }

    /** 一张可加入的会话：主持方已走 10 分钟（无暂停），NOW 时刻创建，10 分钟后失效 */
    private TimerShare activeShare() {
        TimerShare s = new TimerShare();
        s.setId(1L);
        s.setToken(TOKEN);
        s.setHostUserId(HOST);
        s.setSessionKey("1780000000000");
        s.setStatus(TimerShareStatus.ACTIVE);
        s.setStartServerMs(NOW - 600_000L);
        s.setExcludedSeconds(0);
        s.setPausedAtServerMs(null);
        s.setRuleJson(RULE_JSON);
        s.setSnapshotAtMs(NOW);
        s.setExpiresAtMs(NOW + TimerSharePolicy.SNAPSHOT_TTL_MS);
        s.setMaxJoins(TimerSharePolicy.MAX_JOINS);
        s.setJoinCount(0);
        s.setRefreshCount(1);
        return s;
    }

    private User userCreatedAtMsAgo(long ms) {
        User u = new User();
        u.setCreatedAt(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(NOW - ms), ZoneId.systemDefault()));
        return u;
    }

    // ── join 判定链 ─────────────────────────────────────────────────────────

    @Test
    void joinUnknownTokenIsNotFound() {
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.empty());
        TimerShareStore.JoinResult r = store.join(TOKEN, GUEST, NOW);
        assertEquals(TimerShareJoinOutcome.NOT_FOUND, r.outcome());
        assertNull(r.share());
    }

    @Test
    void hostScanningOwnCodeIsSelfAndNothingIsWritten() {
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(activeShare()));
        assertEquals(TimerShareJoinOutcome.SELF, store.join(TOKEN, HOST, NOW).outcome());
        verify(joinRepository, never()).save(any());
    }

    @Test
    void closedShareRejectsEvenForSomeoneWhoWouldOtherwiseBeNew() {
        TimerShare s = activeShare();
        s.setStatus(TimerShareStatus.CLOSED);
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        assertEquals(TimerShareJoinOutcome.CLOSED, store.join(TOKEN, GUEST, NOW).outcome());
        verify(joinRepository, never()).save(any());
    }

    @Test
    void expiredShareIsRejected() {
        TimerShare s = activeShare();
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        assertEquals(TimerShareJoinOutcome.EXPIRED, store.join(TOKEN, GUEST, s.getExpiresAtMs() + 1).outcome());
        verify(joinRepository, never()).save(any());
    }

    @Test
    void theExactExpiryInstantIsStillJoinable() {
        TimerShare s = activeShare();
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        assertEquals(TimerShareJoinOutcome.JOINED, store.join(TOKEN, GUEST, s.getExpiresAtMs()).outcome(),
                "过期判定是严格大于：expires_at_ms 当刻仍有效（与 isJoinable 同口径）");
    }

    @Test
    void repeatScanIsIdempotentAndIgnoresCapacity() {
        TimerShare s = activeShare();
        s.setJoinCount(s.getMaxJoins()); // 名额已满，但这位早就在名单里
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        when(joinRepository.findByShareIdAndUserIdAndDeletedFalse(1L, GUEST))
                .thenReturn(Optional.of(new TimerShareJoin()));

        TimerShareStore.JoinResult r = store.join(TOKEN, GUEST, NOW);

        assertEquals(TimerShareJoinOutcome.ALREADY_JOINED, r.outcome());
        assertSame(s, r.share(), "重复扫码仍要带回会话，供编排层再给一份快照");
        verify(joinRepository, never()).save(any());
        verify(shareRepository, never()).save(any());
        assertEquals(TimerSharePolicy.MAX_JOINS, s.getJoinCount(), "重复扫码不再计数");
    }

    @Test
    void fullShareRejectsNewcomers() {
        TimerShare s = activeShare();
        s.setJoinCount(s.getMaxJoins());
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        when(joinRepository.findByShareIdAndUserIdAndDeletedFalse(1L, GUEST)).thenReturn(Optional.empty());

        assertEquals(TimerShareJoinOutcome.FULL, store.join(TOKEN, GUEST, NOW).outcome());
        verify(joinRepository, never()).save(any());
    }

    @Test
    void corruptedRuleIsRejectedBeforeConsumingASlot() {
        TimerShare s = activeShare();
        s.setRuleJson("garbage");
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        when(joinRepository.findByShareIdAndUserIdAndDeletedFalse(1L, GUEST)).thenReturn(Optional.empty());

        assertEquals(TimerShareJoinOutcome.NOT_FOUND, store.join(TOKEN, GUEST, NOW).outcome());
        verify(joinRepository, never()).save(any());
        assertEquals(0, s.getJoinCount(), "坏数据不白耗名额");
    }

    @Test
    void newcomerIsRecordedAndCounted() {
        TimerShare s = activeShare();
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        when(joinRepository.findByShareIdAndUserIdAndDeletedFalse(1L, GUEST)).thenReturn(Optional.empty());
        when(userRepository.findById(GUEST)).thenReturn(Optional.of(userCreatedAtMsAgo(60_000L)));

        TimerShareStore.JoinResult r = store.join(TOKEN, GUEST, NOW);

        assertEquals(TimerShareJoinOutcome.JOINED, r.outcome());
        assertTrue(r.newUser(), "账号 1 分钟前才建 ⇒ 新用户");
        assertEquals(1, s.getJoinCount());

        ArgumentCaptor<TimerShareJoin> saved = ArgumentCaptor.forClass(TimerShareJoin.class);
        verify(joinRepository).save(saved.capture());
        assertEquals(1L, saved.getValue().getShareId());
        assertEquals(GUEST, saved.getValue().getUserId());
        assertTrue(saved.getValue().getNewUser());
        assertEquals(600, saved.getValue().getNetSecondsAtJoin(), "主持方已走 10 分钟 ⇒ 加入时净时长 600 秒");
        verify(shareRepository).save(s);
    }

    @Test
    void oldAccountsAreNotCountedAsNewUsers() {
        TimerShare s = activeShare();
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        when(joinRepository.findByShareIdAndUserIdAndDeletedFalse(1L, GUEST)).thenReturn(Optional.empty());
        when(userRepository.findById(GUEST))
                .thenReturn(Optional.of(userCreatedAtMsAgo(TimerSharePolicy.NEW_USER_WINDOW_MS + 1)));

        assertFalse(store.join(TOKEN, GUEST, NOW).newUser());
    }

    @Test
    void newUserWindowBoundaryIsInclusiveAndUnknownUserIsNotNew() {
        TimerShare s = activeShare();
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        when(joinRepository.findByShareIdAndUserIdAndDeletedFalse(1L, GUEST)).thenReturn(Optional.empty());
        when(userRepository.findById(GUEST))
                .thenReturn(Optional.of(userCreatedAtMsAgo(TimerSharePolicy.NEW_USER_WINDOW_MS)));
        assertTrue(store.join(TOKEN, GUEST, NOW).newUser(), "恰好 15 分钟仍算新用户");

        s.setJoinCount(0);
        when(userRepository.findById(21L)).thenReturn(Optional.empty());
        when(joinRepository.findByShareIdAndUserIdAndDeletedFalse(1L, 21L)).thenReturn(Optional.empty());
        assertFalse(store.join(TOKEN, 21L, NOW).newUser(), "查不到用户 ⇒ 保守判非新用户，不因此拒绝加入");
    }

    @Test
    void pausedHostSnapshotRecordsFrozenNetSecondsAtJoin() {
        TimerShare s = activeShare();
        s.setExcludedSeconds(120);
        s.setPausedAtServerMs(NOW); // 主持方创建时处于暂停：净时长 = 600 − 120 = 480
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        when(joinRepository.findByShareIdAndUserIdAndDeletedFalse(1L, GUEST)).thenReturn(Optional.empty());
        when(userRepository.findById(GUEST)).thenReturn(Optional.empty());

        store.join(TOKEN, GUEST, NOW + 300_000L); // 5 分钟后才扫

        ArgumentCaptor<TimerShareJoin> saved = ArgumentCaptor.forClass(TimerShareJoin.class);
        verify(joinRepository).save(saved.capture());
        assertEquals(480, saved.getValue().getNetSecondsAtJoin(), "暂停态冻结，不随扫码延迟增长");
    }

    // ── close ─────────────────────────────────────────────────────────────

    @Test
    void hostCanCloseAnActiveShareOnce() {
        TimerShare s = activeShare();
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));

        assertTrue(store.close(TOKEN, HOST, NOW));
        assertEquals(TimerShareStatus.CLOSED, s.getStatus());
        assertEquals(NOW, s.getClosedAtMs());
        verify(shareRepository).save(s);

        assertFalse(store.close(TOKEN, HOST, NOW + 1), "幂等：第二次关闭 ⇒ false，不改 closedAtMs");
        assertEquals(NOW, s.getClosedAtMs());
    }

    @Test
    void closeByNonHostOrUnknownTokenChangesNothing() {
        TimerShare s = activeShare();
        when(shareRepository.findByTokenForUpdate(TOKEN)).thenReturn(Optional.of(s));
        assertFalse(store.close(TOKEN, GUEST, NOW));
        assertEquals(TimerShareStatus.ACTIVE, s.getStatus());

        when(shareRepository.findByTokenForUpdate("ZzZzZzZzZz")).thenReturn(Optional.empty());
        assertFalse(store.close("ZzZzZzZzZz", HOST, NOW));
        verify(shareRepository, never()).save(any());
    }

    // ── createOrRefresh ───────────────────────────────────────────────────

    private static final TimerShareClock.Anchors ANCHORS = new TimerShareClock.Anchors(NOW - 600_000L, 60, null);

    @Test
    void refreshKeepsTokenReactivatesAndBumpsCounters() {
        TimerShare s = activeShare();
        s.setStatus(TimerShareStatus.CLOSED);
        s.setClosedAtMs(NOW - 1);
        s.setJoinCount(2);
        when(shareRepository.findByHostAndSessionKeyForUpdate(HOST, "1780000000000")).thenReturn(Optional.of(s));
        when(shareRepository.save(s)).thenReturn(s);

        TimerShareStore.Upserted u = store.createOrRefresh(HOST, "1780000000000", ANCHORS, RULE_JSON, 7L, 99L,
                "NEWTOKEN12", NOW + 5_000L);

        assertFalse(u.created());
        assertEquals(TOKEN, u.share().getToken(), "刷新不换 token ⇒ 码图不变");
        assertEquals(TimerShareStatus.ACTIVE, s.getStatus(), "单独结算后撤销 ⇒ 重新激活");
        assertNull(s.getClosedAtMs());
        assertEquals(2, s.getRefreshCount());
        assertEquals(2, s.getJoinCount(), "已加入的人不受刷新影响");
        assertEquals(NOW + 5_000L, s.getSnapshotAtMs());
        assertEquals(NOW + 5_000L + TimerSharePolicy.SNAPSHOT_TTL_MS, s.getExpiresAtMs(), "有效期从刷新时刻重新计");
        assertEquals(ANCHORS.startServerMs(), s.getStartServerMs());
        assertEquals(60, s.getExcludedSeconds());
        assertEquals(7L, s.getVenueId());
        assertNull(s.getParentShareId(), "传播链只在创建时确立，刷新不改写（即便这次带了 99）");
        verify(shareRepository, never()).countNewSince(anyLong(), any());
    }

    @Test
    void firstCreateBuildsAFreshShareWithTheGivenToken() {
        when(shareRepository.findByHostAndSessionKeyForUpdate(HOST, "k1")).thenReturn(Optional.empty());
        when(shareRepository.countNewSince(anyLong(), any())).thenReturn(0L);
        when(shareRepository.saveAndFlush(any(TimerShare.class))).thenAnswer(inv -> inv.getArgument(0));

        TimerShareStore.Upserted u = store.createOrRefresh(HOST, "k1", ANCHORS, RULE_JSON, null, 99L,
                "NEWTOKEN12", NOW);

        assertTrue(u.created());
        TimerShare s = u.share();
        assertEquals("NEWTOKEN12", s.getToken());
        assertEquals(HOST, s.getHostUserId());
        assertEquals("k1", s.getSessionKey());
        assertEquals(99L, s.getParentShareId());
        assertEquals(TimerShareStatus.ACTIVE, s.getStatus());
        assertEquals(TimerSharePolicy.MAX_JOINS, s.getMaxJoins());
        assertEquals(0, s.getJoinCount());
        assertEquals(1, s.getRefreshCount());
        assertEquals(NOW + TimerSharePolicy.SNAPSHOT_TTL_MS, s.getExpiresAtMs());
        assertNull(s.getVenueId());
        assertNotNull(s.getRuleJson());
    }

    @Test
    void dailyNewShareCapBlocksCreationButNeverRefresh() {
        when(shareRepository.findByHostAndSessionKeyForUpdate(HOST, "k1")).thenReturn(Optional.empty());
        when(shareRepository.countNewSince(anyLong(), any())).thenReturn((long) TimerSharePolicy.MAX_NEW_SHARES_PER_DAY);

        BusinessException e = assertThrows(BusinessException.class,
                () -> store.createOrRefresh(HOST, "k1", ANCHORS, RULE_JSON, null, null, "NEWTOKEN12", NOW));
        assertEquals(1006, e.getCode());
        verify(shareRepository, never()).saveAndFlush(any());
    }

    // ── 只读查询 ──────────────────────────────────────────────────────────

    @Test
    void findOwnedHidesOtherUsersShares() {
        when(shareRepository.findByTokenAndDeletedFalse(TOKEN)).thenReturn(Optional.of(activeShare()));
        assertTrue(store.findOwned(TOKEN, HOST).isPresent());
        assertFalse(store.findOwned(TOKEN, GUEST).isPresent(), "不是自己的 ⇒ 当作不存在");
    }

    @Test
    void isJoinableRequiresActiveAndUnexpired() {
        TimerShare s = activeShare();
        when(shareRepository.findByTokenAndDeletedFalse(TOKEN)).thenReturn(Optional.of(s));
        assertTrue(store.isJoinable(TOKEN, NOW));
        assertTrue(store.isJoinable(TOKEN, s.getExpiresAtMs()), "恰好到期那一刻仍可用");
        assertFalse(store.isJoinable(TOKEN, s.getExpiresAtMs() + 1));
        s.setStatus(TimerShareStatus.CLOSED);
        assertFalse(store.isJoinable(TOKEN, NOW));
    }
}
