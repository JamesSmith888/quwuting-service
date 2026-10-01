package org.quwuting.quwutingservice.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 失败尝试限制器（2026-10-01，首个消费方 = Web 后台密码登录）。
 * <p>
 * 语义 = 固定窗口：某个 key 第一次失败起算 {@code window}，窗口内失败满 {@code maxFailures}
 * 次即锁定到窗口结束；成功一次立即清零。进程内实现（单实例部署），重启即清零——
 * 它防的是在线爆破的<b>速率</b>，不是持久黑名单。
 * <p>
 * key 的选择由调用方负责（通常是客户端 IP，见 {@code ClientIpResolver} 的信任边界说明）。
 */
public final class FailedAttemptLimiter {

    private final int maxFailures;
    private final Cache<String, AtomicInteger> failures;

    public FailedAttemptLimiter(int maxFailures, Duration window, long maxKeys) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be >= 1");
        }
        this.maxFailures = maxFailures;
        this.failures = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(maxKeys)
                .build();
    }

    /** 当前是否处于锁定期 */
    public boolean isBlocked(String key) {
        AtomicInteger count = failures.getIfPresent(key);
        return count != null && count.get() >= maxFailures;
    }

    /** 记一次失败，返回窗口内累计失败数 */
    public int recordFailure(String key) {
        return failures.get(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 成功后清零 */
    public void reset(String key) {
        failures.invalidate(key);
    }
}
