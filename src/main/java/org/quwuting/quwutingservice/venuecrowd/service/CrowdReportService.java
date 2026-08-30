package org.quwuting.quwutingservice.venuecrowd.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.points.service.ContributionService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuecrowd.dto.request.SubmitCrowdReportRequest;
import org.quwuting.quwutingservice.venuecrowd.dto.response.AdminCrowdReportSummary;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdSummary;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReport;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdFemaleLevel;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdMaleLevel;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdTier;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 门店热度上报服务（2026-08-29，docs/agents/27-venue-crowd-report.md）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>双维信号</b>：femaleLevel（在店舞伴，主）+ maleLevel（男客，次，可空）；</li>
 *   <li><b>每日一记防刷</b>：UNIQUE(venue,user,report_date) + ON CONFLICT 幂等 upsert，
 *       同日再次上报 = UPDATE 原行 + modify_count+1；首版零积分（防「为分而报」污染信号）；</li>
 *   <li><b>6 小时窗口</b>：聚合只取最近 {@link #CROWD_WINDOW_HOURS} 小时记录（强时效，
 *       过时自动撤下）；<b>全部历史记录</b>（2026-08-29 用户需求）——详情页右下角
 *       「查看全部热度」链接 → 独立历史页（{@link #history}，分页全量，过期行
 *       expired 标记），不塞进详情页表格（120rpx 定宽列放不下长文案）；</li>
 *   <li><b>上报者可信度加权</b>（2026-08-29 用户补充需求）：聚合非简单计数——
 *       每票权重 = 1.0 + 历史上报采纳加成 + 打卡加成（社区贡献信号，复用
 *       {@link ContributionService#aggregatesFor}，不发明新信任分体系、不公开）；
 *       众数按权重和计，权重高者（资深/常客）票更重，新号刷票被稀释；</li>
 *   <li><b>置信度分层</b>：{@link CrowdTier}（EMPTY/UNVERIFIED/VETERAN/CONFIRMED/CONFLICT），
 *       展示文案服务端权威派生，前端零拼接。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CrowdReportService {

    /** 聚合窗口（小时）：人数是「此刻」的信号，6 小时后数据自动撤下（区别于门店报告 2 天公示期） */
    public static final int CROWD_WINDOW_HOURS = 6;

    /** 历史页单页大小上限（分页查询防深翻页） */
    static final int HISTORY_PAGE_SIZE_LIMIT = 50;

    /** 单条上报标记「资深舞友」的可信度权重阈值（N==1 时升级 UNVERIFIED_VETERAN 呈现） */
    static final double VETERAN_WEIGHT = 2.0;

    /** 明细标识「常客」的权重阈值（1.2 ≤ w < 2.0；有贡献（打卡/少量采纳）但未达资深） */
    static final double REGULAR_WEIGHT = 1.2;

    /** 众数确认占比阈值：众数权重和 / 总权重和 ≥ 该值视为「一致」（否则 CONFLICT 不站队） */
    static final double CONFIRM_SHARE = 0.6;

    /** 确认态最小独立人数（≥3 人一致才有统计意义；1-2 人一律中性降级） */
    static final int CONFIRM_MIN_REPORTERS = 3;

    /** 列表角标最小独立上报人数（公共面克制：<3 人不上列表，防误伤与商家刷量） */
    static final int BADGE_MIN_REPORTERS = 3;

    /** 管理端聚合窗口（小时）：运营看「今天有什么异常」，24h 覆盖前晚场次 */
    static final int ADMIN_WINDOW_HOURS = 24;

    /** 高频修改阈值（modify_count ≥ 3 = 反复改，刷量/反复横跳嫌疑，运营核实） */
    static final int HIGH_MODIFY_THRESHOLD = 3;

    private final VenueCrowdReportRepository crowdReportRepository;
    private final VenueRepository venueRepository;
    private final UserRepository userRepository;
    private final ContributionService contributionService;

    /**
     * 提交 / 更新今晚热度（需登录；每日一记，同日幂等 UPDATE）。
     * 返回提交后的聚合摘要（前端立即刷新展示）。
     */
    @Transactional
    public CrowdSummary submit(Long venueId, SubmitCrowdReportRequest request) {
        Long userId = UserContext.requireAuth();
        requireVenue(venueId);
        // 枚举校验（快捷按钮载荷越界拒绝——零自由文本，无内容审核面）
        CrowdFemaleLevel female = CrowdFemaleLevel.of(request.femaleLevel());
        CrowdMaleLevel male = request.maleLevel() != null ? CrowdMaleLevel.of(request.maleLevel()) : null;
        // ⚠️ 时间口径：created_at/updated_at 必须传 JVM LocalDateTime.now()（北京时间），
        // 禁 DB now()（Supabase 会话 UTC → 与 6h 窗口比较错位 → 上报恒不可见，见
        // VenueCrowdReportRepository.upsert 注释，2026-08-29 修复）。
        LocalDateTime now = LocalDateTime.now();
        crowdReportRepository.upsert(venueId, userId, female.getLevel(),
                male != null ? male.getLevel() : null, LocalDate.now(), now, now);
        return summary(venueId);
    }

    /** 聚合摘要（公开读，无需登录；mine 字段仅在登录时回填） */
    @Transactional(readOnly = true)
    public CrowdSummary summary(Long venueId) {
        LocalDateTime since = LocalDateTime.now().minusHours(CROWD_WINDOW_HOURS);
        List<VenueCrowdReport> rows =
                crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(venueId, since);
        String emptyText = "暂无舞友上报，来报第一个";
        if (rows.isEmpty()) {
            return new CrowdSummary(false, null, null, 0, CrowdTier.EMPTY.name(),
                    CrowdTier.EMPTY.getText(), null, null, null, emptyText, mine(venueId), List.of());
        }
        // 上报者可信度权重（批量聚合，一次查询；明细行用户标识亦由权重分档，
        // 见 buildDetailRows——不展示用户名）
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        Map<Long, Double> weights = trustWeights(userIds);
        // 明细昵称批量回填（2026-08-29 用户拍板：详情弹层**直接展示完整昵称**，
        // 纯展示不可点击；列表行仍不显示——公共面不点名。空昵称兜底「匿名」，防 N+1）
        Map<Long, String> nicknames = userRepository.findByIdInAndDeletedFalse(userIds).stream()
                .collect(Collectors.toMap(User::getId,
                        u -> u.getNickname() != null && !u.getNickname().isBlank()
                                ? u.getNickname() : "匿名"));
        // 加权众数（双维独立聚合）
        Map<Integer, Double> femaleSums = new HashMap<>();
        Map<Integer, Double> maleSums = new HashMap<>();
        Map<Integer, Integer> femaleCounts = new HashMap<>();
        Map<Integer, Integer> maleCounts = new HashMap<>();
        for (VenueCrowdReport r : rows) {
            double w = weights.getOrDefault(r.getUserId(), 1.0);
            femaleSums.merge(r.getFemaleLevel(), w, Double::sum);
            femaleCounts.merge(r.getFemaleLevel(), 1, Integer::sum);
            if (r.getMaleLevel() != null) {
                maleSums.merge(r.getMaleLevel(), w, Double::sum);
                maleCounts.merge(r.getMaleLevel(), 1, Integer::sum);
            }
        }
        int reporterCount = userIds.size();
        Winner femaleWinner = winnerOf(femaleSums, femaleCounts);
        CrowdSummary.CrowdLevelView femaleView = femaleLevelView(femaleWinner);
        CrowdSummary.CrowdLevelView maleView = maleSums.isEmpty()
                ? null : maleLevelView(winnerOf(maleSums, maleCounts));
        // 置信度分层
        CrowdTier tier = resolveTier(reporterCount, femaleView.share(), rows, weights);
        // 展示文案（服务端权威）
        String ageText = ageText(rows);
        String mainText = buildMainText(femaleView, reporterCount, ageText, tier);
        String maleText = maleView != null ? buildMaleText(maleView) : null;
        List<CrowdSummary.CrowdReportRow> detailRows = buildDetailRows(rows, weights, nicknames);
        return new CrowdSummary(true, femaleView, maleView, reporterCount, tier.name(),
                tier.getText(), mainText, maleText, ageText, emptyText, mine(venueId), detailRows);
    }

    /**
     * 全部热度历史（2026-08-29 用户需求「用户可以看到过期后的记录」，最终形态：
     * 详情页右下角「查看全部热度」链接 → 独立历史页；公开读，无需登录）。
     * <p>
     * 全量分页（createdAt 倒序，不过滤窗口）；行字段全部服务端权威派生——
     * badgeText（资深/常客/普通）、档位名/锚点、ageText（相对时间）、
     * reportAt（绝对时间 yyyy-MM-dd HH:mm:ss）、expired（是否已出 6h 窗口，
     * 前端仅据此派生「已过期」标签 + 置灰，不参与任何聚合）。
     * <p>
     * ⚠️ 与 summary 的边界：summary = 窗口内有效信号（决策用）；history =
     * 全部记录（回顾用）。历史页不禁用入口——窗口无数据时用户仍可回看。
     */
    @Transactional(readOnly = true)
    public Page<CrowdSummary.CrowdHistoryRow> history(Long venueId, int page, int size) {
        requireVenue(venueId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(CROWD_WINDOW_HOURS);
        Page<VenueCrowdReport> result = crowdReportRepository
                .findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(venueId,
                        PageRequest.of(page, Math.min(size, HISTORY_PAGE_SIZE_LIMIT)));
        List<VenueCrowdReport> rows = result.getContent();
        if (rows.isEmpty()) {
            return new PageImpl<>(List.of(), result.getPageable(), result.getTotalElements());
        }
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        Map<Long, Double> weights = trustWeights(userIds);
        Map<Long, String> nicknames = userRepository.findByIdInAndDeletedFalse(userIds).stream()
                .collect(Collectors.toMap(User::getId,
                        u -> u.getNickname() != null && !u.getNickname().isBlank()
                                ? u.getNickname() : "匿名"));
        List<CrowdSummary.CrowdHistoryRow> content = rows.stream()
                .map(r -> {
                    CrowdFemaleLevel female = CrowdFemaleLevel.of(r.getFemaleLevel());
                    CrowdMaleLevel male = r.getMaleLevel() != null
                            ? CrowdMaleLevel.of(r.getMaleLevel()) : null;
                    return new CrowdSummary.CrowdHistoryRow(
                            r.getId(),
                            r.getUserId(),
                            badgeFor(r.getUserId(), weights),
                            nicknames.getOrDefault(r.getUserId(), "匿名"),
                            female.getDisplayName(), female.getAnchor(),
                            male != null ? male.getDisplayName() : null,
                            male != null ? male.getAnchor() : null,
                            r.getCreatedAt(),
                            ageTextFor(r.getCreatedAt()),
                            r.getCreatedAt().isBefore(since));
                })
                .toList();
        return new PageImpl<>(content, result.getPageable(), result.getTotalElements());
    }

    /** 我今天的上报（可改；未登录 / 未上报 → null） */
    private CrowdSummary.CrowdMineView mine(Long venueId) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        List<VenueCrowdReport> mine = crowdReportRepository
                .findByVenueIdAndUserIdAndReportDateAndDeletedFalse(venueId, userId, LocalDate.now());
        if (mine.isEmpty()) {
            return null;
        }
        VenueCrowdReport row = mine.get(0);
        return new CrowdSummary.CrowdMineView(row.getFemaleLevel(), row.getMaleLevel(),
                CrowdFemaleLevel.of(row.getFemaleLevel()).getDisplayName());
    }

    /**
     * 上报者可信度权重（内部，不公开展示）：
     * base 1.0 + min(历史上报采纳,5)×0.5 + min(打卡天数,10)×0.1（封顶 4.5）。
     * 信号选择逻辑（对齐「每信号须预测目标行为」）：上报采纳 = 之前报得准；
     * 打卡 = 真实到店行为。认领/收藏/认可/分享与「报人数可信」弱相关，不进入权重；
     * 认领人（门店主）不享受加成（商家自报有营销动机，用中性权重稀释）。
     */
    private Map<Long, Double> trustWeights(Set<Long> userIds) {
        Map<Long, ContributionService.ContributionAggregate> aggs =
                contributionService.aggregatesFor(userIds);
        Map<Long, Double> weights = new HashMap<>();
        for (Map.Entry<Long, ContributionService.ContributionAggregate> e : aggs.entrySet()) {
            long adoptions = e.getValue().reportedCount();
            long checkins = e.getValue().checkInDays();
            double w = 1.0 + Math.min(adoptions, 5) * 0.5 + Math.min(checkins, 10) * 0.1;
            weights.put(e.getKey(), w);
        }
        return weights;
    }

    /**
     * 置信度分层（单一事实源，CrowdTier 注释同步规则）：
     * N==0 → EMPTY；N==1 → UNVERIFIED（权重 ≥ VETERAN_WEIGHT 升级 UNVERIFIED_VETERAN）；
     * N==2 且 share≥CONFIRM_SHARE → UNVERIFIED（两人一致仍中性，未达统计意义）；
     * N≥3 且 share≥CONFIRM_SHARE → CONFIRMED；N≥2 且 share&lt;CONFIRM_SHARE → CONFLICT。
     */
    private CrowdTier resolveTier(int reporterCount, double femaleShare,
                                  List<VenueCrowdReport> rows, Map<Long, Double> weights) {
        if (reporterCount == 1) {
            VenueCrowdReport only = rows.get(0);
            double w = weights.getOrDefault(only.getUserId(), 1.0);
            return w >= VETERAN_WEIGHT ? CrowdTier.UNVERIFIED_VETERAN : CrowdTier.UNVERIFIED;
        }
        if (reporterCount >= CONFIRM_MIN_REPORTERS && femaleShare >= CONFIRM_SHARE) {
            return CrowdTier.CONFIRMED;
        }
        if (femaleShare < CONFIRM_SHARE) {
            return CrowdTier.CONFLICT;
        }
        return CrowdTier.UNVERIFIED;
    }

    /** 加权众数（winnerLevel + 权重占比 + 独立人数） */
    private Winner winnerOf(Map<Integer, Double> sums, Map<Integer, Integer> counts) {
        int winnerLevel = 0;
        double winnerSum = -1;
        for (Map.Entry<Integer, Double> e : sums.entrySet()) {
            if (e.getValue() > winnerSum) {
                winnerSum = e.getValue();
                winnerLevel = e.getKey();
            }
        }
        double total = sums.values().stream().mapToDouble(Double::doubleValue).sum();
        double share = total > 0 ? winnerSum / total : 0;
        return new Winner(winnerLevel, Math.round(share * 100.0) / 100.0, counts.getOrDefault(winnerLevel, 0));
    }

    /** 主信号视图（levelName/levelHint 后端权威） */
    private CrowdSummary.CrowdLevelView femaleLevelView(Winner w) {
        CrowdFemaleLevel female = CrowdFemaleLevel.of(w.level());
        return new CrowdSummary.CrowdLevelView(female.getLevel(), female.getDisplayName(),
                female.getAnchor(), w.count(), w.share());
    }

    /** 次信号视图（男客 1-4，锚点同女——冷清 0-20 / 一般 约50 / 不错 约100 / 火爆 300+） */
    private CrowdSummary.CrowdLevelView maleLevelView(Winner w) {
        CrowdMaleLevel male = CrowdMaleLevel.of(w.level());
        return new CrowdSummary.CrowdLevelView(male.getLevel(), male.getDisplayName(),
                male.getAnchor(), w.count(), w.share());
    }

    /** 加权众数中间结果 */
    private record Winner(int level, double share, int count) {
    }

    /** 主信号展示文案：「舞伴 不错（约100）· 3 位舞友 · 1 小时前」 */
    private String buildMainText(CrowdSummary.CrowdLevelView female, int reporterCount,
                                 String ageText, CrowdTier tier) {
        String core = "舞伴 " + female.levelName() + "（" + female.levelHint() + "）· "
                + reporterCount + " 位舞友 · " + ageText;
        if (tier == CrowdTier.CONFLICT) {
            return core + " · 请以现场为准";
        }
        return core;
    }

    /** 次信号展示文案：「男客 一般（约50）· 2 人」（2026-08-29 用户改判：男客同女 4 档 + 锚点） */
    private String buildMaleText(CrowdSummary.CrowdLevelView male) {
        CrowdMaleLevel maleLevel = CrowdMaleLevel.of(male.level());
        return "男客 " + maleLevel.getDisplayName() + "（" + maleLevel.getAnchor() + "）· " + male.count() + " 人";
    }

    /** 相对时间（「刚刚 / N 分钟前 / N 小时前」）——服务端权威，前端零拼接 */
    private String ageText(List<VenueCrowdReport> rows) {
        LocalDateTime latest = rows.stream().map(VenueCrowdReport::getCreatedAt)
                .max(LocalDateTime::compareTo).orElse(null);
        return ageTextFor(latest);
    }

    /** 单条上报的相对时间（明细行逐条复用；createdAt 为 null 时返回空串） */
    private String ageTextFor(LocalDateTime at) {
        if (at == null) {
            return "";
        }
        long minutes = Duration.between(at, LocalDateTime.now()).toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        return (minutes / 60) + " 小时前";
    }

    /**
     * 每个用户的上报明细（2026-08-29 用户要求「表格式列表展示每个用户上报」，
     * 同日再改「舞友列不展示用户名，只展示用户标识」，七次改「详情弹窗直接展示
     * 完整昵称（不可点击），去脱敏」）：
     * createdAt 倒序（最新在前）；male 未报时 maleLevelName/maleLevelHint 为 null；
     * **列表行不展示昵称**——badgeText 由上报者可信度权重分档（服务端权威，
     * 对齐 UNVERIFIED_VETERAN 判定）；nickname = 完整昵称（空兜底「匿名」），
     * 仅详情弹层展示（纯展示不可点击跳转，公共面不点名）。
     * <p>
     * 仅含 6h 窗口内有效行（2026-08-29 定版：过期记录不塞进详情页表格——120rpx
     * 定宽时间列放不下长文案，历史数据走 {@link #history} 独立页）。
     */
    private List<CrowdSummary.CrowdReportRow> buildDetailRows(List<VenueCrowdReport> rows,
                                                              Map<Long, Double> weights,
                                                              Map<Long, String> nicknames) {
        return rows.stream()
                .sorted(Comparator.comparing(VenueCrowdReport::getCreatedAt).reversed())
                .map(r -> {
                    CrowdFemaleLevel female = CrowdFemaleLevel.of(r.getFemaleLevel());
                    CrowdMaleLevel male = r.getMaleLevel() != null
                            ? CrowdMaleLevel.of(r.getMaleLevel()) : null;
                    return new CrowdSummary.CrowdReportRow(
                            r.getUserId(),
                            badgeFor(r.getUserId(), weights),
                            nicknames.getOrDefault(r.getUserId(), "匿名"),
                            female.getDisplayName(), female.getAnchor(),
                            male != null ? male.getDisplayName() : null,
                            male != null ? male.getAnchor() : null,
                            ageTextFor(r.getCreatedAt()));
                })
                .toList();
    }

    /**
     * 用户标识分档（2026-08-29 用户要求「至少三个级别」+「删除『舞友』两字」——
     * 表头已有「舞友」列名，行内只显示级别词）：
     * 权重 ≥ {@link #VETERAN_WEIGHT} → 资深；≥ {@link #REGULAR_WEIGHT} → 常客；
     * 其余 → 普通（无贡献记录按 1.0 兜底）。
     */
    private String badgeFor(Long userId, Map<Long, Double> weights) {
        double w = weights.getOrDefault(userId, 1.0);
        if (w >= VETERAN_WEIGHT) {
            return "资深";
        }
        if (w >= REGULAR_WEIGHT) {
            return "常客";
        }
        return "普通";
    }

    private void requireVenue(Long venueId) {
        if (!venueRepository.existsById(venueId)) {
            throw new BusinessException(1017, "门店不存在");
        }
    }

    /**
     * 列表角标批量生成（2026-08-29，VenueService.listVenues 调用）：
     * 一次 IN + GROUP BY 覆盖整页（防 N+1），返回 venueId → 中性文案「N人报过」。
     * 门槛 = 最近 6h 窗口独立上报人数 ≥ {@link #BADGE_MIN_REPORTERS}（3）——
     * 列表是公共面，<3 人不上（防误伤/防商家找两三个朋友刷「火爆」）；
     * 文案中性不带档位词（「热闹/冷清」不上列表——给门店贴正负定性有商家争议
     * 与数据误伤风险，具体档位留给详情页，同一事实只呈现一次）。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> badgeTextsByVenue(Collection<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime since = LocalDateTime.now().minusHours(CROWD_WINDOW_HOURS);
        Map<Long, Long> counts = crowdReportRepository
                .countDistinctUsersByVenueIdsSince(venueIds, since).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]));
        Map<Long, String> badges = new HashMap<>();
        for (Map.Entry<Long, Long> e : counts.entrySet()) {
            if (e.getValue() >= BADGE_MIN_REPORTERS) {
                badges.put(e.getKey(), e.getValue() + "人报过");
            }
        }
        return badges;
    }

    /**
     * 列表「最新上报」行批量生成（2026-08-29，VenueService.listVenues 调用）：
     * 每店窗口内最新一条上报 → 克制文案「{相对时间} · {标识}舞友上报」
     * （如「2 分钟前 · 资深舞友上报」）——列表公共面克制：
     * <ul>
     *   <li>**不显示档位词**：单条档位贴公共列表有商家自报营销/数据误伤风险，
     *       档位留详情页（同 {@link #badgeTextsByVenue} 决策）；本行只表达
     *       「此刻有人刚报过」的实时动态 + 上报者信任标识；</li>
     *   <li>**不公开昵称**：标识由上报者可信度权重分档（{@link #badgeFor}，
     *       服务端权威，资深/常客/普通 + 「舞友」后缀——列表无表头，需自解释）；</li>
     *   <li>展示门槛：窗口内有上报即返回（「有人刚报过」是事实非结论，
     *       与 ≥3 人角标语义解耦、互补：胶囊 = 多少人，本行 = 最新动态）。</li>
     * </ul>
     * 返回 venueId → 文案；无上报的店不在 map（前端 null 不渲染）。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> latestTextsByVenue(Collection<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime since = LocalDateTime.now().minusHours(CROWD_WINDOW_HOURS);
        List<VenueCrowdReport> rows = crowdReportRepository
                .findLatestByVenueIdsSince(venueIds, since);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        Map<Long, Double> weights = trustWeights(userIds);
        Map<Long, String> texts = new HashMap<>();
        // 同一店同一时刻多条（理论罕见，子查询等值匹配）→ 每店只取首条
        Set<Long> seen = new HashSet<>();
        for (VenueCrowdReport r : rows) {
            if (!seen.add(r.getVenueId())) {
                continue;
            }
            texts.put(r.getVenueId(),
                    ageTextFor(r.getCreatedAt()) + " · " + badgeFor(r.getUserId(), weights) + "舞友上报");
        }
        return texts;
    }

    /**
     * 管理端热度上报聚合（2026-08-29，GET /admin/crowd-reports 数据源，仅 ADMIN）：
     * 最近 24h 全部上报按店聚合——档位分布（运营看「各执一词」conflict）、高频修改
     * 用户（modify_count ≥ 3，刷量/反复横跳嫌疑）。数据量小（日活 5~36），
     * 一次全量拉取 + 内存分组，不做 SQL 分页（上限随日活增长，届时再评估）。
     * 返回按上报条数降序，分页由 Controller 内存切页。
     */
    @Transactional(readOnly = true)
    public List<AdminCrowdReportSummary> adminSummaries() {
        LocalDateTime since = LocalDateTime.now().minusHours(ADMIN_WINDOW_HOURS);
        List<VenueCrowdReport> rows = crowdReportRepository.findByCreatedAtAfterAndDeletedFalse(since);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<VenueCrowdReport>> byVenue = rows.stream()
                .collect(Collectors.groupingBy(VenueCrowdReport::getVenueId));
        // 门店名 + 用户昵称批量回填（防 N+1）
        Map<Long, String> venueNames = venueRepository.findAllById(byVenue.keySet()).stream()
                .collect(Collectors.toMap(Venue::getId, Venue::getName));
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        Map<Long, String> nicknames = userRepository.findByIdInAndDeletedFalse(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : "匿名"));
        List<AdminCrowdReportSummary> summaries = new ArrayList<>();
        for (Map.Entry<Long, List<VenueCrowdReport>> e : byVenue.entrySet()) {
            List<VenueCrowdReport> venueRows = e.getValue();
            summaries.add(buildAdminSummary(e.getKey(),
                    venueNames.getOrDefault(e.getKey(), "门店" + e.getKey()),
                    venueRows, nicknames));
        }
        summaries.sort(Comparator.comparingInt(AdminCrowdReportSummary::reportCount24h).reversed());
        return summaries;
    }

    private AdminCrowdReportSummary buildAdminSummary(Long venueId, String venueName,
                                                      List<VenueCrowdReport> rows,
                                                      Map<Long, String> nicknames) {
        // 档位分布（按条数，降序；male 用 CrowdMaleLevel 解析，勿复用 female 枚举）
        List<AdminCrowdReportSummary.LevelCount> femaleDist = femaleDistribution(rows);
        List<AdminCrowdReportSummary.LevelCount> maleDist = maleDistribution(rows);
        boolean conflict = false;
        if (!femaleDist.isEmpty()) {
            long total = femaleDist.stream().mapToLong(AdminCrowdReportSummary.LevelCount::count).sum();
            conflict = femaleDist.get(0).count() * 1.0 / total < CONFIRM_SHARE;
        }
        // 高频修改用户（modify_count ≥ 3，按 modifyCount 降序）
        List<AdminCrowdReportSummary.HighModifyUser> highModifiers = rows.stream()
                .filter(r -> r.getModifyCount() != null && r.getModifyCount() >= HIGH_MODIFY_THRESHOLD)
                .collect(Collectors.toMap(
                        VenueCrowdReport::getUserId,
                        Function.identity(),
                        (a, b) -> a.getModifyCount() >= b.getModifyCount() ? a : b))
                .values().stream()
                .sorted(Comparator.comparingInt(VenueCrowdReport::getModifyCount).reversed())
                .map(r -> new AdminCrowdReportSummary.HighModifyUser(r.getUserId(),
                        nicknames.getOrDefault(r.getUserId(), String.valueOf(r.getUserId())),
                        r.getModifyCount()))
                .toList();
        LocalDateTime latestAt = rows.stream().map(VenueCrowdReport::getCreatedAt)
                .max(LocalDateTime::compareTo).orElse(null);
        return new AdminCrowdReportSummary(venueId, venueName, rows.size(),
                femaleDist, maleDist, conflict, highModifiers, latestAt);
    }

    /** 在店舞伴档位分布（level → 条数，降序；levelName/levelHint 后端权威） */
    private List<AdminCrowdReportSummary.LevelCount> femaleDistribution(List<VenueCrowdReport> rows) {
        Map<Integer, Long> counts = rows.stream()
                .filter(r -> r.getFemaleLevel() != null)
                .collect(Collectors.groupingBy(VenueCrowdReport::getFemaleLevel, Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .map(e -> {
                    CrowdFemaleLevel female = CrowdFemaleLevel.of(e.getKey());
                    return new AdminCrowdReportSummary.LevelCount(
                            female.getLevel(), female.getDisplayName(), female.getAnchor(), e.getValue());
                })
                .collect(Collectors.toList());
    }

    /** 男客数量档位分布（level → 条数，降序；CrowdMaleLevel 解析，锚点同女） */
    private List<AdminCrowdReportSummary.LevelCount> maleDistribution(List<VenueCrowdReport> rows) {
        Map<Integer, Long> counts = rows.stream()
                .filter(r -> r.getMaleLevel() != null)
                .collect(Collectors.groupingBy(VenueCrowdReport::getMaleLevel, Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .map(e -> {
                    CrowdMaleLevel male = CrowdMaleLevel.of(e.getKey());
                    return new AdminCrowdReportSummary.LevelCount(
                            male.getLevel(), male.getDisplayName(), male.getAnchor(), e.getValue());
                })
                .collect(Collectors.toList());
    }
}
