package org.quwuting.quwutingservice.wxsubscribe.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 微信订阅通知「突发批次」限流（2026-09-08 新增，V13，docs/agents/41）。
 * <p>
 * <b>要解决的问题</b>：微信一次性订阅额度可无限累加（每次 accept +1；勾「总是保持
 * 以上选择 + 允许」后连弹窗都没有、静默累加），而发送侧原本逐事件逐用户下发、
 * 无聚合无频控。批量状态更新（32 城市 ~992 家门店的批量反转是常态运营动作）时，
 * 关注 N 家的用户会在同一分钟收到 N 条微信服务通知 → 骚扰 → 用户去设置里关闭
 * 订阅消息（微信侧永久、开发者几乎无法拉回）→ 唯一被动触达通道报废。
 * <p>
 * <b>治理方向（用户 2026-09-08 拍板）</b>：不限制用户授权——囤额度是有意愿的信号；
 * 改为<b>限制我们自己一次能打断用户几次</b>：突发窗口内最多下发 N 条微信，超出部分
 * 只走站内信（关注者站内信已在 {@code VenueStatusWatcherService#notifyStatusChanged}
 * 逐 watcher 发出，不受本限流器影响），且<b>不扣额度</b>。
 * <p>
 * <b>窗口语义：识别「一批」，不是限流</b>。窗口内允许下发 {@code limit} 条（默认 3），
 * 只在超出时才跳过——它回答的是"这 N 条算不算同一次事件突发"，而不是"多久才能发一条"。
 * 窗口是<b>固定窗口</b>（自窗口内首次发送起算，非滑动）：批量事件在数秒内连续触发，
 * 固定窗口语义更符合"一批"，也避免了持续零散变化导致窗口被无限续期。
 * <p>
 * <b>为什么是进程内缓存而不是 DB 计数</b>：本限流器只服务于"同一时刻的事件突发"
 * 这一启发式判定，允许重启后状态丢失（最坏情况 = 重启当天多收到几条通知）；
 * 换 DB 计数会为每条通知增加一次写放大，得不偿失。精确的事后审计看
 * {@code qwt_wx_subscribe_logs}。
 */
@Slf4j
@Service
public class WxSubscribeBurstLimiter {

    /** 档位值：不限（接收全部门店变动通知，用户自主选择） */
    public static final int UNLIMITED = 0;

    /** 突发窗口时长（毫秒，固定窗口） */
    private final long windowMillis;

    /**
     * 用户 → 当前突发窗口状态。expireAfterWrite 取窗口 2 倍时长做<b>内存兜底</b>
     * （防长期不活跃条目堆积）；窗口是否过期由 {@link Window#startedAt} 逻辑判定，
     * 不依赖缓存驱逐时机。
     */
    private final Cache<Long, Window> windows;

    public WxSubscribeBurstLimiter(
            @Value("${wechat.subscribe.burst-window-minutes:3}") long windowMinutes) {
        long minutes = Math.max(1, windowMinutes);
        this.windowMillis = minutes * 60_000L;
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(minutes * 2, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();
    }

    /**
     * 本次下发是否放行。
     *
     * @param userId 收件用户
     * @param limit  该用户档位（{@value #UNLIMITED} = 不限；&gt; 0 = 窗口内最多条数）
     * @return true = 可发微信；false = 跳过（站内信已发，额度不扣）
     */
    public boolean allow(long userId, int limit) {
        if (limit == UNLIMITED) {
            return true;
        }
        long now = System.currentTimeMillis();
        Window window = windows.get(userId, k -> new Window(now));
        synchronized (window) {
            if (now - window.startedAt >= windowMillis) {
                // 固定窗口过期：开新一轮（批量事件在数秒内触发，同批必然落在同一窗口内）
                window.startedAt = now;
                window.count = 0;
            }
            if (window.count >= limit) {
                log.info("wx subscribe burst limited (userId={}, limit={}, windowMin={})",
                        userId, limit, windowMillis / 60_000L);
                return false;
            }
            window.count++;
            return true;
        }
    }

    /** 窗口状态（固定窗口：startedAt = 本窗口首次发送时刻，count = 已放行条数） */
    private static final class Window {
        private long startedAt;
        private int count;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
