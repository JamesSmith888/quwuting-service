package org.quwuting.quwutingservice.taginteraction.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.taginteraction.RatingDimensions;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 场所标签的"聚合统计"缓存服务，与用户个人交互状态（点赞/评分是否为我）彻底分离。
 * <p>
 * 根因说明：早期实现将 likedByMe / myScore 等用户个人状态一并塞进以
 * {venueId, userId} 为 key 的缓存中，写操作（点赞/评分）未失效缓存，
 * 导致用户提交后 60 秒 TTL 内返回列表再进入详情页会看到操作前的状态
 * （完全重启小程序因间隔通常 > 60s 而"恰好"绕开了这个窗口，掩盖了问题）。
 * <p>
 * 修复方案：只缓存与用户无关的场所级聚合数据（点赞计数、评分均值，venueId 为 key），
 * 个人交互状态在 {@link TagInteractionService#getTagStats} 中始终实时查询、不缓存；
 * 同时 toggleLike / score 写操作调用 {@link #invalidate} 即时刷新聚合数据，
 * 不必等待刷新周期自然过期。
 * <p>
 * 缓存载体为内嵌 Caffeine {@link LoadingCache}（refresh-ahead），与 VenueHeatService
 * 同模式、同理由：refreshAfterWrite 要求 LoadingCache，Spring 缓存抽象无法为单个缓存
 * 注入各自的加载器。语义：60s 后访问返回旧值并异步重载（用户不感知冷加载）、
 * 30min 无访问硬过期、同 key 单飞、刷新失败保留旧值。
 */
@Slf4j
@Service
public class TagAggregateStatsService {

    private final TagInteractionRepository tagInteractionRepository;
    private final VenueLookupService venueLookupService;
    private final ObjectMapper objectMapper;
    private final LoadingCache<Long, TagAggregate> aggregateCache;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /** 缓存预刷新周期：60s 后访问返回旧值并触发异步重载 */
    private static final long CACHE_REFRESH_SECONDS = 60;

    /** 缓存硬过期：30 分钟无访问才驱逐 */
    private static final long CACHE_EXPIRE_MINUTES = 30;

    public TagAggregateStatsService(TagInteractionRepository tagInteractionRepository,
                                    VenueLookupService venueLookupService,
                                    ObjectMapper objectMapper) {
        this.tagInteractionRepository = tagInteractionRepository;
        this.venueLookupService = venueLookupService;
        this.objectMapper = objectMapper;
        this.aggregateCache = Caffeine.newBuilder()
                .maximumSize(500)
                .refreshAfterWrite(CACHE_REFRESH_SECONDS, TimeUnit.SECONDS)
                .expireAfterWrite(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
                .build(this::computeAggregate);
    }

    /**
     * 场所标签的聚合统计（不含任何用户个人状态，可安全缓存）。
     *
     * @param venueTags   场所当前标签列表（用于组装返回顺序）
     * @param likeCounts  tag → 点赞总数
     * @param multiWindow tag → [avgAll, countAll, avg30d, count30d, avg7d, count7d]
     */
    public record TagAggregate(
            List<String> venueTags,
            Map<String, Long> likeCounts,
            Map<String, double[]> multiWindow
    ) {}

    /**
     * 获取聚合统计（缓存：单飞 + refresh-ahead，见类注释）。
     * <p>
     * 共享缓存：详情页 GET /venues/{id}（tagLikeCounts）与 GET /venues/{venueId}/tags/stats
     * 消费同一份聚合数据、共享同一 venueId key——详情页并发的多个请求中仅首个触发回源。
     */
    public TagAggregate getAggregate(Long venueId) {
        return aggregateCache.get(venueId);
    }

    /**
     * 写路径显式失效：点赞/评分写操作完成后必须调用，
     * 保证所有用户及时看到最新聚合值（与 VenueHeatService.invalidate 同模式）。
     */
    public void invalidate(Long venueId) {
        aggregateCache.invalidate(venueId);
    }

    /**
     * 聚合计算（缓存 loader，勿直接调用——经 {@link #getAggregate} 走缓存）。
     * <p>
     * DB 往返压缩：点赞计数与三窗口评分聚合合并为单条条件聚合 SQL
     * （{@link TagInteractionRepository#aggregateLikesAndScoresByTag}），回源仅 1 次往返
     * （场所实体经 {@link VenueLookupService#findById} 缓存，命中时不再占往返）。
     */
    private TagAggregate computeAggregate(Long venueId) {
        Venue venue = venueLookupService.findById(venueId);
        List<String> venueTags = deserializeStringList(venue.getTags());

        LocalDateTime now = LocalDateTime.now();
        Map<String, Long> likeCountMap = new HashMap<>();
        Map<String, double[]> multiWindowMap = new HashMap<>();
        // 行结构：{tag, likeCount, avgAll, countAll, avg30d, count30d, avg7d, count7d}
        for (Object[] row : tagInteractionRepository.aggregateLikesAndScoresByTag(
                venueId, now.minusDays(30), now.minusDays(7))) {
            String tag = (String) row[0];
            long likeCount = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            if (likeCount > 0) {
                likeCountMap.put(tag, likeCount);
            }
            if (RatingDimensions.isValid(tag)) {
                double avgAll = row[2] != null ? ((Number) row[2]).doubleValue() : 0;
                long countAll = row[3] != null ? ((Number) row[3]).longValue() : 0L;
                double avg30d = row[4] != null ? ((Number) row[4]).doubleValue() : 0;
                long count30d = row[5] != null ? ((Number) row[5]).longValue() : 0L;
                double avg7d = row[6] != null ? ((Number) row[6]).doubleValue() : 0;
                long count7d = row[7] != null ? ((Number) row[7]).longValue() : 0L;
                multiWindowMap.put(tag, new double[]{avgAll, countAll, avg30d, count30d, avg7d, count7d});
            }
        }

        return new TagAggregate(venueTags, likeCountMap, multiWindowMap);
    }

    private List<String> deserializeStringList(String json) {
        if (!StringUtils.hasText(json)) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("Failed to deserialize tags: {}", json);
            return Collections.emptyList();
        }
    }
}
