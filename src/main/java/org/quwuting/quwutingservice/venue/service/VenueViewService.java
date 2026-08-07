package org.quwuting.quwutingservice.venue.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.web.ClientIpResolver;
import org.quwuting.quwutingservice.venue.repository.VenueViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 场所浏览记录服务。
 * <p>
 * 已登录用户按 (venueId, userId, viewDate) 去重（同一天仅记一条）；
 * 匿名用户 userId 为 null，无法按身份去重——2026-08 起增加 <b>60s 简单频控</b>
 * （同场所同客户端 IP 60 秒内只记一条），压制脚本连点/自动刷新放大 PV 的漏洞。
 * 频控是尽力而为（多 IP 分布式刷无法拦截），key 取 X-Forwarded-For 第一个 IP；
 * 拿不到请求上下文时降级为固定 key（粗粒度"场所级 60s 防抖"，仍能防单端连点）。
 * <p>
 * 写入采用无条件 upsert（{@code INSERT ... ON CONFLICT DO NOTHING}，见
 * {@link VenueViewRepository#upsertView}）：恒为 1 次 DB 往返，去重与并发竞态
 * 由联合唯一约束在库内兜底。早期实现为 check-then-act（先 SELECT 存在性再 INSERT），
 * 当天首次浏览需 2 次跨洲往返（约 800ms），且 SELECT 与 INSERT 之间存在并发窗口
 * 需 catch 唯一约束异常——upsert 同时消除了这两项开销。
 * <p>
 * 不做场所存在性校验：此端点为 fire-and-forget，由详情页 GET /venues/{id} 发起，
 * 场所不存在时详情页已返回 404。冗余的场所查询（跨洲 DB 往返 300ms）对 fire-and-forget
 * 端点是不合理的延迟负担——即使场所不存在，写入的 view 记录也无害（不会被热度统计引用，
 * 因为热度统计从 qwt_venues 表驱动）。
 */
@Service
@RequiredArgsConstructor
public class VenueViewService {

    /** 匿名浏览频控窗口：同一场所同一 IP 在窗口内只计 1 条 */
    private static final long ANON_RATE_LIMIT_SECONDS = 60;

    /** 匿名频控缓存（key = venueId:ip；未命中时 putIfAbsent 竞争窗口内可能双写一条，无害） */
    private final Cache<String, Boolean> anonymousViewLimiter = Caffeine.newBuilder()
            .expireAfterWrite(ANON_RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
            .maximumSize(50_000)
            .build();

    private final VenueViewRepository venueViewRepository;

    /**
     * 记录一次浏览（匿名用户最多 60s 一条，已登录用户由 upsert 按天去重，恒 1 次 DB 往返）。
     *
     * @param venueId 场所 ID
     * @param userId  用户 ID，匿名时为 null（匿名记录参与 IP 频控，不参与按天去重）
     */
    @Transactional
    public void recordView(Long venueId, Long userId) {
        if (userId == null && isRateLimited(venueId)) {
            return; // 匿名频控命中：跳过写入（尽力而为，防止脚本连点放大 PV）
        }
        LocalDate today = LocalDate.now();
        venueViewRepository.upsertView(venueId, userId, today, LocalDateTime.now());
    }

    /**
     * 匿名频控判定：同场所同 IP 在 ANON_RATE_LIMIT_SECONDS 内已记录过则返回 true。
     * putIfAbsent 原子占位——并发首写时可能都通过（最多双写一条匿名记录，对统计无实质影响）。
     */
    private boolean isRateLimited(Long venueId) {
        String ip = ClientIpResolver.resolve();
        String key = venueId + ":" + (ip != null ? ip : "anon");
        return anonymousViewLimiter.asMap().putIfAbsent(key, Boolean.TRUE) != null;
    }
}
