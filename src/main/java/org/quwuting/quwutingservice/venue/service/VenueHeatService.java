package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.taginteraction.RatingDimensions;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.dto.response.VenueHeatResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueStatusLogRepository;
import org.quwuting.quwutingservice.venue.repository.VenueViewRepository;
import org.quwuting.quwutingservice.venuepost.repository.VenuePostRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 场所热度计算服务（多维度聚合）。
 * <p>
 * 热度指数 = 浏览量 × W_VIEW + 收藏总数 × W_FAVORITE + 近期新增收藏 × W_NEW_FAVORITE
 *           + 动态总数 × W_POST + 近期评价数 × W_RATING + 近期点赞数 × W_LIKE
 *           + 满意度 × W_SATISFACTION（无评分时为 0）。
 * <p>
 * 权重常量收敛在本类内部，后续基于真实数据分布调优，接口契约不变。
 * <p>
 * 性能优化：通过条件聚合将原 14 次串行 DB 往返合并为 6 次，
 * 并加 60s TTL 本地缓存（聚合数据无需实时精度）。
 */
@Service
@RequiredArgsConstructor
public class VenueHeatService {

    // ── 热度权重常量 ──
    private static final long WEIGHT_VIEW = 1;
    private static final long WEIGHT_FAVORITE = 10;
    private static final long WEIGHT_NEW_FAVORITE = 15;
    private static final long WEIGHT_POST = 5;
    private static final long WEIGHT_RATING = 8;
    private static final long WEIGHT_LIKE = 3;
    private static final long WEIGHT_SATISFACTION = 20;

    /** 满意度最低样本量：评价人数不足此值时不展示具体分数 */
    private static final long MIN_RATING_SAMPLE = 3;

    /** 时间窗口：30 天 */
    private static final int WINDOW_DAYS = 30;

    private final VenueRepository venueRepository;
    private final FavoriteRepository favoriteRepository;
    private final VenuePostRepository venuePostRepository;
    private final VenueViewRepository venueViewRepository;
    private final VenueStatusLogRepository venueStatusLogRepository;
    private final TagInteractionRepository tagInteractionRepository;

    @Cacheable(value = CacheConfig.CACHE_VENUE_HEAT, key = "#venueId")
    @Transactional(readOnly = true)
    public VenueHeatResponse getHeat(Long venueId) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));

        LocalDateTime since30d = LocalDateTime.now().minusDays(WINDOW_DAYS);
        LocalDate sinceDate30d = LocalDate.now().minusDays(WINDOW_DAYS);

        // ── 浏览（PV + UV 合并为 1 次往返） ──
        Object[] viewRow = venueViewRepository.countPvAndUvByVenueIdSince(venueId, sinceDate30d);
        long viewCount30d = ((Number) viewRow[0]).longValue();
        long viewUv30d = ((Number) viewRow[1]).longValue();

        // ── 收藏（总数 + 30天新增 合并为 1 次往返） ──
        Object[] favRow = favoriteRepository.countTotalAndRecentByVenueId(venueId, since30d);
        long favoriteCount = ((Number) favRow[0]).longValue();
        long newFavoriteCount30d = favRow[1] != null ? ((Number) favRow[1]).longValue() : 0;

        // ── 动态（总数 + 30天新增 合并为 1 次往返） ──
        Object[] postRow = venuePostRepository.countTotalAndRecentByVenueId(venueId, since30d);
        long postCount = ((Number) postRow[0]).longValue();
        long newPostCount30d = postRow[1] != null ? ((Number) postRow[1]).longValue() : 0;

        // ── 评价互动（ratingCount30d + likeCount30d + distinctRaters 合并为 1 次往返） ──
        Object[] tiRow = tagInteractionRepository.countInteractionsForHeat(venueId, since30d);
        long ratingCount30d = ((Number) tiRow[0]).longValue();
        long likeCount30d = ((Number) tiRow[1]).longValue();
        long ratingTotalCount = ((Number) tiRow[2]).longValue();

        // ── 满意度（各维度等权均分，近30天窗口） ──
        Double satisfactionScore = computeSatisfaction(venueId, since30d, ratingTotalCount);

        // ── 营业稳定性（暂停次数 + 最近状态时间 合并为 1 次往返） ──
        Object[] statusRow = venueStatusLogRepository.countSuspensionsAndLatestTime(venueId, since30d);
        long suspensionCount30d = ((Number) statusRow[0]).longValue();
        long currentStatusDays = computeCurrentStatusDays(statusRow[1]);

        // ── 综合热度指数 ──
        long satisfactionComponent = satisfactionScore != null
                ? Math.round(satisfactionScore * WEIGHT_SATISFACTION)
                : 0;
        long heatScore = viewCount30d * WEIGHT_VIEW
                + favoriteCount * WEIGHT_FAVORITE
                + newFavoriteCount30d * WEIGHT_NEW_FAVORITE
                + postCount * WEIGHT_POST
                + ratingCount30d * WEIGHT_RATING
                + likeCount30d * WEIGHT_LIKE
                + satisfactionComponent;

        return new VenueHeatResponse(
                heatScore,
                viewCount30d, viewUv30d,
                favoriteCount, newFavoriteCount30d,
                postCount, newPostCount30d,
                ratingCount30d, likeCount30d,
                satisfactionScore, ratingTotalCount,
                suspensionCount30d, currentStatusDays,
                venue.getStatus().name(), venue.getStatus().getDisplayName()
        );
    }

    /**
     * 计算综合满意度：近30天各维度评分的等权均分。
     * 评价人数不足 MIN_RATING_SAMPLE 时返回 null（前端展示"暂无足够评价"）。
     */
    private Double computeSatisfaction(Long venueId, LocalDateTime since, long totalRaters) {
        if (totalRaters < MIN_RATING_SAMPLE) {
            return null;
        }
        List<Object[]> scores = tagInteractionRepository.aggregateScoresByVenueSinceGroupByTag(venueId, since);
        if (scores.isEmpty()) {
            // 近30天无评分但历史有足够样本，回退到全量
            scores = tagInteractionRepository.aggregateScoresByVenueGroupByTag(venueId);
        }
        if (scores.isEmpty()) {
            return null;
        }
        double sum = 0;
        int count = 0;
        for (Object[] row : scores) {
            String tag = (String) row[0];
            // 仅体验评估维度参与满意度（排除"现场状况"类：舞伴氛围/客流热度/舞伴年龄层）
            if (!RatingDimensions.isQualityDimension(tag)) {
                continue;
            }
            Object avg = row[1];
            if (avg != null) {
                sum += ((Number) avg).doubleValue();
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        // 保留一位小数
        return Math.round(sum / count * 10.0) / 10.0;
    }

    /** 从合并查询结果中提取当前状态持续天数 */
    private long computeCurrentStatusDays(Object latestCreatedAt) {
        if (latestCreatedAt == null) {
            return 0L;
        }
        LocalDateTime latest = ((java.sql.Timestamp) latestCreatedAt).toLocalDateTime();
        return ChronoUnit.DAYS.between(latest.toLocalDate(), LocalDate.now());
    }
}
