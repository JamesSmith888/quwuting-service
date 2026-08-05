package org.quwuting.quwutingservice.venuereaction.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.quwuting.quwutingservice.venuereaction.repository.VenueReactionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 场所 Reaction 的"聚合统计"缓存服务，与用户个人参与状态彻底分离。
 * <p>
 * 与 {@link org.quwuting.quwutingservice.taginteraction.service.TagAggregateStatsService} 同模式
 * （内嵌 Caffeine LoadingCache，refresh-ahead）：只缓存与用户无关的场所级聚合计数
 * （每个 Reaction 代码的 今日/7天/30天/全部 计数），个人参与状态永远实时查询、不缓存。
 * <p>
 * 每个 venueId 对应的值为 {@code Map<reactionCode, long[]{countAll, countToday, count7d, count30d}>}。
 * <p>
 * 2026-08 每日一记模型（见 AGENTS.md「Reaction 快速反馈系统」）：聚合语义不变——四窗口实时统计
 * 当前全部生效记录（取消即物理删除，天然不计入）；窗口锚点为"此刻"（今天0点 / now-7d / now-30d），
 * 随时间推移记录自然滑出近期窗口（时间衰减），无需周期性清零。
 */
@Service
public class VenueReactionAggregateService {

    private final VenueReactionRepository venueReactionRepository;
    private final LoadingCache<Long, Map<String, long[]>> aggregateCache;

    private static final long CACHE_REFRESH_SECONDS = 60;
    private static final long CACHE_EXPIRE_MINUTES = 30;

    public VenueReactionAggregateService(VenueReactionRepository venueReactionRepository) {
        this.venueReactionRepository = venueReactionRepository;
        this.aggregateCache = Caffeine.newBuilder()
                .maximumSize(500)
                .refreshAfterWrite(CACHE_REFRESH_SECONDS, TimeUnit.SECONDS)
                .expireAfterWrite(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
                .build(this::computeAggregate);
    }

    /** 获取场所各 Reaction 的四窗口聚合计数（缓存：单飞 + refresh-ahead） */
    public Map<String, long[]> getAggregate(Long venueId) {
        return aggregateCache.get(venueId);
    }

    /** 写路径显式失效：toggle 写操作完成后必须调用 */
    public void invalidate(Long venueId) {
        aggregateCache.invalidate(venueId);
    }

    /**
     * 聚合计算（缓存 loader，勿直接调用——经 {@link #getAggregate} 走缓存）。
     * <p>
     * "今日/7天/30天"为真实时间窗口锚点（今天0点/7天前/30天前的此刻），不同于热度模块
     * "截至昨日"的排他上界约定——Reaction 是实时众包信号，越新鲜的窗口越该反映"此刻"，
     * 见 {@link VenueReactionRepository#aggregateByVenue} 注释与 AGENTS.md 说明。
     */
    private Map<String, long[]> computeAggregate(Long venueId) {
        LocalDateTime sinceToday = LocalDate.now().atStartOfDay();
        LocalDateTime since7d = LocalDateTime.now().minusDays(7);
        LocalDateTime since30d = LocalDateTime.now().minusDays(30);

        Map<String, long[]> result = new HashMap<>();
        for (Object[] row : venueReactionRepository.aggregateByVenue(venueId, sinceToday, since7d, since30d)) {
            String code = (String) row[0];
            long countAll = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            long countToday = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            long count7d = row[3] != null ? ((Number) row[3]).longValue() : 0L;
            long count30d = row[4] != null ? ((Number) row[4]).longValue() : 0L;
            result.put(code, new long[]{countAll, countToday, count7d, count30d});
        }
        return result.isEmpty() ? Collections.emptyMap() : result;
    }
}
