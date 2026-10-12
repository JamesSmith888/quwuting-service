package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.timershare.enums.TimerShareRole;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeRules.JoinFact;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeRules.Pair;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeRules.ShareFact;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点赞的准入与窗口判定（V48；纯函数，零 Spring / DB）。
 * <p>
 * 这里钉的是「谁能赞谁」的<b>形状</b>：主持方 ↔ 加入者互赞、加入者之间不开放、非成员不存在——
 * 以及读状态与写校验共用同一个入口（{@code pairsOf}）。
 */
class TimerShareLikeRulesTest {

    private static final long HOST = 10L;
    private static final long A = 21L;
    private static final long B = 22L;
    private static final long STRANGER = 99L;
    private static final long T0 = 1_780_000_000_000L;
    private static final long DAY = TimerSharePolicy.LIKE_WINDOW_MS;

    private static final ShareFact SHARE = new ShareFact(HOST, T0, null);
    private static final List<JoinFact> JOINS = List.of(
            new JoinFact(A, T0 + 1_000, null),
            new JoinFact(B, T0 + 2_000, null));

    @Test
    void hostSeesEveryJoinerInJoinOrderWithOneBasedSeq() {
        List<Pair> pairs = TimerShareLikeRules.pairsOf(HOST, SHARE, JOINS);

        assertNotNull(pairs);
        assertEquals(2, pairs.size());
        assertEquals(TimerShareRole.JOINER, pairs.get(0).role());
        assertEquals(1, pairs.get(0).seq());
        assertEquals(A, pairs.get(0).otherUserId());
        assertEquals(2, pairs.get(1).seq());
        assertEquals(B, pairs.get(1).otherUserId());
    }

    @Test
    void joinerSeesOnlyTheHostAndNeverOtherJoiners() {
        List<Pair> pairs = TimerShareLikeRules.pairsOf(A, SHARE, JOINS);

        assertNotNull(pairs);
        assertEquals(1, pairs.size());
        assertEquals(TimerShareRole.HOST, pairs.get(0).role());
        assertEquals(0, pairs.get(0).seq(), "对方是主持方时 seq 恒为 0");
        assertEquals(HOST, pairs.get(0).otherUserId());
        // 加入者之间不开放：B 不在 A 的可赞对象里
        assertNull(TimerShareLikeRules.find(pairs, TimerShareRole.JOINER, 2));
    }

    @Test
    void nonMemberGetsNullNotAnEmptyList() {
        assertNull(TimerShareLikeRules.pairsOf(STRANGER, SHARE, JOINS));
    }

    @Test
    void hostWithNobodyJoinedIsAMemberWithAnEmptyList() {
        List<Pair> pairs = TimerShareLikeRules.pairsOf(HOST, SHARE, List.of());

        assertNotNull(pairs, "空列表（成员但没有可赞对象）与 null（非成员）必须可区分");
        assertTrue(pairs.isEmpty());
    }

    @Test
    void findRequiresBothRoleAndSeqToMatch() {
        List<Pair> pairs = TimerShareLikeRules.pairsOf(HOST, SHARE, JOINS);

        assertEquals(B, TimerShareLikeRules.find(pairs, TimerShareRole.JOINER, 2).otherUserId());
        assertNull(TimerShareLikeRules.find(pairs, TimerShareRole.JOINER, 3), "第 3 位不存在");
        assertNull(TimerShareLikeRules.find(pairs, TimerShareRole.HOST, 0), "主持方不能赞自己");
    }

    @Test
    void anchorIsTheLatestOfSnapshotJoinAndBothSettlements() {
        // 只有快照与加入：取较大者（加入晚于快照）
        assertEquals(T0 + 1_000, TimerShareLikeRules.anchorMs(SHARE, JOINS.get(0)));
        // 主持方结算更晚
        ShareFact hostSettled = new ShareFact(HOST, T0, T0 + 50_000);
        assertEquals(T0 + 50_000, TimerShareLikeRules.anchorMs(hostSettled, JOINS.get(0)));
        // 加入者结算最晚
        JoinFact joinerSettled = new JoinFact(A, T0 + 1_000, T0 + 90_000);
        assertEquals(T0 + 90_000, TimerShareLikeRules.anchorMs(hostSettled, joinerSettled));
        // 会话被刷新（snapshot 更新）也算活动
        ShareFact refreshed = new ShareFact(HOST, T0 + 120_000, null);
        assertEquals(T0 + 120_000, TimerShareLikeRules.anchorMs(refreshed, JOINS.get(0)));
    }

    @Test
    void windowIsInclusiveAtTheBoundaryAndClosedAfterIt() {
        Pair pair = TimerShareLikeRules.pairsOf(HOST, SHARE, JOINS).get(0);
        long anchor = pair.anchorMs();

        assertTrue(TimerShareLikeRules.withinWindow(pair, anchor));
        assertTrue(TimerShareLikeRules.withinWindow(pair, anchor + DAY), "含边界");
        assertFalse(TimerShareLikeRules.withinWindow(pair, anchor + DAY + 1));
    }

    @Test
    void aLaterSettlementReopensTheWindow() {
        // 同一对：原本过了 24h，但对方 25h 后才结算 ⇒ 窗口从结算时刻重新起算
        ShareFact share = new ShareFact(HOST, T0, null);
        JoinFact settledLate = new JoinFact(A, T0 + 1_000, T0 + DAY + 3_600_000);
        Pair pair = TimerShareLikeRules.pairsOf(HOST, share, List.of(settledLate)).get(0);

        assertTrue(TimerShareLikeRules.withinWindow(pair, T0 + DAY + 3_600_000 + 1_000));
    }
}
