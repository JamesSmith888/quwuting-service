package org.quwuting.quwutingservice.dancer.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 舞伴"认可统计"的聚合缓存服务，与用户个人认可状态彻底分离。
 * <p>
 * 与 {@code VenueReactionAggregateService} / {@code TagAggregateStatsService} 同模式
 * （内嵌 Caffeine LoadingCache，refresh-ahead）：只缓存与用户无关的舞伴级聚合计数
 * （{@code long[]{countAll, countToday, count7d, count30d}}），个人"今日已认可"状态
 * 永远实时查询、不缓存。
 * <p>
 * 写路径（认可/取消）完成后必须调用 {@link #invalidate(Long)}，refresh/expire 仅兜底。
 */
@Service
public class DancerAggregateService {

    private final DancerRecognitionRepository recognitionRepository;
    private final LoadingCache<Long, long[]> aggregateCache;

    private static final long CACHE_REFRESH_SECONDS = 60;
    private static final long CACHE_EXPIRE_MINUTES = 30;

    public DancerAggregateService(DancerRecognitionRepository recognitionRepository) {
        this.recognitionRepository = recognitionRepository;
        this.aggregateCache = Caffeine.newBuilder()
                .maximumSize(500)
                .refreshAfterWrite(CACHE_REFRESH_SECONDS, TimeUnit.SECONDS)
                .expireAfterWrite(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
                .build(this::computeAggregate);
    }

    /** 获取舞伴四窗口认可统计（缓存：单飞 + refresh-ahead），值 = {countAll, countToday, count7d, count30d} */
    public long[] getAggregate(Long dancerId) {
        return aggregateCache.get(dancerId);
    }

    /** 写路径显式失效：认可/取消写操作完成后必须调用 */
    public void invalidate(Long dancerId) {
        aggregateCache.invalidate(dancerId);
    }

    /**
     * 聚合计算（缓存 loader，勿直接调用——经 {@link #getAggregate} 走缓存）。
     * "今日/7天/30天"为真实时间窗口锚点（今天0点/7天前/30天前的此刻），同 Reaction
     * 统计口径（实时众包信号，越新鲜越反映"此刻"，见
     * {@code DancerRecognitionRepository#aggregateByDancer} 注释）。
     */
    private long[] computeAggregate(Long dancerId) {
        LocalDateTime sinceToday = LocalDate.now().atStartOfDay();
        LocalDateTime since7d = LocalDateTime.now().minusDays(7);
        LocalDateTime since30d = LocalDateTime.now().minusDays(30);
        Object[] row = recognitionRepository.aggregateByDancer(dancerId, sinceToday, since7d, since30d);
        if (row == null || row.length < 4) {
            return new long[]{0L, 0L, 0L, 0L};
        }
        return new long[]{
                row[0] != null ? ((Number) row[0]).longValue() : 0L,
                row[1] != null ? ((Number) row[1]).longValue() : 0L,
                row[2] != null ? ((Number) row[2]).longValue() : 0L,
                row[3] != null ? ((Number) row[3]).longValue() : 0L,
        };
    }
}
