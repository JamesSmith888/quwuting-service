package org.quwuting.quwutingservice.dancer.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancer.DancerTagCode;
import org.quwuting.quwutingservice.dancer.dto.response.DancerRecognitionStats;
import org.quwuting.quwutingservice.dancer.dto.response.DancerTagStat;
import org.quwuting.quwutingservice.dancer.dto.response.DancerVenueInfo;
import org.quwuting.quwutingservice.dancer.entity.DancerCity;
import org.quwuting.quwutingservice.dancer.enums.DancerVenueRelation;
import org.quwuting.quwutingservice.dancer.repository.DancerAdViewRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerCityRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionTagRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerVenueRepository;
import org.quwuting.quwutingservice.points.dto.GiftCountResponse;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;
import org.quwuting.quwutingservice.points.repository.PointsGateRepository;
import org.quwuting.quwutingservice.points.repository.PointsTransactionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 舞伴详情「用户无关公共部分」聚合缓存（2026-08-19 根因修复，性能核心）。
 * <p>
 * <b>根因</b>：DancerService#getDetail 原本对每个详情请求顺序执行约 15 次 DB 往返
 * （认可统计/每日认可/标签/场所/城市/收礼/收到积分×2/广告计数/门槛/解锁……），
 * Supabase 为跨洲远程库、单次往返 300~500ms，详情接口实测 3~7s 慢加载；
 * 其中绝大多数查询与「当前请求用户」无关（纯舞伴级聚合），却在每个请求重复执行。
 * <p>
 * <b>方案</b>（对齐项目既有 DancerAggregateService / DancerStatsService 的 refresh-ahead
 * 缓存范式）：把与用户无关的聚合整体打包为一个 Caffeine LoadingCache 条目——
 * <ul>
 *   <li>60s refresh-ahead + 30min 绝对过期 + 500 条上限（同 DancerStatsService）；</li>
 *   <li>新鲜度主保障是写路径显式 {@link #invalidate}（唯一失效入口，级联失效内层
 *       DancerAggregateService 与 DancerStatsService——详情缓存重算会回读它们的值，
 *       只失效外层会让内层 60s 陈旧值泄漏进详情）；</li>
 *   <li><b>用户相关状态不进缓存</b>：isMine / 今日认可态 / 收藏态 / 解锁态 / 相册
 *       （含按用户的照片解锁过滤）恒实时查询——缓存只承载舞伴级公共聚合。</li>
 * </ul>
 * 效果：60s 窗口内重复进详情，DB 往返从 ~15 次降到 ~6 次（舞伴主行 + 个人认可态 +
 * 收藏态 + 相册 + 照片门槛/解锁批量），Supabase 抖动影响面同步收窄。
 * <p>
 * <b>失效矩阵（写路径必须调用 {@link #invalidate}）</b>：认可 toggle / 收藏 add·remove /
 * 浏览记录 / 礼物赠送(DANCER) / 分享 SHARE / 资料编辑（城市子表）/ 联系方式门槛设置。
 * 照片增删审（不在缓存内）与状态流转（主表字段，不在缓存内）无需失效。
 */
@Service
@RequiredArgsConstructor
public class DancerDetailCacheService {

    /** 缓存刷新间隔：写入 60s 后下一次访问触发异步回源（refresh-ahead，同 DancerStatsService） */
    private static final long CACHE_REFRESH_SECONDS = 60;

    /** 缓存绝对过期：30 分钟（防长期无访问的陈旧条目常驻） */
    private static final long CACHE_EXPIRE_MINUTES = 30;

    /** 详情页"最近认可"动态信息展示的天数（含今日，同 DancerService.RECENT_DAILY_DAYS） */
    private static final int RECENT_DAILY_DAYS = 7;

    private final DancerAggregateService aggregateService;
    private final DancerStatsService dancerStatsService;
    private final DancerRecognitionRepository recognitionRepository;
    private final DancerRecognitionTagRepository recognitionTagRepository;
    private final DancerVenueRepository dancerVenueRepository;
    private final DancerCityRepository dancerCityRepository;
    private final DancerAdViewRepository adViewRepository;
    // 积分读侧直连仓库（不经 PointsService——避免「PointsService → 本服务 → PointsService」
    // 构造循环依赖；本服务是纯读侧聚合器，与 PointsService 的写路径通过 invalidate 解耦）
    private final PointsTransactionRepository pointsTransactionRepository;
    private final PointsGateRepository pointsGateRepository;

    /** 详情公共部分（用户无关聚合的一次快照；消费方只读，不共享可变状态） */
    public record PublicPart(
            DancerRecognitionStats stats,
            List<DancerTagStat> tags,
            List<DancerVenueInfo> venues,
            List<String> cities,
            List<GiftCountResponse> giftsReceived,
            long pointsReceivedTotal,
            long pointsReceived30d,
            int contactCost,
            long adViews
    ) {}

    private final LoadingCache<Long, PublicPart> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .refreshAfterWrite(CACHE_REFRESH_SECONDS, TimeUnit.SECONDS)
            .expireAfterWrite(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
            .build(this::compute);

    /** 获取详情公共部分（缓存：单飞 + refresh-ahead；首次 miss 全量聚合一次） */
    public PublicPart get(Long dancerId) {
        return cache.get(dancerId);
    }

    /**
     * 写路径显式失效（唯一入口）：级联失效内层聚合缓存——详情缓存重算（compute）会回读
     * {@link DancerAggregateService}（认可四窗口）与 {@link DancerStatsService}（统计页
     * 趋势）的值，若只清外层，60s 内重算仍会读到内层陈旧值（缓存一致性根因，勿拆散）。
     */
    public void invalidate(Long dancerId) {
        aggregateService.invalidate(dancerId);
        dancerStatsService.invalidate(dancerId);
        cache.invalidate(dancerId);
    }

    /** 聚合计算（缓存 loader，勿直接调用——经 {@link #get} 走缓存）。每个查询独立只读事务，
     *  refresh-ahead 异步回源场景亦安全（无事务上下文依赖）。 */
    private PublicPart compute(Long dancerId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sinceToday = LocalDate.now().atStartOfDay();
        LocalDateTime since7d = now.minusDays(7);
        LocalDateTime since30d = now.minusDays(30);
        // 统计口径与 DancerService.buildStats 完全一致：四窗口（aggregate 缓存）+ 近7日每日
        long[] agg = aggregateService.getAggregate(dancerId);
        DancerRecognitionStats stats = new DancerRecognitionStats(
                agg[0], agg[1], agg[2], agg[3], fetchRecentDaily(dancerId));
        List<DancerTagStat> tags = fetchTags(dancerId, sinceToday, since7d, since30d);
        List<DancerVenueInfo> venues = fetchVenues(dancerId);
        List<String> cities = dancerCityRepository
                .findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(dancerId)
                .stream().map(DancerCity::getCity).toList();
        // 收到积分窗口口径与 DancerService.getDetail 一致（近30天截至今日0点）
        LocalDateTime windowStart = LocalDate.now().minusDays(30).atStartOfDay();
        LocalDateTime windowEnd = LocalDate.now().atStartOfDay();
        long pointsReceivedTotal = pointsTransactionRepository.sumReceivedTotal(PointsTargetType.DANCER, dancerId);
        long pointsReceived30d = pointsTransactionRepository
                .sumReceivedSince(PointsTargetType.DANCER, dancerId, windowStart, windowEnd);
        List<GiftCountResponse> giftsReceived = pointsTransactionRepository
                .sumGiftsReceived(PointsTargetType.DANCER, dancerId).stream()
                .map(row -> new GiftCountResponse((String) row[0], (Long) row[1]))
                .toList();
        int contactCost = pointsGateRepository
                .findByTargetTypeAndTargetId(PointsGateTargetType.DANCER_CONTACT, dancerId)
                .map(gate -> gate.isDeleted() ? 0 : gate.getCost())
                .orElse(0);
        long adViews = adViewRepository.countByDancerId(dancerId);
        return new PublicPart(stats, tags, venues, cities, giftsReceived,
                pointsReceivedTotal, pointsReceived30d, contactCost, adViews);
    }

    /** 近7日每日认可（含今日，最近在前）——按 recognitionDate 自然日聚合（同 DancerService 口径） */
    private List<DancerRecognitionStats.DailyRecognitionPoint> fetchRecentDaily(Long dancerId) {
        LocalDate since = LocalDate.now().minusDays(RECENT_DAILY_DAYS - 1L);
        List<DancerRecognitionStats.DailyRecognitionPoint> points = new ArrayList<>(RECENT_DAILY_DAYS);
        Map<LocalDate, Long> countByDay = new HashMap<>();
        for (Object[] row : recognitionRepository.countByDay(dancerId, since)) {
            countByDay.put((LocalDate) row[0], ((Number) row[1]).longValue());
        }
        for (int i = 0; i < RECENT_DAILY_DAYS; i++) {
            LocalDate day = LocalDate.now().minusDays(i);
            points.add(new DancerRecognitionStats.DailyRecognitionPoint(day, countByDay.getOrDefault(day, 0L)));
        }
        return points;
    }

    /** 单舞伴全量标签（详情页认可 chip，全量不截断；四窗口计数——同 DancerService.fetchAllTags 口径） */
    private List<DancerTagStat> fetchTags(Long dancerId, LocalDateTime sinceToday,
                                          LocalDateTime since7d, LocalDateTime since30d) {
        List<DancerTagStat> result = new ArrayList<>();
        for (Object[] row : recognitionTagRepository.aggregateByDancer(dancerId, sinceToday, since7d, since30d)) {
            String tag = (String) row[0];
            long countAll = ((Number) row[1]).longValue();
            long countToday = ((Number) row[2]).longValue();
            long count7d = ((Number) row[3]).longValue();
            long count30d = ((Number) row[4]).longValue();
            DancerTagCode code = DancerTagCode.valueOf(tag); // 仅字典内代码落库，valueOf 安全
            result.add(new DancerTagStat(tag, code.getEmoji(), code.getLabel(), countAll, countToday, count7d, count30d));
        }
        return result;
    }

    /** 详情页场所关系全量（HOME 常去 + APPEARANCE 出现，均按创建时间升序——同 DancerService.fetchVenues 口径） */
    private List<DancerVenueInfo> fetchVenues(Long dancerId) {
        List<DancerVenueInfo> result = new ArrayList<>();
        for (Object[] row : dancerVenueRepository.findVenueBriefsByDancerIds(List.of(dancerId))) {
            result.add(new DancerVenueInfo(
                    (Long) row[1], (String) row[2], (String) row[3], (String) row[4],
                    DancerVenueRelation.valueOf((String) row[5]), (String) row[6]));
        }
        return result;
    }
}
