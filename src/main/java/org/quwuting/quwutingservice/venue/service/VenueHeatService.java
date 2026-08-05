package org.quwuting.quwutingservice.venue.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.taginteraction.RatingDimensions;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.dto.response.FavoriteTrendPoint;
import org.quwuting.quwutingservice.venue.dto.response.VenueHeatResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.StatusConfidence;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuereaction.ReactionCode;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.ActiveReportSummary;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 场所热度计算服务（多维度聚合）。
 * <p>
 * 热度指数 = 浏览量 × W_VIEW + 收藏总数 × W_FAVORITE + 近期新增收藏 × W_NEW_FAVORITE
 *           + 动态总数 × W_POST + 近期评价数 × W_RATING + 近30天正向 Reaction × W_REACTION
 *           + 满意度偏移 × W_SATISFACTION（无评分时为 0）。
 * <p>
 * 2026-08 缺陷修复确立的语义（见 AGENTS.md「场所热度」章节）：
 * <ul>
 *   <li><b>Reaction 分极性</b>：仅 {@link ReactionCode.Polarity#POSITIVE} 的 code（人气旺/氛围好等）
 *       计入热度；NEGATIVE（服务问题/排队太久等）不计入公式，单独以 negativeReactionCount30d
 *       下发供详情页展示负面信号——修复"被吐槽的店热度反而更高"的语义硬伤。</li>
 *   <li><b>满意度中性偏移</b>：满意度贡献 = (满意度 − 6) × 20，6 分（及格线）为中性基准，
 *       高于 6 加分、低于 6 扣分——低分店热度真实下降，口碑差不再"靠收藏/浏览撑高"。</li>
 *   <li><b>评分计数按 created_at</b>：ratingCount30d 与满意度窗口均按评分创建时间统计，
 *       改分不刷新窗口，防"定期改分保持计数常青"的刷分漏洞。</li>
 *   <li><b>公式文案后端下发</b>：formulaText / formulaDetail 由本服务生成（权重唯一事实源），
 *       前端直接渲染——消灭前端硬编码权重的展示失真风险。</li>
 * </ul>
 * 权重常量收敛在本类内部，后续基于真实数据分布调优，接口契约不变。
 * <p>
 * DB 往返压缩（两轮优化的最终形态）：
 * <ol>
 *   <li>第一轮（条件聚合）：同表多指标合并，14 次 → 6 次；</li>
 *   <li>第二轮（跨表 mega-query，{@link VenueRepository#countHeatCounters}）：
 *       6 张表的全部单值计数器收敛为一条标量子查询 SELECT，回源仅
 *       mega-query(1) + 收藏趋势(1) + 满意度(0~2，raters 不足时跳过) ≈ 2~4 次往返。</li>
 * </ol>
 * <p>
 * 缓存策略（refresh-ahead，2026-08 确立）：内嵌 Caffeine {@link LoadingCache}，
 * 不走 Spring CacheManager——refreshAfterWrite 要求 LoadingCache（构建时提供 loader），
 * Spring 缓存抽象无法为单个缓存注入各自的加载器，故采用与 AuthInterceptor 用户缓存
 * 相同的"服务内嵌原生 Caffeine"模式：
 * <ul>
 *   <li>{@code refreshAfterWrite(60s)}：条目写入 60s 后，下一次访问<b>立即返回旧值</b>并
 *       在后台异步重载——活跃场所的用户永不周期性吃到同步冷加载（早期
 *       expireAfterWrite(60s) 硬过期导致每 60 秒出现一个 2s+ 慢请求）；</li>
 *   <li>{@code expireAfterWrite(30min)}：仅长期无访问的条目被驱逐，下一访问者承担一次冷加载；</li>
 *   <li>单飞（single-flight）：同 key 并发回源只执行一次加载，详情页并发请求天然去重；</li>
 *   <li>异步刷新失败保留旧值：瞬态 DB 抖动降级为数据滞后而非请求失败；</li>
 *   <li>新鲜度主保障是写路径显式 {@link #invalidate}（点赞/评分、收藏、动态、状态上报、
 *       场所编辑），refresh/expire 仅作兜底。被缓存值与请求者身份无关（热度为公共聚合），
 *       后台刷新线程无请求上下文是安全的。</li>
 * </ul>
 */
@Service
public class VenueHeatService {

    // ── 热度权重常量 ──
    private static final long WEIGHT_VIEW = 1;
    private static final long WEIGHT_FAVORITE = 10;
    private static final long WEIGHT_NEW_FAVORITE = 15;
    private static final long WEIGHT_POST = 5;
    private static final long WEIGHT_RATING = 8;
    /** 正向 Reaction 权重（仅 Polarity.POSITIVE 计入；负向不计入公式，单独展示） */
    private static final long WEIGHT_REACTION = 3;
    private static final long WEIGHT_SATISFACTION = 20;

    /** 满意度中性基准（1-10 分制及格线）：高于 6 加分、低于 6 扣分——低分店热度真实下降 */
    private static final double SATISFACTION_NEUTRAL = 6.0;

    /** 正向 Reaction code 列表（mega-query 参数，恒非空） */
    private static final List<String> POSITIVE_REACTION_CODES = positiveCodes();
    /** 负向 Reaction code 列表（mega-query 参数，恒非空） */
    private static final List<String> NEGATIVE_REACTION_CODES = negativeCodes();

    private static List<String> positiveCodes() {
        return java.util.Arrays.stream(ReactionCode.values())
                .filter(c -> c.getPolarity() == ReactionCode.Polarity.POSITIVE)
                .map(Enum::name)
                .toList();
    }

    private static List<String> negativeCodes() {
        return java.util.Arrays.stream(ReactionCode.values())
                .filter(c -> c.getPolarity() == ReactionCode.Polarity.NEGATIVE)
                .map(Enum::name)
                .toList();
    }

    /** 满意度最低样本量：评价人数不足此值时不展示具体分数（同时跳过满意度查询） */
    private static final long MIN_RATING_SAMPLE = 3;

    /** 时间窗口：30 天 */
    private static final int WINDOW_DAYS = 30;

    /** 收藏趋势图窗口：14 天——比 30 天更适合小程序小屏图表的柱状数量，且足以看出升降走势 */
    private static final int TREND_WINDOW_DAYS = 14;

    /** 状态可信度矩阵阈值：不稳定门店状态持续天数 ≤ 此值视为"近期确认过"（MEDIUM），> 此值为 LOW */
    private static final long CONFIDENCE_RECENT_DAYS = 7;

    /** 缓存预刷新周期：60s 后访问返回旧值并触发异步重载 */
    private static final long CACHE_REFRESH_SECONDS = 60;

    /** 缓存硬过期：30 分钟无访问才驱逐 */
    private static final long CACHE_EXPIRE_MINUTES = 30;

    private final VenueLookupService venueLookupService;
    private final VenueRepository venueRepository;
    private final FavoriteRepository favoriteRepository;
    private final TagInteractionRepository tagInteractionRepository;
    private final LoadingCache<Long, VenueHeatResponse> heatCache;

    public VenueHeatService(VenueLookupService venueLookupService,
                            VenueRepository venueRepository,
                            FavoriteRepository favoriteRepository,
                            TagInteractionRepository tagInteractionRepository) {
        this.venueLookupService = venueLookupService;
        this.venueRepository = venueRepository;
        this.favoriteRepository = favoriteRepository;
        this.tagInteractionRepository = tagInteractionRepository;
        // loader 为 computeHeat（实例方法引用）：字段必须先于缓存构建完成赋值。
        // 异步刷新默认运行在 ForkJoinPool.commonPool——热度计算不依赖请求上下文，安全。
        this.heatCache = Caffeine.newBuilder()
                .maximumSize(500)
                .refreshAfterWrite(CACHE_REFRESH_SECONDS, TimeUnit.SECONDS)
                .expireAfterWrite(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
                .build(this::computeHeat);
    }

    /**
     * 获取场所热度（缓存：单飞 + refresh-ahead，见类注释）。
     */
    public VenueHeatResponse getHeat(Long venueId) {
        return heatCache.get(venueId);
    }

    /**
     * 写路径显式失效：任何改变热度输入的写操作（点赞/评分、收藏增删、动态发布、
     * 状态上报/撤销、场所编辑）完成后必须调用，保证其他用户及时看到最新统计。
     * 与早期 @CacheEvict(CACHE_VENUE_HEAT) 注解同职责，因缓存载体改为内嵌
     * LoadingCache 而显式化。
     */
    public void invalidate(Long venueId) {
        heatCache.invalidate(venueId);
    }

    /**
     * 热度计算（缓存 loader，勿直接调用——经 {@link #getHeat} 走缓存）。
     * <p>
     * 各聚合查询在独立隐式只读事务中执行（统计读无跨语句原子性要求，
     * Postgres READ COMMITTED 下与原单事务行为等价）。
     */
    private VenueHeatResponse computeHeat(Long venueId) {
        Venue venue = venueLookupService.findById(venueId);

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
        // 活跃上报为实时 TTL 窗口（now - 4h），是实时事实，不受「截至昨日」约束
        LocalDateTime reportSince = LocalDateTime.now().minusHours(StatusReportService.ACTIVE_REPORT_TTL_HOURS);

        // ── 全部单值计数器：跨 6 张表合并为 1 次 DB 往返（标量子查询 mega-query） ──
        VenueRepository.HeatCounters counters = venueRepository.countHeatCounters(
                venueId, sinceDate30d, today, since30d, windowEnd, reportSince,
                POSITIVE_REACTION_CODES, NEGATIVE_REACTION_CODES);
        long viewCount30d = orZero(counters.getPv());
        long viewUv30d = orZero(counters.getUv());
        long favoriteCount = orZero(counters.getFavtotal());
        long newFavoriteCount30d = orZero(counters.getFavrecent());
        long postCount = orZero(counters.getPosttotal());
        long newPostCount30d = orZero(counters.getPostrecent());
        long ratingCount30d = orZero(counters.getRatingcount30d());
        long positiveReactionCount30d = orZero(counters.getPositivereactioncount30d());
        long negativeReactionCount30d = orZero(counters.getNegativereactioncount30d());
        long ratingTotalCount = orZero(counters.getRaters());
        long suspensionCount30d = orZero(counters.getSuspensioncount());
        long currentStatusDays = computeCurrentStatusDays(counters.getLateststatuslogtime());
        ActiveReportSummary reportSummary = new ActiveReportSummary(
                (int) orZero(counters.getReportcount()),
                counters.getLatestreporttime());

        // ── 收藏趋势（多行时间序列，独立 1 次往返，近14天每日新增，截至昨天） ──
        List<FavoriteTrendPoint> favoriteTrend = computeFavoriteTrend(venueId, statsAsOfDate);

        // ── 满意度（各维度等权均分，近30天窗口；raters 不足样本量时直接跳过查询） ──
        Double satisfactionScore = computeSatisfaction(venueId, since30d, windowEnd, ratingTotalCount);

        // ── 综合热度指数（满意度为中性偏移：(分−6)×20，低于 6 分扣分） ──
        double satisfactionOffset = satisfactionScore != null ? satisfactionScore - SATISFACTION_NEUTRAL : 0;
        long satisfactionComponent = satisfactionScore != null
                ? Math.round(satisfactionOffset * WEIGHT_SATISFACTION)
                : 0;
        long heatScore = viewCount30d * WEIGHT_VIEW
                + favoriteCount * WEIGHT_FAVORITE
                + newFavoriteCount30d * WEIGHT_NEW_FAVORITE
                + postCount * WEIGHT_POST
                + ratingCount30d * WEIGHT_RATING
                + positiveReactionCount30d * WEIGHT_REACTION
                + satisfactionComponent;

        // ── 公式文案（权重唯一事实源在后端，前端直接渲染，禁止前端硬编码权重） ──
        String satisfactionTerm = satisfactionScore != null
                ? String.format(" + %.1f×20", satisfactionOffset)
                : "";
        String formulaText = heatScore + " = " + viewCount30d + "×1 + " + favoriteCount + "×10 + "
                + newFavoriteCount30d + "×15 + " + postCount + "×5 + " + ratingCount30d + "×8 + "
                + positiveReactionCount30d + "×3" + satisfactionTerm;
        String formulaDetail = "热度公式：浏览量(" + viewCount30d + ")×1 + 收藏总数(" + favoriteCount + ")×10"
                + " + 近30天新增收藏(" + newFavoriteCount30d + ")×15 + 动态总数(" + postCount + ")×5"
                + " + 近30天评分数(" + ratingCount30d + ")×8 + 近30天正向反馈(" + positiveReactionCount30d + ")×3"
                + (satisfactionScore != null
                        ? String.format(" + 满意度偏移(%.1f)×20", satisfactionOffset)
                        : "")
                + "。满意度为各体验维度等权均分（满分10），6分为中性基准、高于6加分低于6减分；"
                + (satisfactionScore != null
                        ? "当前满意度" + satisfactionScore + "分。"
                        : "当前评价人数不足3人，满意度不参与计算。")
                + "负向反馈（如服务问题、排队太久）不计入热度，单独展示。";

        // ── 状态可信度（二维矩阵 + 活跃报告 override） ──
        StatusConfidence confidence = computeStatusConfidence(
                suspensionCount30d, currentStatusDays, reportSummary.activeCount() > 0);

        return new VenueHeatResponse(
                heatScore,
                viewCount30d, viewUv30d,
                favoriteCount, newFavoriteCount30d, favoriteTrend,
                postCount, newPostCount30d,
                ratingCount30d, positiveReactionCount30d, negativeReactionCount30d,
                satisfactionScore, ratingTotalCount,
                suspensionCount30d, currentStatusDays,
                venue.getStatus().name(), venue.getStatus().getDisplayName(),
                confidence.name(),
                reportSummary.activeCount(),
                reportSummary.latestReportTime(),
                formulaText, formulaDetail,
                statsAsOfDate.toString()
        );
    }

    private static long orZero(Long value) {
        return value != null ? value : 0L;
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
     * 评价人数不足 MIN_RATING_SAMPLE 时返回 null（前端展示"暂无足够评价"），
     * 且完全不发起满意度查询——冷启动场景下大部分场所样本不足，省掉 1~2 次 DB 往返。
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
            // 只统计当前合法的评分维度——防御历史 legacy 数据（原"现场状况"三维度已被 Reaction 替代，
            // 但历史行仍可能存在于表中，见 RatingDimensions 类注释）
            if (!RatingDimensions.isValid(tag)) {
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
