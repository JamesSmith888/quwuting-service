package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分享快照的时间算术（V42；纯函数，无 Spring / DB）。
 * <p>
 * 这是整个功能里<b>唯一会静默算错钱</b>的地方：锚点推导错一秒，接收方屏幕上的时长就与主持方不一致，
 * 而两边后续都按各自的时长算金额。所以每条用例都用「主持方屏幕读数」做基准——
 * 不验证实现细节，验证「还原出来的净秒数 == 主持方当时屏幕上的净秒数」这个产品承诺。
 */
class TimerShareClockTest {

    /** 任意固定的服务端时刻（epoch ms） */
    private static final long RECV = 1_780_000_000_000L;

    @Test
    void runningReadingRoundTripsToTheSameNetSecondsAtReceiveTime() {
        // 主持方已走 25 分 30.4 秒墙钟，其中休息了 5 分钟 ⇒ 屏幕净时长 20:30
        long wallMs = (25 * 60 + 30) * 1000L + 400;
        int net = 20 * 60 + 30;

        TimerShareClock.Anchors a = TimerShareClock.anchor(RECV, wallMs, net, true);

        assertEquals(RECV - wallMs, a.startServerMs(), "起点 = 收到时刻 − 墙钟时长");
        assertEquals(5 * 60, a.excludedSeconds(), "排除秒数 = floor(墙钟秒) − 净秒数");
        assertNull(a.pausedAtServerMs());
        assertFalse(a.paused());
        assertEquals(net, TimerShareClock.netSecondsAt(a, RECV), "收到那一刻还原的净时长必须等于主持方屏幕读数");
    }

    @Test
    void runningSnapshotAdvancesWithServerTime() {
        TimerShareClock.Anchors a = TimerShareClock.anchor(RECV, 600_000L, 540, true);
        // 10 分钟墙钟、净 9 分钟；再过 90 秒接收方加入 ⇒ 净 10:30
        assertEquals(540 + 90, TimerShareClock.netSecondsAt(a, RECV + 90_000L));
    }

    @Test
    void pausedSnapshotFreezesAtTheReadingNoMatterWhenReceiverJoins() {
        // 主持方暂停中：净时长冻结在 12:00
        TimerShareClock.Anchors a = TimerShareClock.anchor(RECV, 900_000L, 720, false);

        assertTrue(a.paused());
        assertEquals(RECV, a.pausedAtServerMs(), "暂停锚点 = 创建（收到）时刻");
        assertEquals(720, TimerShareClock.netSecondsAt(a, RECV));
        assertEquals(720, TimerShareClock.netSecondsAt(a, RECV + 3_600_000L), "一小时后才扫，仍冻结在 12:00");
    }

    @Test
    void justStartedTimerHasZeroEverywhere() {
        TimerShareClock.Anchors a = TimerShareClock.anchor(RECV, 0L, 0, true);
        assertEquals(RECV, a.startServerMs());
        assertEquals(0, a.excludedSeconds());
        assertEquals(0, TimerShareClock.netSecondsAt(a, RECV));
    }

    @Test
    void netSecondsNeverGoesNegativeEvenIfQueriedBeforeTheStart() {
        TimerShareClock.Anchors a = TimerShareClock.anchor(RECV, 10_000L, 10, true);
        assertEquals(0, TimerShareClock.netSecondsAt(a, RECV - 60_000L));
    }

    @Test
    void netSlightlyAboveWallSecondsWithinJitterSlackIsClampedNotRejected() {
        // 两个读数来自同一时刻的 floor，理论上 net ≤ wallSeconds；留 1 秒抖动余量，钳回而不是拒绝
        TimerShareClock.Anchors a = TimerShareClock.anchor(RECV, 60_500L, 61, true);
        assertEquals(0, a.excludedSeconds());
        assertEquals(60, TimerShareClock.netSecondsAt(a, RECV), "钳回墙钟秒数 60，而不是 61");
    }

    @Test
    void rejectsReadingsThatContradictEachOther() {
        assertThrows(IllegalArgumentException.class,
                () -> TimerShareClock.anchor(RECV, 60_000L, 63, true), "净时长比墙钟还多 3 秒：超出抖动余量");
        assertThrows(IllegalArgumentException.class,
                () -> TimerShareClock.anchor(RECV, 60_000L, -1, true));
        assertThrows(IllegalArgumentException.class,
                () -> TimerShareClock.anchor(RECV, -1L, 0, true));
    }

    @Test
    void rejectsImplausiblyLongSessions() {
        long tooLong = TimerSharePolicy.MAX_WALL_ELAPSED_MS + 1;
        assertThrows(IllegalArgumentException.class,
                () -> TimerShareClock.anchor(RECV, tooLong, 0, true));
        // 恰好等于上限是合法的
        TimerShareClock.anchor(RECV, TimerSharePolicy.MAX_WALL_ELAPSED_MS, 0, true);
    }

    @Test
    void excludedSecondsStaysExactWhenNothingWasPaused() {
        // 没暂停：net == floor(wall) ⇒ excluded 0，接收方与主持方逐秒同步
        TimerShareClock.Anchors a = TimerShareClock.anchor(RECV, 123_999L, 123, true);
        assertEquals(0, a.excludedSeconds());
        assertEquals(123, TimerShareClock.netSecondsAt(a, RECV));
        assertEquals(124, TimerShareClock.netSecondsAt(a, RECV + 1L), "墙钟 124.000 秒的那一毫秒，净秒数进位");
    }
}
