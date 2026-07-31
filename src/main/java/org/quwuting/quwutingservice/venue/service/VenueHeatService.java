package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.taginteraction.RatingDimensions;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.dto.response.FavoriteTrendPoint;
import org.quwuting.quwutingservice.venue.dto.response.VenueHeatResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.StatusConfidence;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueStatusLogRepository;
import org.quwuting.quwutingservice.venue.repository.VenueViewRepository;
import org.quwuting.quwutingservice.venuepost.repository.VenuePostRepository;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.ActiveReportSummary;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** 收藏趋势图窗口：14 天——比 30 天更适合小程序小屏图表的柱状数量，且足以看出升降走势 */
    private static final int TREND_WINDOW_DAYS = 14;

    /** 状态可信度矩阵阈值：不稳定门店状态持续天数 ≤ 此值视为"近期确认过"（MEDIUM），> 此值为 LOW */
    private static final long CONFIDENCE_RECENT_DAYS = 7;

    private final VenueRepository venueRepository;
    private final FavoriteRepository favoriteRepository;
    private final VenuePostRepository venuePostRepository;
    private final VenueViewRepository venueViewRepository;
    private final VenueStatusLogRepository venueStatusLogRepository;
    private final TagInteractionRepository tagInteractionRepository;
    private final StatusReportService statusReportService;

    @Cacheable(value = CacheConfig.CACHE_VENUE_HEAT, key = "#venueId")
    @Transactional(readOnly = true)
    public VenueHeatResponse getHeat(Long venueId) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));

        // 统计口径统一锚定在「昨天」：所有滚动窗口（近30天/近14天）的排他上界固定为
        // 今天 0 点（即只统计到昨天 24 点），不掺入当天尚未走完的部分。
        // 根因：当天数据是"半天"，与其余"整天"数据混在同一窗口聚合/对比（尤其是逐日趋势图）
        // 会系统性地把最新一天拉低，造成"数据在往下掉"的错觉；改为固定日期边界后，同一天内
        // 多次请求的统计结果也保持稳定，不再随请求时刻漂移。
        LocalDate today = LocalDate.now();
        LocalDate statsAsOfDate = today.minusDays(1);
        LocalDateTime windowEnd = today.atStartOfDay();
        LocalDateTime since30d = windowEnd.minusDays(WINDOW_DAYS);
        LocalDate sinceDate30d = today.minusDays(WINDOW_DAYS);

        // ── 浏览（PV + UV 合并为 1 次往返） ──
        VenueViewRepository.PvUvStats viewStats =
                venueViewRepository.countPvAndUvByVenueIdSince(venueId, sinceDate30d, today);
        long viewCount30d = viewStats.getPv();
        long viewUv30d = viewStats.getUv();

        // ── 收藏（总数 + 30天新增 合并为 1 次往返） ──
        FavoriteRepository.TotalRecentStats favStats =
                favoriteRepository.countTotalAndRecentByVenueId(venueId, since30d, windowEnd);
        long favoriteCount = favStats.getTotal();
        long newFavoriteCount30d = favStats.getRecent() != null ? favStats.getRecent() : 0;

        // ── 收藏趋势（近14天每日新增，图表用，截至昨天） ──
        List<FavoriteTrendPoint> favoriteTrend = computeFavoriteTrend(venueId, statsAsOfDate);

        // ── 动态（总数 + 30天新增 合并为 1 次往返） ──
        VenuePostRepository.TotalRecentStats postStats =
                venuePostRepository.countTotalAndRecentByVenueId(venueId, since30d, windowEnd);
        long postCount = postStats.getTotal();
        long newPostCount30d = postStats.getRecent() != null ? postStats.getRecent() : 0;

        // ── 评价互动（ratingCount30d + likeCount30d + distinctRaters 合并为 1 次往返） ──
        TagInteractionRepository.HeatInteractionStats tiStats =
                tagInteractionRepository.countInteractionsForHeat(venueId, since30d, windowEnd);
        long ratingCount30d = tiStats.getRatingcount();
        long likeCount30d = tiStats.getLikecount();
        long ratingTotalCount = tiStats.getRaters();

        // ── 满意度（各维度等权均分，近30天窗口） ──
        Double satisfactionScore = computeSatisfaction(venueId, since30d, windowEnd, ratingTotalCount);

        // ── 营业稳定性（暂停次数 + 最近状态时间 合并为 1 次往返） ──
        // 注意：latestcreatedat 代表当前状态的实时事实，不受「截至昨天」窗口约束（见 Repository 注释）
        VenueStatusLogRepository.SuspensionStats statusStats =
                venueStatusLogRepository.countSuspensionsAndLatestTime(venueId, since30d, windowEnd);
        long suspensionCount30d = statusStats.getSuspensioncount();
        long currentStatusDays = computeCurrentStatusDays(statusStats.getLatestcreatedat());

        // ── 用户实时状态报告（独立信号层，TTL 窗口，实时事实不受"截至昨日"约束） ──
        ActiveReportSummary reportSummary = statusReportService.getActiveReportSummary(venueId);

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

        // ── 状态可信度（二维矩阵 + 活跃报告 override） ──
        StatusConfidence confidence = computeStatusConfidence(
                suspensionCount30d, currentStatusDays, reportSummary.activeCount() > 0);

        return new VenueHeatResponse(
                heatScore,
                viewCount30d, viewUv30d,
                favoriteCount, newFavoriteCount30d, favoriteTrend,
                postCount, newPostCount30d,
                ratingCount30d, likeCount30d,
                satisfactionScore, ratingTotalCount,
                suspensionCount30d, currentStatusDays,
                venue.getStatus().name(), venue.getStatus().getDisplayName(),
                confidence.name(),
                reportSummary.activeCount(),
                reportSummary.latestReportTime(),
                statsAsOfDate.toString()
        );
    }

    /**
     * 计算近 {@link #TREND_WINDOW_DAYS} 天每日新增收藏数（含 asOfDate 当天，即截至昨天），
     * 缺失的日期补零，保证图表时间轴连续。
     */
    private List<FavoriteTrendPoint> computeFavoriteTrend(Long venueId, LocalDate asOfDate) {
        LocalDate sinceDate = asOfDate.minusDays(TREND_WINDOW_DAYS - 1);
        LocalDateTime sinceDateTime = sinceDate.atStartOfDay();
        LocalDateTime untilDateTime = asOfDate.plusDays(1).atStartOfDay();
        Map<LocalDate, Long> countByDay = new HashMap<>();
        for (FavoriteRepository.DailyFavoriteCount row :
                favoriteRepository.countDailyFavoritesSince(venueId, sinceDateTime, untilDateTime)) {
            countByDay.put(row.getDay(), row.getCount());
        }
        List<FavoriteTrendPoint> points = new ArrayList<>(TREND_WINDOW_DAYS);
        for (int i = 0; i < TREND_WINDOW_DAYS; i++) {
            LocalDate day = sinceDate.plusDays(i);
            points.add(new FavoriteTrendPoint(day.toString(), countByDay.getOrDefault(day, 0L)));
        }
        return points;
    }

    /**
     * 计算综合满意度：近30天各维度评分的等权均分。
     * 评价人数不足 MIN_RATING_SAMPLE 时返回 null（前端展示"暂无足够评价"）。
     */
    private Double computeSatisfaction(Long venueId, LocalDateTime since, LocalDateTime until, long totalRaters) {
        if (totalRaters < MIN_RATING_SAMPLE) {
            return null;
        }
        List<Object[]> scores = tagInteractionRepository.aggregateScoresByVenueSinceGroupByTag(venueId, since, until);
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

    /** 从合并查询结果中提取当前状态持续天数（实时事实，不受统计窗口约束） */
    private long computeCurrentStatusDays(LocalDateTime latestCreatedAt) {
        if (latestCreatedAt == null) {
            return 0L;
        }
        return ChronoUnit.DAYS.between(latestCreatedAt.toLocalDate(), LocalDate.now());
    }

    /**
     * 状态可信度：活跃报告 override + 二维矩阵。
     * <p>
     * 第一优先级：有活跃用户报告（TTL 内）→ 恒为 LOW。众包实时信号的说明力高于历史统计——
     * 有用户在现场报告"关了"，这比"30天内管理员改过N次状态"更能说明当前问题。
     * <p>
     * 二维矩阵（无活跃报告时）：
     * <pre>
     *                    稳定（30d 内 0 次暂停）    不稳定（30d 内 ≥1 次暂停）
     * 状态持续 ≤ 7天      HIGH（近期确认）          MEDIUM（状态多变）
     * 状态持续 > 7天      HIGH（稳定营业）          LOW（需确认 / 数据可能过时）
     * </pre>
     * 核心洞察：稳定门店无论多久没改状态，"营业中"就是可信的——不更新≠不准确。
     */
    private StatusConfidence computeStatusConfidence(long suspensionCount30d, long currentStatusDays,
                                                       boolean hasActiveReports) {
        if (hasActiveReports) {
            return StatusConfidence.LOW;
        }
        if (suspensionCount30d == 0) {
            return StatusConfidence.HIGH;
        }
        return currentStatusDays <= CONFIDENCE_RECENT_DAYS ? StatusConfidence.MEDIUM : StatusConfidence.LOW;
    }
}
