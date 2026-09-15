package org.quwuting.quwutingservice.venueshare.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.web.ClientIpResolver;
import org.quwuting.quwutingservice.venueshare.entity.VenueShare;
import org.quwuting.quwutingservice.venueshare.enums.ShareEventType;
import org.quwuting.quwutingservice.venueshare.repository.VenueShareRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * - 活动维度（2026-09-16，V28，docs/agents/49-venue-activities.md §8）：SHARE / OPEN
 *   事件各可携带 activityId（可空 = 场所级分享），把归因从「谁分享的」升级为
 *   「哪个活动被传播 / 哪条活动的卡片被点开」。同样不做活动存在性校验
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
     * @param venueId    场所 ID
     * @param userId     分享者用户 ID，匿名时为 null
     * @param channel    分享发起渠道（BUTTON / MENU / TIMELINE），可为 null
     * @param activityId 活动 ID（2026-09-16，V28，docs/agents/49-venue-activities.md §8），
     *                   可空 = 场所级分享。不做存在性校验（与 venueId 同策略）。
     */
    @Transactional
    public void recordShare(Long venueId, Long userId, String channel, Long activityId) {
        if (isRateLimited(venueId, userId)) {
            return;
        }
        VenueShare share = new VenueShare();
        share.setVenueId(venueId);
        share.setActivityId(activityId);
        share.setUserId(userId);
        share.setEventType(ShareEventType.SHARE);
        share.setChannel(channel);
        venueShareRepository.save(share);
    }

    /**
     * 记录一次分享打开（OPEN 事件，分享卡片回流归因）。
     *
     * @param venueId    场所 ID
     * @param userId     打开者用户 ID，匿名时为 null
     * @param shareFrom  原分享者用户 ID（分享路径 share_from 参数），可空
     * @param activityId 活动 ID（落地 URL 的 act_id 参数），可空。
     *                   与 shareFrom 相互独立：匿名分享者的卡片不带 share_from，
     *                   但仍可带 act_id（活动归因不依赖分享者身份）。
     */
    @Transactional
    public void recordOpen(Long venueId, Long userId, Long shareFrom, Long activityId) {
        if (isRateLimited(venueId, userId)) {
            return;
        }
        VenueShare open = new VenueShare();
        open.setVenueId(venueId);
        open.setActivityId(activityId);
        open.setUserId(userId);
        open.setEventType(ShareEventType.OPEN);
        open.setShareFrom(shareFrom);
        venueShareRepository.save(open);
    }

    /**
     * 事件频控判定：同场所同身份（已登录按 userId，匿名按 IP）在窗口内已记录过则跳过。
     * putIfAbsent 原子占位——并发首写时可能都通过（最多双写一条，对统计无实质影响）。
     * <p>
     * ⚠️ 频控键**刻意不含 activityId**（2026-09-16 定）：一个用户在 60s 内连续分享同一
     * 门店的两条不同活动时，只有第一条会入库。这是有意的取舍——频控的目的是压制脚本
     * 连点刷事件，而"同一人 60s 内分享同一门店多次"本身就是可疑行为；把 activityId 并进
     * 频控键会让连点脚本换个活动 ID 就能绕过。代价（极少数真实用户快速连转两条活动只记
     * 一条）远小于收益。两个事件类型（SHARE/OPEN）共用同一键，与既有行为一致。
     */
    private boolean isRateLimited(Long venueId, Long userId) {
        String identity = userId != null
                ? "u" + userId
                : "ip:" + ClientIpResolver.resolve();
        String key = venueId + ":" + identity;
        return eventLimiter.asMap().putIfAbsent(key, Boolean.TRUE) != null;
    }
}
