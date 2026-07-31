package org.quwuting.quwutingservice.taginteraction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.taginteraction.RatingDimensions;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * 同时 toggleLike / score 写操作对本缓存做 @CacheEvict，即时刷新聚合数据，
 * 不必等待 60 秒 TTL 自然过期。
 * <p>
 * 独立成单独的 Bean 是必要的：Spring AOP 基于代理实现 @Cacheable，
 * 同一个类内部通过 this 自调用会绕开代理导致缓存注解静默失效，
 * 必须让被缓存的方法从另一个 Bean 上被调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagAggregateStatsService {

    private final TagInteractionRepository tagInteractionRepository;
    private final VenueRepository venueRepository;
    private final ObjectMapper objectMapper;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

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

    @Cacheable(value = CacheConfig.CACHE_TAG_STATS, key = "#venueId")
    @Transactional(readOnly = true)
    public TagAggregate getAggregate(Long venueId) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
        List<String> venueTags = deserializeStringList(venue.getTags());

        List<Object[]> likeRows = tagInteractionRepository.countLikesByVenueGroupByTag(venueId);
        Map<String, Long> likeCountMap = new HashMap<>();
        for (Object[] row : likeRows) {
            likeCountMap.put((String) row[0], (Long) row[1]);
        }

        LocalDateTime now = LocalDateTime.now();
        List<Object[]> multiWindowRows = tagInteractionRepository.aggregateScoresMultiWindow(
                venueId, now.minusDays(30), now.minusDays(7));
        Map<String, double[]> multiWindowMap = new HashMap<>();
        for (Object[] row : multiWindowRows) {
            String dimTag = (String) row[0];
            if (RatingDimensions.isValid(dimTag)) {
                double avgAll = row[1] != null ? ((Number) row[1]).doubleValue() : 0;
                long countAll = ((Number) row[2]).longValue();
                double avg30d = row[3] != null ? ((Number) row[3]).doubleValue() : 0;
                long count30d = row[4] != null ? ((Number) row[4]).longValue() : 0;
                double avg7d = row[5] != null ? ((Number) row[5]).doubleValue() : 0;
                long count7d = row[6] != null ? ((Number) row[6]).longValue() : 0;
                multiWindowMap.put(dimTag, new double[]{avgAll, countAll, avg30d, count30d, avg7d, count7d});
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
