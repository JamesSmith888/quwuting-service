package org.quwuting.quwutingservice.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.ArrayDeque;

/**
 * 滑动窗口速率限制器（2026-10-07，首个消费方 = 计时同步 {@code timershare}）。
 * <p>
 * 语义：同一个 key 在任意长度为 {@code windowMs} 的窗口内最多放行 {@code maxEvents} 次；
 * 超限的那次<b>被拒绝且不计入窗口</b>（被拒的尝试不延长封锁期，否则一个持续重试的客户端会把自己
 * 永远锁死）。进程内实现（单实例部署），重启即清零——它防的是突发速率，不是持久黑名单；
 * 与 {@link FailedAttemptLimiter}（固定窗口、只数失败）互补：这个数的是<b>所有尝试</b>。
 * <p>
 * 时间由调用方传入（{@code nowMs}）：限流判定是纯函数，可确定性单测，不依赖真实睡眠。
 * key 的选择由调用方负责（通常是 userId；匿名入口用 {@code ClientIpResolver} 的来源 IP）。
 */
public final class SlidingWindowLimiter {

    private final int maxEvents;
    private final long windowMs;
    private final Cache<String, ArrayDeque<Long>> windows;

    /**
     * @param maxEvents 窗口内最多放行次数（≥1）
     * @param windowMs  窗口长度（毫秒，&gt;0）
     * @param maxKeys   同时跟踪的 key 上限（防异常流量撑大内存；超限按 Caffeine 策略淘汰）
     */
    public SlidingWindowLimiter(int maxEvents, long windowMs, long maxKeys) {
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be >= 1");
        }
        if (windowMs < 1) {
            throw new IllegalArgumentException("windowMs must be > 0");
        }
        this.maxEvents = maxEvents;
        this.windowMs = windowMs;
        // 一个 key 在窗口长度内没有任何访问，其队列里的事件必然全部过期，可安全回收
        this.windows = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMillis(windowMs))
                .maximumSize(maxKeys)
                .build();
    }

    /**
     * 尝试占用一次额度。
     *
     * @return true = 放行（已计入窗口）；false = 超限（未计入）
     */
    public boolean tryAcquire(String key, long nowMs) {
        ArrayDeque<Long> window = windows.get(key, k -> new ArrayDeque<>());
        synchronized (window) {
            // 淘汰已滑出窗口的事件（队首最旧）
            while (!window.isEmpty() && nowMs - window.peekFirst() >= windowMs) {
                window.pollFirst();
            }
            if (window.size() >= maxEvents) {
                return false;
            }
            window.addLast(nowMs);
            return true;
        }
    }
}
