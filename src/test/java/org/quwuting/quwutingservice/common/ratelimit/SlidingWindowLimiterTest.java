package org.quwuting.quwutingservice.common.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 滑动窗口限流（纯函数，时间由调用方传入——不依赖真实睡眠）。 */
class SlidingWindowLimiterTest {

    private static final long T0 = 1_000_000L;

    @Test
    void allowsUpToTheLimitThenRejects() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(3, 60_000L, 100);
        assertTrue(limiter.tryAcquire("a", T0));
        assertTrue(limiter.tryAcquire("a", T0 + 1));
        assertTrue(limiter.tryAcquire("a", T0 + 2));
        assertFalse(limiter.tryAcquire("a", T0 + 3), "第 4 次超限");
    }

    @Test
    void slotsFreeUpAsEventsSlideOutOfTheWindow() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(2, 10_000L, 100);
        assertTrue(limiter.tryAcquire("a", T0));
        assertTrue(limiter.tryAcquire("a", T0 + 4_000));
        assertFalse(limiter.tryAcquire("a", T0 + 9_999), "最早一次仍在窗口内（差 9999ms < 10000ms）");
        assertTrue(limiter.tryAcquire("a", T0 + 10_000), "最早一次恰好滑出窗口，释放一个名额");
        assertFalse(limiter.tryAcquire("a", T0 + 10_001), "此时窗口内又是 2 次");
    }

    @Test
    void rejectedAttemptsDoNotExtendTheLockout() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, 10_000L, 100);
        assertTrue(limiter.tryAcquire("a", T0));
        // 被拒的尝试不计入窗口：持续重试的客户端不会把自己永远锁死
        for (int i = 1; i <= 50; i++) {
            assertFalse(limiter.tryAcquire("a", T0 + i * 100L));
        }
        assertTrue(limiter.tryAcquire("a", T0 + 10_000L), "只有第一次被放行的那次决定何时解封");
    }

    @Test
    void keysAreIsolated() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, 60_000L, 100);
        assertTrue(limiter.tryAcquire("u1", T0));
        assertFalse(limiter.tryAcquire("u1", T0 + 1));
        assertTrue(limiter.tryAcquire("u2", T0 + 1), "另一个用户不受影响");
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLimiter(0, 1000, 10));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLimiter(1, 0, 10));
    }
}
