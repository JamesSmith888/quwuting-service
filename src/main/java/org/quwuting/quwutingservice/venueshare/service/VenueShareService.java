package org.quwuting.quwutingservice.venueshare.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.venueshare.entity.VenueShare;
import org.quwuting.quwutingservice.venueshare.enums.ShareEventType;
import org.quwuting.quwutingservice.venueshare.repository.VenueShareRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

/**
 * 场所分享事件记录服务（fire-and-forget 语义，复用 {@code VenueViewService} 的频控模式）。
 * <p>
 * - 软鉴权：登录用户记录 userId（身份归因），匿名用户 userId=null（仅 IP 频控）——分享是
 *   用户自然行为，要求登录会扼杀传播（未登录用户看到好店也要能转发）
 * - 频控：同场所同身份（userId 或 IP）60s 窗口内最多记一条——压制脚本连点刷事件
 *   放大分享 / 回流量的漏洞（尽力而为，多 IP 分布式刷无法拦截，与浏览频控同语义）
 * - 不做场所存在性校验：事件端点由详情页发起（场所不存在时详情页已 404），冗余的场所
 *   查询对 fire-and-forget 端点是不合理的延迟负担；孤儿事件不会被任何统计引用
 * - 不参与热度计算：分享维度不在热度公式闭集内（产品定义），本表仅作分析数据源
 *   （邀请排行 / 热门传播门店 / 回流归因），不 invalidate 热度缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueShareService {

    /** 事件频控窗口：同一场所同一身份（userId 或 IP）在窗口内只记 1 条 */
    private static final long EVENT_RATE_LIMIT_SECONDS = 60;

    /** 频控缓存（key = venueId:identity；putIfAbsent 竞争窗口内可能双写一条，无害） */
    private final Cache<String, Boolean> eventLimiter = Caffeine.newBuilder()
            .expireAfterWrite(EVENT_RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
            .maximumSize(50_000)
            .build();

    private final VenueShareRepository venueShareRepository;

    /**
     * 记录一次分享动作（SHARE 事件）。
     *
     * @param venueId 场所 ID
     * @param userId  分享者用户 ID，匿名时为 null
     * @param channel 分享发起渠道（BUTTON / MENU / TIMELINE），可为 null
     */
    @Transactional
    public void recordShare(Long venueId, Long userId, String channel) {
        if (isRateLimited(venueId, userId)) {
            return;
        }
        VenueShare share = new VenueShare();
        share.setVenueId(venueId);
        share.setUserId(userId);
        share.setEventType(ShareEventType.SHARE);
        share.setChannel(channel);
        venueShareRepository.save(share);
    }

    /**
     * 记录一次分享打开（OPEN 事件，分享卡片回流归因）。
     *
     * @param venueId   场所 ID
     * @param userId    打开者用户 ID，匿名时为 null
     * @param shareFrom 原分享者用户 ID（分享路径 share_from 参数），可空
     */
    @Transactional
    public void recordOpen(Long venueId, Long userId, Long shareFrom) {
        if (isRateLimited(venueId, userId)) {
            return;
        }
        VenueShare open = new VenueShare();
        open.setVenueId(venueId);
        open.setUserId(userId);
        open.setEventType(ShareEventType.OPEN);
        open.setShareFrom(shareFrom);
        venueShareRepository.save(open);
    }

    /**
     * 事件频控判定：同场所同身份（已登录按 userId，匿名按 IP）在窗口内已记录过则跳过。
     * putIfAbsent 原子占位——并发首写时可能都通过（最多双写一条，对统计无实质影响）。
     */
    private boolean isRateLimited(Long venueId, Long userId) {
        String identity = userId != null
                ? "u" + userId
                : "ip:" + resolveClientIp();
        String key = venueId + ":" + identity;
        return eventLimiter.asMap().putIfAbsent(key, Boolean.TRUE) != null;
    }

    /**
     * 解析客户端 IP：优先 X-Forwarded-For 第一个地址（代理链路真实来源），
     * 回退 remoteAddr。代理剥离 XFF 时两者同为网关地址——频控退化为"场所级防抖"，仍可接受。
     */
    private String resolveClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        String xff = attrs.getRequest().getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return attrs.getRequest().getRemoteAddr();
    }
}
