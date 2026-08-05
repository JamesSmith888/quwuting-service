package org.quwuting.quwutingservice.taginteraction.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 场所评分维度的"聚合统计"缓存服务，与用户个人评分状态（我的评分是否为我）彻底分离。
 * <p>
 * 缓存载体为内嵌 Caffeine {@link LoadingCache}（refresh-ahead），与 VenueHeatService/
 * VenueReactionAggregateService 同模式：refreshAfterWrite 要求 LoadingCache（构建时提供
 * loader），Spring 缓存抽象无法为单个缓存注入各自的加载器。语义：60s 后访问返回旧值并
 * 异步重载、30min 无访问硬过期、同 key 单飞、刷新失败保留旧值。
 * <p>
 * <b>历史沿革</b>：本类原名承载"点赞计数 + 评分聚合"两类数据（{@code likeCounts} /
 * {@code venueTags}），"标签点赞"功能被 Reaction 快速反馈系统替代后（见 AGENTS.md
 * 「Reaction 快速反馈系统」），本类简化为只负责评分维度的多窗口聚合，不再依赖场所实体
 * 或标签列表。
 */
@Service
@RequiredArgsConstructor
public class TagAggregateStatsService {

    private final TagInteractionRepository tagInteractionRepository;

    /** 缓存预刷新周期：60s 后访问返回旧值并触发异步重载 */
    private static final long CACHE_REFRESH_SECONDS = 60;

    /** 缓存硬过期：30 分钟无访问才驱逐 */
    private static final long CACHE_EXPIRE_MINUTES = 30;

    private final LoadingCache<Long, Map<String, double[]>> aggregateCache = Caffeine.newBuilder()
            .maximumSize(500)
            .refreshAfterWrite(CACHE_REFRESH_SECONDS, TimeUnit.SECONDS)
            .expireAfterWrite(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
            .build(this::computeAggregate);

    /**
     * 获取场所各评分维度的三窗口聚合（tag → [avgAll, countAll, avg30d, count30d, avg7d, count7d]）。
     * 缓存：单飞 + refresh-ahead，见类注释。
     */
    public Map<String, double[]> getAggregate(Long venueId) {
        return aggregateCache.get(venueId);
    }

    /**
     * 写路径显式失效：评分写操作完成后必须调用，
     * 保证所有用户及时看到最新聚合值（与 VenueHeatService.invalidate 同模式）。
     */
    public void invalidate(Long venueId) {
        aggregateCache.invalidate(venueId);
    }

    /**
     * 聚合计算（缓存 loader，勿直接调用——经 {@link #getAggregate} 走缓存）。
     */
    private Map<String, double[]> computeAggregate(Long venueId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, double[]> multiWindowMap = new HashMap<>();
        // 行结构：{tag, avgAll, countAll, avg30d, count30d, avg7d, count7d}
        for (Object[] row : tagInteractionRepository.aggregateScoresMultiWindowByTag(
                venueId, now.minusDays(30), now.minusDays(7))) {
            String tag = (String) row[0];
            double avgAll = row[1] != null ? ((Number) row[1]).doubleValue() : 0;
            long countAll = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            double avg30d = row[3] != null ? ((Number) row[3]).doubleValue() : 0;
            long count30d = row[4] != null ? ((Number) row[4]).longValue() : 0L;
            double avg7d = row[5] != null ? ((Number) row[5]).doubleValue() : 0;
            long count7d = row[6] != null ? ((Number) row[6]).longValue() : 0L;
            multiWindowMap.put(tag, new double[]{avgAll, countAll, avg30d, count30d, avg7d, count7d});
        }
        return multiWindowMap.isEmpty() ? Collections.emptyMap() : multiWindowMap;
    }
}
