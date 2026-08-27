package org.quwuting.quwutingservice.venue.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.quwuting.quwutingservice.config.VenueHeatWeights;
import org.quwuting.quwutingservice.taginteraction.RatingDimensions;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.dto.response.FavoriteTrendPoint;
import org.quwuting.quwutingservice.venue.dto.response.ReactionTrendPoint;
import org.quwuting.quwutingservice.venue.dto.response.VenueHeatResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.StatusConfidence;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuereaction.ReactionCode;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.ActiveReportSummary;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 场所热度计算服务（多维度聚合）。
 * <p>
 * 热度指数 = 浏览贡献（ln(1+加权浏览)，来源加权列表0.5/其他1/搜索1.5/分享2 + 近7天×2，
 *           2026-08-27 重构——原线性 PV×1 被马太反馈循环放大，见 VenueHeatWeights）
 *           + 收藏总数 × W_FAVORITE + 近期新增收藏 × W_NEW_FAVORITE
 *           + 动态总数 × W_POST + 近期评价数 × W_RATING + 近30天正向 Reaction × W_REACTION
 *           + 满意度偏移 × W_SATISFACTION（无评分时为 0）。
 * <p>
 * 2026-08 缺陷修复确立的语义（见 AGENTS.md「场所热度」章节）：
 * <ul>
 *   <li><b>Reaction 分极性</b>：仅 {@link ReactionCode.Polarity#POSITIVE} 的 code（人气旺/氛围好等）
 *       计入热度；NEGATIVE（服务问题/排队太久等）不计入公式，单独以 negativeReactionCount30d
 *       下发供详情页展示负面信号——修复"被吐槽的店热度反而更高"的语义硬伤。
 *       极性 code 列表唯一事实源 = {@link ReactionCode#positiveCodeNames()}，禁止各调用方自行 filter。</li>
 *   <li><b>满意度中性偏移</b>：满意度贡献 = (满意度 − 6) × 20，6 分（及格线）为中性基准，
 *       高于 6 加分、低于 6 扣分——低分店热度真实下降，口碑差不再"靠收藏/浏览撑高"。</li>
 *   <li><b>非负收敛（2026-08-08）</b>：满意度负偏移可能把总分拉负，热度指数语义非负，
 *       clamp 到 0（前端详情页 chip 以 heatScore &gt; 0 为"有数据"判据，负分导致两端
 *       展示矛盾——详情页隐藏、热度页显示负数的根因）。公式文案标注「按0计」。</li>
 *   <li><b>评分计数按 created_at</b>：ratingCount30d 与满意度窗口均按评分创建时间统计，
 *       改分不刷新窗口，防"定期改分保持计数常青"的刷分漏洞。</li>
 *   <li><b>公式文案后端下发</b>：formulaText / formulaDetail 由本服务生成（权重唯一事实源），
 *       前端直接渲染——消灭前端硬编码权重的展示失真风险。</li>
 * </ul>
 * 权重常量收敛在本类内部，后续基于真实数据分布调优，接口契约不变。
 * <p>
 * DB 往返压缩（三轮优化的最终形态）：
 * <ol>
 *   <li>第一轮（条件聚合）：同表多指标合并，14 次 → 6 次；</li>
 *   <li>第二轮（跨表 mega-query，{@link VenueRepository#countHeatCounters}）：
 *       6 张表的全部单值计数器收敛为一条标量子查询 SELECT；</li>
 *   <li>第三轮（趋势 mega-query，{@link VenueRepository#countDailyTrends}，2026-08-08）：
 *       收藏/浏览/正负向 Reaction 四组按天时间序列合并为一条 generate_series + LEFT JOIN
 *       SELECT——若各趋势图一条查询会把往返从 2~4 次膨胀到 5~7 次（见「最少往返」约束）。</li>
 * </ol>
 * 回源 = mega-query(1) + 趋势(1) + 满意度(0~2，raters 不足时跳过) ≈ 2~4 次往返。
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

    // ── 热度权重常量（唯一事实源 = VenueHeatWeights，2026-08-10 V2 权重收敛） ──
    // 非配置化权重收敛到 VenueHeatWeights，三处镜像（本类 / VenueRepository.HEAT_SCORE /
    // findHotVenueIds）引用同一常量；积分权重是运营可调参数走 PointsProperties
    // （V2 三阶段校准机制，禁止硬编码——见 AGENTS.md「积分系统 · 排名权重校准」）。

    /** 正向 Reaction code 列表（mega-query / 趋势查询参数，恒非空；唯一事实源 = ReactionCode） */
    private static final List<String> POSITIVE_REACTION_CODES = ReactionCode.positiveCodeNames();
    /** 负向 Reaction code 列表（mega-query / 趋势查询参数，恒非空；唯一事实源 = ReactionCode） */
    private static final List<String> NEGATIVE_REACTION_CODES = ReactionCode.negativeCodeNames();

    /** 满意度最低样本量：评价人数不足此值时不展示具体分数（同时跳过满意度查询） */
    private static final long MIN_RATING_SAMPLE = 3;

    /** 时间窗口：30 天 */
    private static final int WINDOW_DAYS = 30;

    /**
     * 趋势图窗口：30 天（2026-08-08 由 14 天扩展，与其余滚动指标一致）。
     * 根因：时间范围刷选控件（略缩图）需要足够长的全量窗口才有"缩放"意义——
     * 全量=趋势窗口，默认选中最近 14 天，用户可放大到全量或缩小到 7 天；
     * 14 天全量无法表达"拉近看 7 天"的语义。
     * 2026-08-13 实时化：骨架 = [今天-30, 今天] 共 31 个自然日（含今日），
     * 该常量语义为"近30天 + 今日"，数值不变（窗口起点仍往前推 30 天）。
     */
    private static final int TREND_WINDOW_DAYS = 30;

    /** 热度指数非负下界：满意度负偏移可能把总分拉负，负热度无展示语义（clamp 到 0） */
    private static final long HEAT_SCORE_FLOOR = 0L;

    /** 状态可信度矩阵阈值：不稳定门店状态持续天数 ≤ 此值视为"近期确认过"（MEDIUM），> 此值为 LOW */
    private static final long CONFIDENCE_RECENT_DAYS = 7;

    /** 缓存预刷新周期：60s 后访问返回旧值并触发异步重载 */
    private static final long CACHE_REFRESH_SECONDS = 60;

    /** 缓存硬过期：30 分钟无访问才驱逐 */
    private static final long CACHE_EXPIRE_MINUTES = 30;

    private final VenueLookupService venueLookupService;
    private final VenueRepository venueRepository;
    private final TagInteractionRepository tagInteractionRepository;
    private final org.quwuting.quwutingservice.config.PointsProperties pointsProperties;
    /**
     * 积分聚合查询入口（礼物墙数据源）。@Lazy 打破与 PointsService 的构造器循环
     * 依赖（PointsService 依赖本服务做赠送后热度失效；本服务仅在 computeHeat 回源
     * 时读积分聚合，延迟解析安全——循环依赖根因见后端 AGENTS.md「场所热度」）。
     */
    private final org.quwuting.quwutingservice.points.service.PointsService pointsService;
    private final LoadingCache<Long, VenueHeatResponse> heatCache;

    public VenueHeatService(VenueLookupService venueLookupService,
                            VenueRepository venueRepository,
                            TagInteractionRepository tagInteractionRepository,
                            org.quwuting.quwutingservice.config.PointsProperties pointsProperties,
                            @org.springframework.context.annotation.Lazy
                            org.quwuting.quwutingservice.points.service.PointsService pointsService) {
        this.venueLookupService = venueLookupService;
        this.venueRepository = venueRepository;
        this.tagInteractionRepository = tagInteractionRepository;
        this.pointsProperties = pointsProperties;
        this.pointsService = pointsService;
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

        // 统计口径（2026-08-13 由「截至昨日」改为实时）：所有滚动窗口（近30天）的排他
        // 上界统一为请求时刻 now——含今日已发生的数据（用户需求：统计图实时）。
        // 根因与权衡：旧口径锚定「昨天 24 点」是防"当天半天数据与整天数据混在同一窗口
        // 聚合对比造成最新一天被系统性拉低"（2026-07-31 确立）；实时化后今日数据为
        // "未走完的一天"，同一日内多次请求统计结果会随请求时刻漂移，这是实时性的
        // 必然代价，由前端 banner「数据实时更新 · 含今日」显性承担口径说明。
        // 骨架锚点仍按自然日对齐：窗口起始 = 今天 0 点 - 30 天（与 [今天-30, 今天]
        // 的 31 天骨架严格对应），上界 = now（今天已发生的部分计入今日）。
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        LocalDate statsAsOfDate = today;
        LocalDateTime windowSince = today.atStartOfDay().minusDays(WINDOW_DAYS);
        LocalDateTime windowUntil = now;
        LocalDate sinceDate30d = today.minusDays(WINDOW_DAYS);
        // views 按 view_date（DATE 列）过滤：上界 = 明天 0 点，覆盖今日全天
        LocalDate viewUntil = today.plusDays(1);
        // 活跃上报为实时 TTL 窗口（expires_at > now，TTL 唯一事实源 = 列），是实时事实，
        // 不受滚动窗口约束

        // ── 全部单值计数器：跨 6 张表合并为 1 次 DB 往返（标量子查询 mega-query） ──
        VenueRepository.HeatCounters counters = venueRepository.countHeatCounters(
                venueId, sinceDate30d, viewUntil, windowSince, windowUntil, now,
                POSITIVE_REACTION_CODES, NEGATIVE_REACTION_CODES);
        long viewCount30d = orZero(counters.getPv());
        long viewUv30d = orZero(counters.getUv());
        // 加权浏览贡献输入（2026-08-27）：来源质量加权 + 近7天时效因子的 30 天合计，
        // 热度公式浏览项 = round(ln(1 + 本值))——线性 PV 计数被重构，见 VenueHeatWeights
        // 浏览贡献注释（马太效应反馈循环修复）。viewCount30d（原始 PV）仅作展示用。
        double weightedViews30d = orZeroDouble(counters.getWeightedviews30d());
        long viewComponent = Math.round(Math.log1p(weightedViews30d));
        long favoriteCount = orZero(counters.getFavtotal());
        long newFavoriteCount30d = orZero(counters.getFavrecent());
        long postCount = orZero(counters.getPosttotal());
        long newPostCount30d = orZero(counters.getPostrecent());
        long ratingCount30d = orZero(counters.getRatingcount30d());
        long positiveReactionCount30d = orZero(counters.getPositivereactioncount30d());
        long negativeReactionCount30d = orZero(counters.getNegativereactioncount30d());
        long pointsReceivedTotal = orZero(counters.getPointsreceivedtotal());
        long pointsReceived30d = orZero(counters.getPointsreceived30d());
        long ratingTotalCount = orZero(counters.getRaters());
        long suspensionCount30d = orZero(counters.getSuspensioncount());
        long currentStatusDays = computeCurrentStatusDays(counters.getLateststatuslogtime());
        ActiveReportSummary reportSummary = new ActiveReportSummary(
                (int) orZero(counters.getReportcount()),
                counters.getLatestreporttime());

        // ── 趋势（多行时间序列，独立 1 次往返：收藏（含取消收藏）/浏览（含来源分列）/
        //    正负向 Reaction/收到积分八序列合一，近30天+今日、缺失日补零——
        //    见 VenueRepository.countDailyTrends） ──
        List<FavoriteTrendPoint> favoriteTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<FavoriteTrendPoint> unfavoriteTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<FavoriteTrendPoint> viewTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<org.quwuting.quwutingservice.venue.dto.response.ViewSourceTrendPoint> viewSourceTrend =
                new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<ReactionTrendPoint> reactionTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<FavoriteTrendPoint> pointsTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        for (VenueRepository.DailyTrendRow row : venueRepository.countDailyTrends(
                venueId, sinceDate30d, statsAsOfDate, sinceDate30d, viewUntil,
                windowSince, windowUntil, POSITIVE_REACTION_CODES, NEGATIVE_REACTION_CODES)) {
            String day = row.getDay().toString();
            favoriteTrend.add(new FavoriteTrendPoint(day, orZero(row.getFavcount())));
            // 取消收藏趋势（V19：unfavorited_at 非空行按日分组；与新增收藏同骨架同窗口）
            unfavoriteTrend.add(new FavoriteTrendPoint(day, orZero(row.getUnfavcount())));
            viewTrend.add(new FavoriteTrendPoint(day, orZero(row.getViewcount())));
            viewSourceTrend.add(new org.quwuting.quwutingservice.venue.dto.response.ViewSourceTrendPoint(
                    day, orZero(row.getViewlistcount()), orZero(row.getViewsharecount()),
                    // search = 列表页搜索结果进入（2026-08-13 晚新增第三折线）
                    orZero(row.getViewsearchcount()),
                    // other = 全量 - list - share - search（同源口径交叉校验；直接 COUNT 亦可，减法省一次扫描）
                    Math.max(0L, orZero(row.getViewcount()) - orZero(row.getViewlistcount())
                            - orZero(row.getViewsharecount()) - orZero(row.getViewsearchcount()))));
            reactionTrend.add(new ReactionTrendPoint(day, orZero(row.getPosreaction()), orZero(row.getNegreaction())));
            pointsTrend.add(new FavoriteTrendPoint(day, orZero(row.getPoints())));
        }

        // ── 满意度（各维度等权均分，近30天窗口；raters 不足样本量时直接跳过查询） ──
        Double satisfactionScore = computeSatisfaction(venueId, windowSince, windowUntil, ratingTotalCount);

        // ── 综合热度指数（满意度为中性偏移：(分−6)×20，低于 6 分扣分；
        //    积分权重来自 PointsProperties——运营可调，V2 三阶段校准机制） ──
        double satisfactionOffset = satisfactionScore != null ? satisfactionScore - VenueHeatWeights.SATISFACTION_NEUTRAL : 0;
        long satisfactionComponent = satisfactionScore != null
                ? Math.round(satisfactionOffset * VenueHeatWeights.SATISFACTION)
                : 0;
        long pointsWeight = pointsProperties.heatWeight();
        long rawHeatScore = viewComponent
                + favoriteCount * VenueHeatWeights.FAVORITE
                + newFavoriteCount30d * VenueHeatWeights.NEW_FAVORITE
                + postCount * VenueHeatWeights.POST
                + ratingCount30d * VenueHeatWeights.RATING
                + positiveReactionCount30d * VenueHeatWeights.REACTION
                + pointsReceived30d * pointsWeight
                + satisfactionComponent;
        // 非负收敛：满意度负偏移可能把总分拉负——热度指数语义非负（负热度无展示意义，
        // 前端详情页 chip 以 heatScore > 0 为"有数据"判据，负分会导致两端展示矛盾，
        // 见后端 AGENTS.md「场所热度 → 非负收敛」），clamp 到 0。
        boolean heatClamped = rawHeatScore < HEAT_SCORE_FLOOR;
        long heatScore = Math.max(HEAT_SCORE_FLOOR, rawHeatScore);

        // ── 公式文案（权重唯一事实源在后端，前端直接渲染，禁止前端硬编码权重） ──
        // 2026-08-27 文案重构（用户反馈：技术符号堆叠看不懂）：
        //   formulaText  = 默认展示的「中等简洁规则」——人话 + 当前各分项数据，
        //                  无 ln/加权符号；前端默认展示，问号入口可收起的完整版在 formulaDetail。
        //   formulaDetail = 点击展开的「完整规则」——条目化分项说明，保留规则细节。
        String weightedViewsText = String.format("%.1f", weightedViews30d);
        String satisfactionTerm = satisfactionScore != null
                ? String.format(" · 满意度偏移 %.1f×20", satisfactionOffset)
                : "";
        String clampSuffix = heatClamped ? "（满意度负偏移按0计）" : "";
        String formulaText = "热度 " + heatScore + " = 近30天人气：浏览贡献 " + viewComponent
                + "（来源加权+近7天翻倍）· 收藏 " + favoriteCount + "×" + VenueHeatWeights.FAVORITE
                + " · 新增收藏 " + newFavoriteCount30d + "×" + VenueHeatWeights.NEW_FAVORITE
                + " · 动态 " + postCount + "×" + VenueHeatWeights.POST
                + " · 评分 " + ratingCount30d + "×" + VenueHeatWeights.RATING
                + " · 正向反馈 " + positiveReactionCount30d + "×" + VenueHeatWeights.REACTION
                + " · 礼物 " + pointsReceived30d + "×" + pointsWeight
                + satisfactionTerm + clampSuffix;

        StringBuilder detail = new StringBuilder();
        detail.append("完整计算规则（近30天）：\n");
        detail.append("· 浏览贡献 ").append(viewComponent).append(" 分：按来源加权（列表×0.5 / 其他×1 / 搜索×1.5 / 分享×2）、近7天翻倍，再压缩取整（加权 ")
                .append(weightedViewsText).append(" → ").append(viewComponent)
                .append(" 分）——降低“排得靠前→看的人多”造成的热度虚高，更反映主动兴趣\n");
        detail.append("· 收藏总数×").append(VenueHeatWeights.FAVORITE).append("：当前 ").append(favoriteCount).append(" 次\n");
        detail.append("· 近30天新增收藏×").append(VenueHeatWeights.NEW_FAVORITE).append("：当前 ").append(newFavoriteCount30d).append(" 次\n");
        detail.append("· 动态总数×").append(VenueHeatWeights.POST).append("：当前 ").append(postCount).append(" 条\n");
        detail.append("· 近30天评分数×").append(VenueHeatWeights.RATING).append("：当前 ").append(ratingCount30d).append(" 条\n");
        detail.append("· 近30天正向反馈×").append(VenueHeatWeights.REACTION).append("：当前 ").append(positiveReactionCount30d)
                .append(" 条（服务问题、排队太久等负向反馈不计入，单独展示）\n");
        detail.append("· 近30天收到礼物价值×").append(pointsWeight).append("：当前 ").append(pointsReceived30d).append(" 分\n");
        detail.append(satisfactionScore != null
                ? "当前满意度 " + satisfactionScore + " 分。"
                : "当前评价人数不足3人，满意度不参与计算。");
        detail.append("满意度为各体验维度等权均分（满分10），6分为中性基准、高于6加分、低于6减分。\n");
        detail.append("积分权重为运营可调参数（当前×").append(pointsWeight).append("）。");
        if (heatClamped) {
            detail.append("满意度负偏移使总分低于0，站内热度按0计（不出现负热度）。");
        }
        String formulaDetail = detail.toString();

        // ── 状态可信度（三维判定：状态类型 × 稳定性 × 持续天数；活跃报告 override） ──
        StatusConfidenceResult confidence = computeStatusConfidence(
                venue.getStatus(), suspensionCount30d, currentStatusDays, reportSummary.activeCount());

        // ── 收到礼物聚合（2026-08-12 礼物化：「收获的支持」礼物墙数据源；
        //     @Lazy 注入 PointsService 打破循环依赖，见构造器注释） ──
        java.util.List<org.quwuting.quwutingservice.points.dto.GiftCountResponse> giftsReceived =
                pointsService.receivedGifts(org.quwuting.quwutingservice.points.enums.PointsTargetType.VENUE, venueId);

        return new VenueHeatResponse(
                heatScore,
                viewCount30d, viewUv30d,
                favoriteCount, newFavoriteCount30d, favoriteTrend, unfavoriteTrend, viewTrend, viewSourceTrend, reactionTrend,
                postCount, newPostCount30d,
                ratingCount30d, positiveReactionCount30d, negativeReactionCount30d,
                pointsReceivedTotal, pointsReceived30d, pointsTrend, giftsReceived,
                satisfactionScore, ratingTotalCount,
                suspensionCount30d, currentStatusDays,
                venue.getStatus().name(), venue.getStatus().getDisplayName(),
                confidence.level().name(),
                confidence.text(),
                confidence.ruleDetail(),
                reportSummary.activeCount(),
                reportSummary.latestReportTime(),
                formulaText, formulaDetail,
                statsAsOfDate.toString()
        );
    }

    private static long orZero(Long value) {
        return value != null ? value : 0L;
    }

    /** 加权浏览输入可能为 null（无浏览记录时子查询返回 NULL）→ 按 0 计 */
    private static double orZeroDouble(Double value) {
        return value != null ? value : 0.0;
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
     * 状态可信度判定结果：等级 + 结论文案 + 判定依据文案。
     * <p>
     * 文案唯一事实源在后端（与热度公式 formulaText/formulaDetail 同模式）——前端只渲染、
     * 禁止前端硬编码可信度文案。2026-08-08 根因修复：旧实现等级在后端、文案在前端，
     * 前端把 HIGH 硬编码为「稳定营业」，导致"已停业（近30天暂停 0 次）→ HIGH → 稳定营业"
     * 的语义错配（寻梦缘123 生产实证）；文案收编到后端后，等级与文案由同一处按
     * 「等级 × 当前状态类型」同步生成，两端永远一致。
     */
    private record StatusConfidenceResult(StatusConfidence level, String text, String ruleDetail) {}

    /**
     * 状态可信度：活跃报告 override + 三维矩阵（状态类型 × 稳定性 × 持续天数）。
     * <p>
     * 第一优先级：有活跃用户报告（TTL 内）→ 恒为 LOW。众包实时信号的说明力高于历史统计——
     * 有用户在现场报告"关了/异常"，与展示状态不一致即"数据可能过时"（无论门店当前状态类型）。
     * <p>
     * 三维矩阵（无活跃报告时）。核心建模修正（2026-08-08 根因）：旧二维矩阵
     * 「稳定性 × 持续天数」隐含假设门店处于营业中——"稳定门店无论多久没改状态，营业中就是
     * 可信的（不更新≠不准确）"只对营业中成立；已停业/暂停等非营业门店近30天暂停 0 次是常态
     * （暂停=SUSPENDED 变迁，停业门店不会产生），旧矩阵因此把"长期停业"（本应是最强的停业
     * 证据）误判为 HIGH 却配上"稳定营业"文案。故判定必须按当前状态类型分治：
     * <pre>
     * 营业中（OPEN）：
     *   近30天 0 次暂停                → HIGH（稳定营业——不更新≠不准确）
     *   近30天 ≥1 次暂停 + 持续 ≤7天   → MEDIUM（状态多变，近期确认过）
     *   近30天 ≥1 次暂停 + 持续 >7天   → LOW（不稳定且久未确认，数据可能过时）
     *
     * 非营业（已停业/暂停营业/装修中/休息中）：
     *   状态持续 >7天                  → HIGH（状态可信——长期未被纠正/反向信号 = 被时间验证）
     *   状态持续 ≤7天                  → MEDIUM（建议确认——刚变更，未经时间验证，可能随时恢复或为误报）
     * </pre>
     * 非营业分支不依赖暂停次数：对非营业门店该指标无区分力（0 次是常态），决定可信度的是
     * 「该状态已稳定持续多久」与「有无反向实时信号（活跃报告 override）」。等级结论（text）
     * 与判定依据（ruleDetail）随状态类型区分生成，杜绝"已停业却显示稳定营业"类语义错配。
     */
    private StatusConfidenceResult computeStatusConfidence(VenueStatus status, long suspensionCount30d,
                                                           long currentStatusDays, int activeReportCount) {
        if (activeReportCount > 0) {
            return new StatusConfidenceResult(StatusConfidence.LOW, "数据可能过时",
                    "判定规则：近 4 小时有 " + activeReportCount + " 人报告暂停营业，与当前状态不一致，数据可能过时。");
        }
        if (status == VenueStatus.OPEN) {
            if (suspensionCount30d == 0) {
                return new StatusConfidenceResult(StatusConfidence.HIGH, "稳定营业",
                        "判定规则：近30天暂停 " + suspensionCount30d + " 次 = 稳定。稳定门店无论多久未更新状态，\"营业中\"即为可信——不更新不等于不准确。");
            }
            if (currentStatusDays <= CONFIDENCE_RECENT_DAYS) {
                return new StatusConfidenceResult(StatusConfidence.MEDIUM, "状态多变",
                        "判定规则：近30天暂停 " + suspensionCount30d + " 次 = 不稳定。不稳定门店状态持续 ≤7天为\"近期确认\"（建议关注），>7天为\"数据可能过时\"。当前属近期确认范围。");
            }
            return new StatusConfidenceResult(StatusConfidence.LOW, "数据可能过时",
                    "判定规则：近30天暂停 " + suspensionCount30d + " 次 = 不稳定。不稳定门店状态持续 >7天为\"数据可能过时\"。建议出发前电话确认或提交反馈。");
        }
        if (currentStatusDays > CONFIDENCE_RECENT_DAYS) {
            return new StatusConfidenceResult(StatusConfidence.HIGH, "状态可信",
                    "判定规则：当前状态（" + status.getDisplayName() + "）已持续 " + currentStatusDays
                            + " 天，长时间未被纠正或收到反向信号，该状态信息可信。");
        }
        return new StatusConfidenceResult(StatusConfidence.MEDIUM, "建议确认",
                "判定规则：当前状态（" + status.getDisplayName() + "）刚变更 " + currentStatusDays
                        + " 天，未经时间验证，建议出发前确认最新情况。");
    }
}
