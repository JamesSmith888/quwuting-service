package org.quwuting.quwutingservice.dancer.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancer.dto.response.DancerStatsResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerDemandStat;
import org.quwuting.quwutingservice.dancer.dto.response.DancerTotals;
import org.quwuting.quwutingservice.dancer.dto.response.DancerTrendPoint;
import org.quwuting.quwutingservice.dancer.dto.response.DancerUnlockRecord;
import org.quwuting.quwutingservice.dancer.dto.response.DancerUnlockStat;
import org.quwuting.quwutingservice.dancer.dto.response.DancerViewSourceTrendPoint;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.enums.DancerServiceCategory;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerStatsRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 舞伴统计服务（2026-08-14 舞伴统计图第一期；2026-08-26 追加需求趋势/需求热度——
 * 对齐舞伴模块服务与联系方式需求的新增内容）。
 * <p>
 * 对齐门店热度服务（{@code VenueHeatService}）的「内嵌 Caffeine refresh-ahead」缓存
 * 模式（能力平权：统计接口同模式，但舞伴域暂无热度公式，只含趋势时间序列 +
 * 累计指标 totals + 解锁信息 + 需求热度）：
 * <ul>
 *   <li>{@code refreshAfterWrite(60s)}：条目写入 60s 后，下一次访问立即返回旧值并
 *       异步回源刷新（单飞：同键并发只触发一次回源）——统计页高频滚动浏览场景下
 *       返回旧值可接受，回源期间不阻塞调用方；</li>
 *   <li>新鲜度主保障是写路径显式 {@link #invalidate}（浏览/认可/收藏/礼物/分享/
 *       解锁，2026-08-21 解锁入矩阵——解锁改变 unlockStats 输入；需求记录随解锁
 *       写路径一并写入，同一失效入口覆盖 demandTrend/demandStats，2026-08-26
 *       确认无需新增失效点），与门店「写路径缓存失效」矩阵同约定——refresh-ahead
 *       只是兜底；</li>
 *   <li>不校验舞伴存在性/可见性：统计接口由详情页进入（详情已校验），冷门舞伴
 *       统计为空序列属正常（空图恒渲染，前端承接）；对不存在的舞伴返回全零序列，
 *       不会暴露任何敏感信息（纯计数时间序列）。</li>
 * </ul>
 * 统计口径（对齐门店 2026-08-13 实时化）：所有滚动窗口（近30天）的排他上界统一为
 * 请求时刻 now——含今日已发生的数据；骨架锚点按自然日对齐（[今天-30, 今天] 的 31 天
 * 骨架）。同一日内多次请求统计结果随请求时刻漂移，由前端 banner「数据实时更新 ·
 * 含今日」显性承担口径说明。
 */
@Service
@RequiredArgsConstructor
public class DancerStatsService {

    /** 趋势窗口天数（与门店 VenueHeatService.WINDOW_DAYS 同值：近30天 + 今日 = 31 天骨架） */
    private static final int TREND_WINDOW_DAYS = 30;

    /** 缓存刷新间隔：写入 60s 后下一次访问触发异步回源（refresh-ahead） */
    private static final long CACHE_REFRESH_SECONDS = 60;

    /** 缓存绝对过期：30 分钟（防长期无访问的陈旧条目常驻） */
    private static final long CACHE_EXPIRE_MINUTES = 30;

    private final DancerStatsRepository dancerStatsRepository;

    private final DancerRepository dancerRepository;

    private final LoadingCache<Long, DancerStatsResponse> statsCache = Caffeine.newBuilder()
            .maximumSize(500)
            .refreshAfterWrite(CACHE_REFRESH_SECONDS, TimeUnit.SECONDS)
            .expireAfterWrite(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
            .build(this::computeStats);

    /**
     * 获取舞伴统计（缓存：单飞 + refresh-ahead，见类注释）。
     */
    public DancerStatsResponse getStats(Long dancerId) {
        return statsCache.get(dancerId);
    }

    /**
     * 写路径显式失效：任何改变统计输入的写操作（浏览/认可/收藏/礼物/分享）完成后
     * 必须调用（经事务 afterCommit 注册，见各写服务），保证其他用户及时看到最新统计。
     */
    public void invalidate(Long dancerId) {
        statsCache.invalidate(dancerId);
    }

    /**
     * 舞伴某内容类型的解锁记录明细（2026-08-26 新增，「解锁信息」条形点击 → 详情页）。
     * <p>
     * 实时查询（不走 {@link #statsCache}——明细是逐条行为记录，与聚合统计的缓存
     * 时效语义不同，且解锁低频、单次查询开销可忽略）。
     * <p>
     * 口径（见 {@code DancerStatsRepository#findDancerUnlocks}）：解锁用户公开资料
     * （软删用户排除）+ 内容描述（照片序号/短视频时长/联系方式）+ 解锁时间 +
     * 本次花费积分（免费解锁 = 0），按解锁时间倒序。
     * <p>
     * 舞伴存在性 + 公开可见性校验（对齐 gifters {@code validateTargetVisible}：
     * 明细含用户公开资料，仅对公开可见的舞伴开放，防止经不可见舞伴的解锁记录反查用户）。
     *
     * @param dancerId       舞伴 ID
     * @param targetTypeName 内容类型（PointsGateTargetType.name()）；非法值 → 1001
     */
    public List<DancerUnlockRecord> unlocks(Long dancerId, String targetTypeName) {
        Dancer dancer = dancerRepository.findByIdAndDeletedFalse(dancerId)
                .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
        if (dancer.getStatus() != DancerStatus.NORMAL) {
            throw new BusinessException(1001, "该舞伴资料暂不可见");
        }
        PointsGateTargetType targetType = safeTargetType(targetTypeName);
        if (targetType == null) {
            throw new BusinessException(1001, "参数错误");
        }
        return dancerStatsRepository.findDancerUnlocks(dancerId, targetType.name()).stream()
                .map(row -> new DancerUnlockRecord(
                        (Long) row[0],
                        row[1] == null || ((String) row[1]).isBlank() ? null : (String) row[1],
                        (String) row[2],
                        targetType,
                        unlockLabel(targetType),
                        unlockTargetDesc(targetType, (Integer) row[4], (Integer) row[5]),
                        (LocalDateTime) row[3],
                        row[6] == null ? 0 : ((Number) row[6]).intValue()))
                .toList();
    }

    /**
     * 统计计算（缓存 loader，勿直接调用——经 {@link #getStats} 走缓存）。
     * <p>
     * 聚合在独立隐式只读事务中执行（统计读无跨语句原子性要求，与门店同语义）。
     */
    private DancerStatsResponse computeStats(Long dancerId) {
        // 实时口径：上界 = 请求时刻 now（含今日已发生的数据）；骨架按自然日对齐
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        LocalDate sinceDate = today.minusDays(TREND_WINDOW_DAYS);
        LocalDate asOfDate = today;
        // DATE 列（认可 recognition_date / 浏览 view_date）排他上界 = 明天 0 点，覆盖今日全天
        LocalDate untilDate = today.plusDays(1);
        // timestamptz 列（收藏/礼物/分享 created_at）上界 = now（实时）
        LocalDateTime windowSince = today.atStartOfDay().minusDays(TREND_WINDOW_DAYS);
        LocalDateTime windowUntil = now;

        List<DancerTrendPoint> recognitionTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<DancerTrendPoint> favoriteTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<DancerTrendPoint> pointsTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<DancerTrendPoint> shareTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<DancerTrendPoint> viewTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<DancerTrendPoint> demandTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        List<DancerViewSourceTrendPoint> viewSourceTrend = new ArrayList<>(TREND_WINDOW_DAYS + 1);
        for (DancerStatsRepository.DailyTrendRow row : dancerStatsRepository.countDancerDailyTrends(
                dancerId, sinceDate, asOfDate, untilDate, windowSince, windowUntil)) {
            String day = row.getDay().toString();
            recognitionTrend.add(new DancerTrendPoint(day, orZero(row.getRecognitioncount())));
            favoriteTrend.add(new DancerTrendPoint(day, orZero(row.getFavcount())));
            pointsTrend.add(new DancerTrendPoint(day, orZero(row.getPoints())));
            shareTrend.add(new DancerTrendPoint(day, orZero(row.getSharecount())));
            viewTrend.add(new DancerTrendPoint(day, orZero(row.getViewcount())));
            // 联系方式需求数（2026-08-26 追加，「需求趋势」图用——qwt_demand_records
            // created_at 按天分组，与分享同窗口同实时口径）
            demandTrend.add(new DancerTrendPoint(day, orZero(row.getDemandcount())));
            // other = 全量 - list - share - search - venue（同源口径交叉校验；直接 COUNT 亦可，减法省一次扫描）
            viewSourceTrend.add(new DancerViewSourceTrendPoint(
                    day,
                    orZero(row.getViewlistcount()),
                    orZero(row.getViewsharecount()),
                    orZero(row.getViewsearchcount()),
                    orZero(row.getViewvenuecount()),
                    Math.max(0L, orZero(row.getViewcount())
                            - orZero(row.getViewlistcount())
                            - orZero(row.getViewsharecount())
                            - orZero(row.getViewsearchcount())
                            - orZero(row.getViewvenuecount()))));
        }
        // 用户解锁信息分类聚合（2026-08-21 追加）：独立一条往返（合并 SQL 见
        // DancerStatsRepository#countDancerUnlockStats），非时间序列；repository
        // 已按人次降序且过滤 0 记录类别。
        List<DancerUnlockStat> unlockStats = new ArrayList<>();
        for (DancerStatsRepository.UnlockStatRow row : dancerStatsRepository.countDancerUnlockStats(dancerId)) {
            PointsGateTargetType targetType = safeTargetType(row.getTargettype());
            unlockStats.add(new DancerUnlockStat(
                    targetType,
                    unlockLabel(targetType),
                    orZero(row.getUnlockcount()),
                    orZero(row.getUniqueusers()),
                    row.getCost() != null ? row.getCost() : 0));
        }
        unlockStats.sort(Comparator.comparingLong(DancerUnlockStat::unlockCount).reversed());
        // 需求热度分类聚合（2026-08-26 追加）：按服务类别聚合 qwt_demand_records
        // 关联 qwt_dancer_services 的需求次数/去重人数——"用户最需要 TA 的哪类
        // 服务"（非口嗨量化）；repository 已按次数降序且过滤 0 记录类别。
        List<DancerDemandStat> demandStats = new ArrayList<>();
        for (DancerStatsRepository.DemandStatRow row : dancerStatsRepository.countDancerDemandStats(dancerId)) {
            demandStats.add(new DancerDemandStat(
                    row.getCategory(),
                    demandLabel(row.getCategory()),
                    orZero(row.getDemandcount()),
                    orZero(row.getUniqueusers())));
        }
        // 全量历史累计指标（2026-08-22 追加；2026-08-26 补需求计数，「累计数据」
        // 汇总卡用）：与趋势序列同源同口径、仅窗口不同（累计=全量）——独立一条往返
        // （合并进 mega-query 会破坏骨架 GROUP BY 语义，标量子查询更直接）。
        DancerStatsRepository.TotalsRow totalsRow = dancerStatsRepository.countDancerTotals(dancerId);
        DancerTotals totals = new DancerTotals(
                orZero(totalsRow.getRecognitioncount()),
                orZero(totalsRow.getFavoritecount()),
                orZero(totalsRow.getViewcount()),
                orZero(totalsRow.getSharecount()),
                orZero(totalsRow.getPointstotal()),
                orZero(totalsRow.getDemandcount()));
        return new DancerStatsResponse(
                recognitionTrend, favoriteTrend, pointsTrend, shareTrend,
                viewTrend, viewSourceTrend, unlockStats, demandTrend, demandStats,
                totals, asOfDate.toString());
    }

    /**
     * 服务类别展示名（2026-08-26 追加，需求热度条形图用）：类别默认标签
     * （按时段 / 舞厅跳舞 / 线上聊天 / 其他）——服务端权威，新增类别免前端改动。
     * 未知类别回退枚举名（防御性：脏数据/新枚举上线而映射漏更时仍可读）。
     */
    private static String demandLabel(String category) {
        if (category == null || category.isBlank()) {
            return "其他";
        }
        try {
            return DancerServiceCategory.valueOf(category).defaultLabel();
        } catch (IllegalArgumentException e) {
            return category;
        }
    }

    /**
     * 内容类型展示名（新增内容类型 = 加枚举值 + 本映射项，前端免改动）。
     * 未知类型回退枚举名（防御性：新枚举已上线而本映射漏更时仍可读）。
     */
    private static String unlockLabel(PointsGateTargetType targetType) {
        if (targetType == null) {
            return "其他";
        }
        return switch (targetType) {
            case DANCER_PHOTO -> "照片";
            case DANCER_VIDEO -> "视频";
            case DANCER_CONTACT -> "联系方式";
        };
    }

    /**
     * 解锁记录的内容描述（2026-08-26 新增，解锁详情页目标行）——后端权威派生，
     * 前端零拼接：
     * 照片 = "照片 N"（N = 相册展示序号 sort_order+1，多张照片可区分）；
     * 短视频 = "短视频 · m:ss"（时长；未知时长 = 0 时省略）；
     * 联系方式 = "联系方式"（舞伴实体字段，无细分）。
     * 非媒体目标（联系方式）sortOrder/durationSeconds 恒 null。
     */
    private static String unlockTargetDesc(PointsGateTargetType targetType, Integer sortOrder, Integer durationSeconds) {
        if (targetType == PointsGateTargetType.DANCER_PHOTO) {
            int index = sortOrder != null ? sortOrder + 1 : 0;
            return "照片 " + index;
        }
        if (targetType == PointsGateTargetType.DANCER_VIDEO) {
            if (durationSeconds != null && durationSeconds > 0) {
                int m = durationSeconds / 60;
                int s = durationSeconds % 60;
                return "短视频 · " + m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
            }
            return "短视频";
        }
        return "联系方式";
    }

    /** 枚举名 → 枚举（未知/异常回退 null，上游按 null 兜底展示「其他」） */
    private static PointsGateTargetType safeTargetType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return PointsGateTargetType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long orZero(Long value) {
        return value != null ? value : 0L;
    }
}
