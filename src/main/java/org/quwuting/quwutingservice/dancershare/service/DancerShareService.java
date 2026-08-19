package org.quwuting.quwutingservice.dancershare.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.web.ClientIpResolver;
import org.quwuting.quwutingservice.dancershare.entity.DancerShare;
import org.quwuting.quwutingservice.dancershare.repository.DancerShareRepository;
import org.quwuting.quwutingservice.venueshare.enums.ShareEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

/**
 * 舞伴分享事件记录服务（fire-and-forget 语义，镜像 {@code VenueShareService} 的频控模式）。
 * <p>
 * - 软鉴权：登录用户记录 userId（身份归因），匿名用户 userId=null（仅 IP 频控）——分享是
 *   用户自然行为，要求登录会扼杀传播（未登录用户看到心仪舞伴也要能转发）
 * - 频控：同舞伴同身份（userId 或 IP）60s 窗口内最多记一条——压制脚本连点刷事件
 *   放大分享 / 回流量的漏洞（尽力而为，多 IP 分布式刷无法拦截，与浏览频控同语义）
 * - 不做舞伴存在性校验：事件端点由详情页发起（舞伴不存在时详情页已 404），冗余的舞伴
 *   查询对 fire-and-forget 端点是不合理的延迟负担；孤儿事件不会被任何统计引用
 * - 不参与热度公式：分享维度不在热度公式闭集内（产品定义），本表仅作分析数据源
 *   （邀请排行 / 热门传播舞伴 / 回流归因）；2026-08-14 起 SHARE 事件是舞伴分享趋势
 *   （shareTrend）输入——真实记录后 invalidate DancerStatsService（OPEN 回流不入图，不失效）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DancerShareService {

    /** 事件频控窗口：同一舞伴同一身份（userId 或 IP）在窗口内只记 1 条 */
    private static final long EVENT_RATE_LIMIT_SECONDS = 60;

    /** 频控缓存（key = dancerId:identity；putIfAbsent 竞争窗口内可能双写一条，无害） */
    private final Cache<String, Boolean> eventLimiter = Caffeine.newBuilder()
            .expireAfterWrite(EVENT_RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
            .maximumSize(50_000)
            .build();

    private final DancerShareRepository dancerShareRepository;
    /**
     * 详情/统计缓存失效入口（2026-08-19 收敛到 DancerDetailCacheService 唯一入口，
     * 级联失效内层 DancerStatsService）：SHARE 事件是分享趋势（shareTrend）输入，
     * 真实记录后须失效；OPEN 回流事件不输入分享趋势，不失效。
     */
    private final org.quwuting.quwutingservice.dancer.service.DancerDetailCacheService dancerDetailCacheService;

    /**
     * 记录一次分享动作（SHARE 事件）。
     *
     * @param dancerId 舞伴 ID
     * @param userId   分享者用户 ID，匿名时为 null
     * @param channel  分享发起渠道（BUTTON / MENU / TIMELINE），可为 null
     */
    @Transactional
    public void recordShare(Long dancerId, Long userId, String channel) {
        if (isRateLimited(dancerId, userId)) {
            return;
        }
        DancerShare share = new DancerShare();
        share.setDancerId(dancerId);
        share.setUserId(userId);
        share.setEventType(ShareEventType.SHARE);
        share.setChannel(channel);
        dancerShareRepository.save(share);
        // 分享趋势（shareTrend）输入真实写入后失效统计缓存。2026-08-19 根因修复：
        // 失效必须延后到事务提交后（项目「失效时机约束」）——提交前失效存在竞态窗口：
        // 另一线程读到 cache miss → 回源重算 → 读不到本事务未提交数据 → 缓存陈旧值
        // （对齐 DancerViewService / PointsService.gift 的 afterCommit 模式；
        // 旧实现提交前内联失效，违反同一约束）。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dancerDetailCacheService.invalidate(dancerId);
                }
            });
        }
    }

    /**
     * 记录一次分享打开（OPEN 事件，分享卡片回流归因）。
     *
     * @param dancerId  舞伴 ID
     * @param userId    打开者用户 ID，匿名时为 null
     * @param shareFrom 原分享者用户 ID（分享路径 share_from 参数），可空
     */
    @Transactional
    public void recordOpen(Long dancerId, Long userId, Long shareFrom) {
        if (isRateLimited(dancerId, userId)) {
            return;
        }
        DancerShare open = new DancerShare();
        open.setDancerId(dancerId);
        open.setUserId(userId);
        open.setEventType(ShareEventType.OPEN);
        open.setShareFrom(shareFrom);
        dancerShareRepository.save(open);
    }

    /**
     * 事件频控判定：同舞伴同身份（已登录按 userId，匿名按 IP）在窗口内已记录过则跳过。
     * putIfAbsent 原子占位——并发首写时可能都通过（最多双写一条，对统计无实质影响）。
     */
    private boolean isRateLimited(Long dancerId, Long userId) {
        String identity = userId != null
                ? "u" + userId
                : "ip:" + ClientIpResolver.resolve();
        String key = dancerId + ":" + identity;
        return eventLimiter.asMap().putIfAbsent(key, Boolean.TRUE) != null;
    }
}
