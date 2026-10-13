package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.timershare.enums.TimerShareLikeDirection;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeHistoryRules.GroupFact;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeHistoryRules.LikeFact;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点赞记录的分组（纯函数）：本人视角、方向、顺序、事件条数上限，以及「与调用者无关的行进不来」。
 */
class TimerShareLikeHistoryRulesTest {

    private static final long ME = 1L;
    private static final long A = 21L;
    private static final long B = 22L;

    private static LikeFact like(long liker, long liked, long atMs) {
        return new LikeFact(liker, liked, atMs);
    }

    @Test
    void groupsByCounterpartWithBothDirectionsCounted() {
        // 新→旧：A 赞我(300)、我赞 A(200)、B 赞我(100)
        List<GroupFact> groups = TimerShareLikeHistoryRules.group(ME,
                List.of(like(A, ME, 300), like(ME, A, 200), like(B, ME, 100)), 20);

        assertEquals(2, groups.size());
        GroupFact first = groups.get(0);
        assertEquals(A, first.peerUserId());
        assertEquals(1, first.received());
        assertEquals(1, first.given());
        assertEquals(300, first.lastAtMs());
        assertEquals(TimerShareLikeDirection.RECEIVED, first.events().get(0).direction());
        assertEquals(TimerShareLikeDirection.GIVEN, first.events().get(1).direction());
        assertEquals(B, groups.get(1).peerUserId());
        assertEquals(1, groups.get(1).received());
        assertEquals(0, groups.get(1).given());
    }

    @Test
    void groupsAreOrderedByMostRecentLikeAndTiesAreStable() {
        List<GroupFact> groups = TimerShareLikeHistoryRules.group(ME,
                List.of(like(B, ME, 500), like(A, ME, 500), like(ME, 99L, 900)), 20);

        assertEquals(List.of(99L, A, B), groups.stream().map(GroupFact::peerUserId).toList());
    }

    @Test
    void eventsPerGroupAreCappedButCountsStayExact() {
        List<LikeFact> facts = List.of(like(A, ME, 5), like(A, ME, 4), like(A, ME, 3), like(A, ME, 2), like(A, ME, 1));

        GroupFact group = TimerShareLikeHistoryRules.group(ME, facts, 2).get(0);

        assertEquals(2, group.events().size());
        assertEquals(5, group.received());
        assertEquals(5, group.lastAtMs());
    }

    @Test
    void rowsUnrelatedToTheCallerOrSelfLikesAreIgnored() {
        List<GroupFact> groups = TimerShareLikeHistoryRules.group(ME,
                List.of(like(A, B, 10), like(ME, ME, 20), like(A, ME, 30)), 20);

        assertEquals(1, groups.size());
        assertEquals(A, groups.get(0).peerUserId());
        assertEquals(1, groups.get(0).received());
    }

    @Test
    void totalsSumAcrossAllGroups() {
        List<GroupFact> groups = TimerShareLikeHistoryRules.group(ME,
                List.of(like(A, ME, 3), like(ME, A, 2), like(B, ME, 1)), 20);

        int[] totals = TimerShareLikeHistoryRules.totals(groups);

        assertEquals(2, totals[0]);
        assertEquals(1, totals[1]);
        assertEquals(2, totals[2]);
    }

    @Test
    void emptyInputIsEmptyNotNull() {
        assertTrue(TimerShareLikeHistoryRules.group(ME, List.of(), 20).isEmpty());
        assertEquals(0, TimerShareLikeHistoryRules.totals(List.of())[2]);
    }
}
