package org.quwuting.quwutingservice.venuecrowd.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.service.ContributionService;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuecrowd.dto.request.SubmitCrowdReportRequest;
import org.quwuting.quwutingservice.venuecrowd.dto.response.AdminCrowdReportDetail;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 门店热度上报服务（2026-08-29，docs/agents/27-venue-crowd-report.md）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>双维信号</b>：femaleLevel（在店舞伴，主）+ maleLevel（男客，次，可空）；</li>
 *   <li><b>每日一记防刷</b>：UNIQUE(venue,user,report_date) + ON CONFLICT 幂等 upsert，
 *       同日再次上报 = UPDATE 原行 + modify_count+1；<b>确认后积分</b>（2026-09-03
 *       推翻首版零积分，见下）——上报本身零分，被 ≥3 人确认才发，防「为分而报」污染
 *       信号（懒懒Q 教训仍约束"上报即给分"路径）；</li>
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
 *   <li><b>确认后积分 + 反馈闭环</b>（2026-09-03，docs/agents/27「确认后积分」）：
 *       submit() 重算命中 CONFIRMED（≥3 人档位一致）时，给「与众数一致且未拿过奖」
 *       的上报者发放确认奖励（PointsSourceType.CROWD_CONFIRMED，幂等键 = 上报行 id）
 *       ——奖励与信号质量对齐；被确认者收到站内信（不含本次触发者，其提交响应
 *       即时告知）；该店<b>首次</b>达确认时给收藏者发联动通知（受众放大互惠闭环）；
 *       提交响应带 rewardText/upgradedBadgeText 即时反馈文案（服务端权威）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CrowdReportService {

    /** 聚合窗口（小时）：人数是「此刻」的信号，6 小时后数据自动撤下（区别于门店报告 2 天公示期） */
    public static final int CROWD_WINDOW_HOURS = 6;

    /** 站内信 relatedType（VENUE = 深链场所详情页；与 MessageType 注释约定一致） */
    private static final String RELATED_TYPE_VENUE = "VENUE";

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
    /** 确认后积分发放（2026-09-03：CROWD_CONFIRMED 来源，幂等键 = 上报行 id） */
    private final PointsService pointsService;
    /** 确认结果站内信 + 收藏联动通知（2026-09-03，MessageType.CROWD_CONFIRMED） */
    private final MessageService messageService;
    /** 收藏该店的用户（2026-09-03 收藏联动通知受众查询） */
    private final FavoriteRepository favoriteRepository;

    // ===== 列表/详情公共读缓存（2026-08-30 性能优化，根因见 AGENTS.md「首页性能优化」） =====
    //
    // 背景：列表接口每次请求 8~9 次跨洲 DB 往返（ECS↔Supabase 东京单次 300~500ms），
    // 其中门店热度角标（badgeTextsByVenue / latestTextsByVenue）是「6h 窗口 + 每日一记」
    // 的低频变化数据，却每次列表都重查；信任权重（trustWeights → ContributionService
    // .aggregatesFor 内部 7 表聚合）是用户历史行为事实，同样低频变化。三者均为「与
    // 请求用户无关 / 低频变化」的公共数据，短 TTL 缓存 + 写路径显式失效（不串用户、
    // 相对时间文案实时渲染——见各缓存注释）。
    //
    // 相对时间语义约束（latestReportsCache）：列表行文案含「N 分钟前」相对时间，
    // 不能缓存渲染后的文案（缓存期间相对时间失真）——缓存「最新上报原始行」
    // （userId + createdAt），渲染时实时重算 ageTextFor。

    /** 列表角标「N人报过」人数缓存（venueId → 6h 窗口独立人数），TTL 30s。
     *  人数是「此刻」信号的统计输入，30s 内变化对公共面无感知差异；上报后写路径
     *  显式失效（{@link #invalidateVenueCrowdCaches}）。 */
    private final Cache<Long, Long> badgeCountsCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    /** 列表「最新上报」行原始数据缓存（venueId → 最新上报行），TTL 30s。
     *  只缓存 userId + createdAt（相对时间文案渲染时实时计算，避免缓存期失真）。 */
    private final Cache<Long, LatestReport> latestReportsCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    /** 上报者可信度权重缓存（userId → 权重），TTL 60s。
     *  权重 = 1.0 + 采纳加成 + 打卡加成（用户历史行为，低频变化）；aggregatesFor
     *  内部 7 表聚合（跨洲多往返），列表/详情/历史页共享本缓存。 */
    private final Cache<Long, Double> trustWeightsCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    /** 列表「最新上报」行缓存值：上报者 + 上报时刻（渲染相对时间文案用） */
    private record LatestReport(Long userId, LocalDateTime createdAt) {}

    /**
     * 提交 / 更新今晚热度（需登录；每日一记，同日幂等 UPDATE）。
     * 返回提交后的聚合摘要（前端立即刷新展示）。
     * <p>
     * 2026-09-03「确认后积分」反馈闭环：upsert 后重算确认态——命中 CONFIRMED
     * （≥3 人档位一致）→ 给「与众数一致且未拿过奖」的上报者发确认奖励 + 站内信
     * （不含触发者本人，见 {@link #confirmAndReward}）；提交响应带两类即时反馈文案：
     * rewardText（本次新触发确认奖励）/ upgradedBadgeText（身份升级 普通→常客→资深），
     * 均为服务端权威（CrowdSummary 字段注释），前端零拼接零推导。
     */
    @Transactional
    public CrowdSummary submit(Long venueId, SubmitCrowdReportRequest request) {
        Long userId = UserContext.requireAuth();
        // 门店存在性 + 营业状态校验（2026-09-01 用户需求「非营业中的门店不允许上报」：
        // 小程序端入口拦截（体验）+ 本处后端权威校验兜底——禁绕过前端直调 API 写入；
        // 口径 = 存储态 status != OPEN（休息/装修/暂停/停业）拒绝；OPEN 门店未到营业
        // 时段仍可报（今晚热度语义含「今晚」，提前报今晚人况是有效信息，不做时间派生限制）
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new BusinessException(1017, "门店不存在"));
        if (venue.getStatus() != VenueStatus.OPEN) {
            throw new BusinessException(1018, "门店当前未营业，暂不可上报今晚热度");
        }
        // 枚举校验（快捷按钮载荷越界拒绝——零自由文本，无内容审核面）
        CrowdFemaleLevel female = CrowdFemaleLevel.of(request.femaleLevel());
        CrowdMaleLevel male = request.maleLevel() != null ? CrowdMaleLevel.of(request.maleLevel()) : null;
        // ⚠️ 时间口径：created_at/updated_at 必须传 JVM LocalDateTime.now()（北京时间），
        // 禁 DB now()（Supabase 会话 UTC → 与 6h 窗口比较错位 → 上报恒不可见，见
        // VenueCrowdReportRepository.upsert 注释，2026-08-29 修复）。
        LocalDateTime now = LocalDateTime.now();
        // 反馈闭环基线（2026-09-03）：升级检测需「提交前」身份（信任权重刷新为新鲜值——
        // 60s 缓存可能掩盖刚由他人提交触发的确认奖励对权重的贡献）；收藏联动只在
        // 「该店首次达确认」时通知（防骚扰），需提交前确认态做差。
        trustWeightsCache.invalidate(userId);
        String oldBadge = badgeFor(userId, trustWeights(Set.of(userId)));
        boolean wasConfirmed = isConfirmedWindow(venueId);
        crowdReportRepository.upsert(venueId, userId, female.getLevel(),
                male != null ? male.getLevel() : null, LocalDate.now(), now, now);
        // 上报写路径：该店角标人数/最新上报行缓存立即失效（新数据此刻生效，不依赖 TTL）；
        // 信任权重缓存不失效——权重是用户历史行为事实，与本次上报无关（确认奖励会
        // 影响权重，由 confirmAndReward 内对获奖用户显式失效）
        invalidateVenueCrowdCaches(venueId);
        ConfirmOutcome outcome = confirmAndReward(venueId, userId, venue.getName(), wasConfirmed);
        CrowdSummary summary = summary(venueId);
        // 升级检测（新身份以提交后摘要明细行为准——确认奖励计入贡献 → 权重提升可能
        // 恰好跨档；摘要行的 badgeText 为服务端权威派生）
        String newBadge = null;
        for (CrowdSummary.CrowdReportRow row : summary.rows()) {
            if (userId.equals(row.userId())) {
                newBadge = row.badgeText();
                break;
            }
        }
        String upgradedBadgeText = (newBadge != null && !newBadge.equals(oldBadge))
                ? "身份升级：" + newBadge + "舞友" : null;
        String rewardText = outcome.newlyRewarded()
                ? buildRewardText(outcome.agreeCount()) : null;
        return withSubmitTexts(summary, rewardText, upgradedBadgeText);
    }

    /** 确认奖励即时反馈文案（服务端权威）：「你的上报被 3 位舞友确认 · +3 积分已到账」 */
    private String buildRewardText(int agreeCount) {
        return "你的上报被 " + agreeCount + " 位舞友确认 · +" + pointsService.crowdConfirmReward()
                + " 积分已到账";
    }

    /** 摘要整体替换 rewardText/upgradedBadgeText（仅 POST 提交响应填充，GET 恒 null） */
    private CrowdSummary withSubmitTexts(CrowdSummary s, String rewardText, String upgradedBadgeText) {
        return new CrowdSummary(s.hasData(), s.female(), s.male(), s.reporterCount(), s.tier(),
                s.tierText(), s.mainText(), s.maleText(), s.ageText(), s.emptyText(), s.mine(),
                s.rows(), rewardText, upgradedBadgeText);
    }

    /**
     * 门店热度公共读缓存失效（上报写路径调用，与 VenueService.invalidateDetailPublic
     * 同模式——内嵌 Caffeine 不走 Spring CacheManager）。角标人数与最新上报行
     * 均以本店为键，失效单店即可；信任权重（userId 为键）不受单店上报影响。
     */
    public void invalidateVenueCrowdCaches(Long venueId) {
        badgeCountsCache.invalidate(venueId);
        latestReportsCache.invalidate(venueId);
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
                    CrowdTier.EMPTY.getText(), null, null, null, emptyText, mine(venueId), List.of(),
                    null, null);
        }
        // 上报者可信度权重（批量聚合，一次查询；明细行用户标识亦由权重分档，
        // 见 buildDetailRows——不展示用户名）
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        Map<Long, Double> weights = trustWeights(userIds);
        // 明细用户资料批量回填（2026-08-29 昵称防 N+1；2026-09-03 用户要求详情表格
        // 直接展示头像 + 名称——头像/昵称一次查全，空昵称兜底「匿名」、空头像前端
        // 首字占位；isMine 按当前登录用户逐行打标）
        Map<Long, User> users = usersByIds(userIds);
        Long currentUserId = UserContext.getCurrentUserId();
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
        List<CrowdSummary.CrowdReportRow> detailRows =
                buildDetailRows(rows, weights, users, currentUserId);
        return new CrowdSummary(true, femaleView, maleView, reporterCount, tier.name(),
                tier.getText(), mainText, maleText, ageText, emptyText, mine(venueId), detailRows,
                null, null);
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
        // 用户资料批量回填（昵称防 N+1；2026-09-03 头像 + 本人标记 isMine 同源一次查全）
        Map<Long, User> users = usersByIds(userIds);
        Long currentUserId = UserContext.getCurrentUserId();
        List<CrowdSummary.CrowdHistoryRow> content = rows.stream()
                .map(r -> {
                    CrowdFemaleLevel female = CrowdFemaleLevel.of(r.getFemaleLevel());
                    CrowdMaleLevel male = r.getMaleLevel() != null
                            ? CrowdMaleLevel.of(r.getMaleLevel()) : null;
                    return new CrowdSummary.CrowdHistoryRow(
                            r.getId(),
                            r.getUserId(),
                            badgeFor(r.getUserId(), weights),
                            nicknameOf(users.get(r.getUserId())),
                            avatarOf(users.get(r.getUserId())),
                            currentUserId != null && currentUserId.equals(r.getUserId()),
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
    /**
     * 上报者可信度权重（2026-08-30 缓存版）：1.0 + 采纳加成 + 打卡加成。
     * 权重是用户历史行为事实（低频变化），经 {@link #trustWeightsCache} 缓存（TTL 60s）——
     * 底层 aggregatesFor 内部 7 表聚合（跨洲多往返），列表/详情/历史页共享本缓存；
     * 批量回源：先查缓存，miss 的 userIds 一次聚合补齐并回填（含零贡献用户默认 1.0，
     * 避免"查了但无记录"的用户反复 miss）。
     */
    private Map<Long, Double> trustWeights(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> weights = new HashMap<>();
        List<Long> misses = new ArrayList<>();
        for (Long userId : userIds) {
            Double cached = trustWeightsCache.getIfPresent(userId);
            if (cached != null) {
                weights.put(userId, cached);
            } else {
                misses.add(userId);
            }
        }
        if (!misses.isEmpty()) {
            Map<Long, ContributionService.ContributionAggregate> aggs =
                    contributionService.aggregatesFor(misses);
            for (Long userId : misses) {
                ContributionService.ContributionAggregate agg = aggs.get(userId);
                long adoptions = agg != null ? agg.reportedCount() : 0L;
                long checkins = agg != null ? agg.checkInDays() : 0L;
                double w = 1.0 + Math.min(adoptions, 5) * 0.5 + Math.min(checkins, 10) * 0.1;
                trustWeightsCache.put(userId, w);
                weights.put(userId, w);
            }
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

    /** 次信号视图（男客 1-8，细粒度档位同女） */
    private CrowdSummary.CrowdLevelView maleLevelView(Winner w) {
        CrowdMaleLevel male = CrowdMaleLevel.of(w.level());
        return new CrowdSummary.CrowdLevelView(male.getLevel(), male.getDisplayName(),
                male.getAnchor(), w.count(), w.share());
    }

    /** 加权众数中间结果 */
    private record Winner(int level, double share, int count) {
    }

    /** 主信号展示文案：「舞伴 约100 · 3 位舞友 · 1 小时前」 */
    private String buildMainText(CrowdSummary.CrowdLevelView female, int reporterCount,
                                 String ageText, CrowdTier tier) {
        String core = "舞伴 " + female.levelName() + " · "
                + reporterCount + " 位舞友 · " + ageText;
        if (tier == CrowdTier.CONFLICT) {
            return core + " · 请以现场为准";
        }
        return core;
    }

    /** 次信号展示文案：「男客 约50 · 2 人」 */
    private String buildMaleText(CrowdSummary.CrowdLevelView male) {
        CrowdMaleLevel maleLevel = CrowdMaleLevel.of(male.level());
        return "男客 " + maleLevel.getDisplayName() + " · " + male.count() + " 人";
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
     * 每个用户的上报明细（2026-08-29「表格式列表展示每个用户上报」；2026-09-03
     * 用户改判：详情页表格<b>直接展示用户头像 + 名称（超长省略）</b>——推翻
     * 「列表行不展示用户名」旧决策；列表页卡片仍维持匿名——公共面不点名，N人报过/
     * 最新上报行不带头像昵称）：
     * createdAt 倒序（最新在前）；male 未报时 maleLevelName/maleLevelHint 为 null；
     * badgeText = 上报者可信度权重分档（服务端权威三档 资深/常客/普通）；
     * nickname = 完整昵称（空兜底「匿名」）；avatarUrl = 头像（空 = 未设头像，
     * 前端首字占位）；isMine = 当前登录用户本人（高亮 +「我」标记，登录后回填）。
     * <p>
     * 仅含 6h 窗口内有效行（2026-08-29 定版：过期记录不塞进详情页表格——120rpx
     * 定宽时间列放不下长文案，历史数据走 {@link #history} 独立页）。
     */
    private List<CrowdSummary.CrowdReportRow> buildDetailRows(List<VenueCrowdReport> rows,
                                                              Map<Long, Double> weights,
                                                              Map<Long, User> users,
                                                              Long currentUserId) {
        return rows.stream()
                .sorted(Comparator.comparing(VenueCrowdReport::getCreatedAt).reversed())
                .map(r -> {
                    CrowdFemaleLevel female = CrowdFemaleLevel.of(r.getFemaleLevel());
                    CrowdMaleLevel male = r.getMaleLevel() != null
                            ? CrowdMaleLevel.of(r.getMaleLevel()) : null;
                    return new CrowdSummary.CrowdReportRow(
                            r.getUserId(),
                            badgeFor(r.getUserId(), weights),
                            nicknameOf(users.get(r.getUserId())),
                            avatarOf(users.get(r.getUserId())),
                            currentUserId != null && currentUserId.equals(r.getUserId()),
                            female.getDisplayName(), female.getAnchor(),
                            male != null ? male.getDisplayName() : null,
                            male != null ? male.getAnchor() : null,
                            ageTextFor(r.getCreatedAt()));
                })
                .toList();
    }

    /** 明细/历史行用户资料批量回填（2026-09-03：昵称 + 头像一次查全，防 N+1） */
    private Map<Long, User> usersByIds(Set<Long> userIds) {
        return userRepository.findByIdInAndDeletedFalse(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** 昵称权威兜底（空 → 「匿名」） */
    private String nicknameOf(User u) {
        return u != null && u.getNickname() != null && !u.getNickname().isBlank()
                ? u.getNickname() : "匿名";
    }

    /** 头像权威兜底（空/未设 → null，前端渲染首字占位而非破图） */
    private String avatarOf(User u) {
        return u != null && u.getAvatarUrl() != null && !u.getAvatarUrl().isBlank()
                ? u.getAvatarUrl() : null;
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

    // ===== 确认后积分 + 反馈闭环（2026-09-03，docs/agents/27「确认后积分」） =====

    /**
     * 窗口聚合快照（确认判定专用——判定口径必须与 {@link #resolveTier} 一致：
     * reporterCount ≥ CONFIRM_MIN_REPORTERS(3) 且女主信号众数<b>权重占比</b>
     * share ≥ CONFIRM_SHARE(0.6) ⇔ CONFIRMED）。
     */
    private WindowSnapshot windowSnapshot(Long venueId, LocalDateTime since) {
        List<VenueCrowdReport> rows =
                crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(venueId, since);
        if (rows.isEmpty()) {
            return new WindowSnapshot(false, 0, 0.0, 0, List.of(), Map.of());
        }
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        Map<Long, Double> weights = trustWeights(userIds);
        Map<Integer, Double> femaleSums = new HashMap<>();
        Map<Integer, Integer> femaleCounts = new HashMap<>();
        for (VenueCrowdReport r : rows) {
            double w = weights.getOrDefault(r.getUserId(), 1.0);
            femaleSums.merge(r.getFemaleLevel(), w, Double::sum);
            femaleCounts.merge(r.getFemaleLevel(), 1, Integer::sum);
        }
        Winner winner = winnerOf(femaleSums, femaleCounts);
        return new WindowSnapshot(true, userIds.size(), winner.share(), winner.level(), rows, weights);
    }

    /** 窗口内当前是否已达确认态（CONFIRMED；提交前基线用） */
    private boolean isConfirmedWindow(Long venueId) {
        WindowSnapshot snap = windowSnapshot(venueId, LocalDateTime.now().minusHours(CROWD_WINDOW_HOURS));
        return snap.confirmed();
    }

    /**
     * 提交后确认重算 + 激励闭环（与 submit 同事务，任一失败整体回滚）：
     * <ol>
     *   <li><b>确认判定</b>：窗口 ≥3 人且女主信号众数权重占比 ≥0.6（= 详情页
     *       CONFIRMED 态，与 resolveTier 同口径）才进入发放；</li>
     *   <li><b>确认后积分</b>：对「上报档位 == 众数档位」的上报者逐人调用
     *       {@link PointsService#rewardCrowdConfirm}（幂等键 = 上报行 id，每行至多
     *       一次——同日改档再次命中确认不重复发，去重后发放）；触发者本人获奖 →
     *       outcome 标记（提交响应即时展示），其余获奖者 → 站内信 CROWD_CONFIRMED
     *       （不含触发者——其提交响应已即时告知，避免双通道重复打扰）；</li>
     *   <li><b>收藏联动（受众放大）</b>：该店<b>本次提交前未达确认</b>、提交后首次
     *       达成 → 给收藏该店的用户发联动站内信（受益者 = 关注者，互惠闭环；
     *       跳过本次触发者与已收确认信的上报者，每店每晚仅首次达成触发一次）。</li>
     * </ol>
     */
    private ConfirmOutcome confirmAndReward(Long venueId, Long actorId, String venueName,
                                            boolean wasConfirmedBefore) {
        LocalDateTime since = LocalDateTime.now().minusHours(CROWD_WINDOW_HOURS);
        WindowSnapshot snap = windowSnapshot(venueId, since);
        if (!snap.confirmed() || snap.winnerLevel() <= 0) {
            return ConfirmOutcome.none();
        }
        int winnerLevel = snap.winnerLevel();
        // 与众数一致的上报者（每日一记 ⇒ 每人每店每自然日至多一行，但 6h 窗口跨日
        // 边界时同一用户可能有两行——按 userId 保留最近一行去重，避免双发）
        Map<Long, VenueCrowdReport> agreeingByUser = snap.rows().stream()
                .filter(r -> r.getFemaleLevel() != null && r.getFemaleLevel() == winnerLevel)
                .sorted(Comparator.comparing(VenueCrowdReport::getCreatedAt).reversed())
                .collect(Collectors.toMap(VenueCrowdReport::getUserId, Function.identity(),
                        (a, b) -> a)); // keep latest (first in reversed order)
        if (agreeingByUser.isEmpty()) {
            return ConfirmOutcome.none();
        }
        int agreeCount = agreeingByUser.size();
        int reward = pointsService.crowdConfirmReward();
        Set<Long> newlyRewardedIds = new HashSet<>();
        boolean actorRewarded = false;
        for (Map.Entry<Long, VenueCrowdReport> e : agreeingByUser.entrySet()) {
            Long reportUserId = e.getKey();
            Long reportRowId = e.getValue().getId();
            // 发放（幂等：该行已拿过确认奖 → null，跳过；已发放用户权重可能因
            // 新流水提升——显式失效其权重缓存，供本次提交的升级检测读到新鲜值）
            Long balance = pointsService.rewardCrowdConfirm(reportUserId, reportRowId);
            if (balance == null) {
                continue;
            }
            trustWeightsCache.invalidate(reportUserId);
            newlyRewardedIds.add(reportUserId);
            if (reportUserId.equals(actorId)) {
                actorRewarded = true; // 触发者本人：提交响应即时告知，不发站内信
            } else {
                messageService.create(reportUserId, MessageType.CROWD_CONFIRMED,
                        "今晚热度已确认",
                        "你在「" + venueName + "」的今晚热度上报已被 " + agreeCount
                                + " 位舞友确认 · +" + reward + " 积分已到账",
                        RELATED_TYPE_VENUE, venueId);
            }
        }
        // 收藏联动：仅「提交前未确认 → 本次首次确认」触发（每店每晚至多一次）；
        // 跳过触发者与本次已收确认信的上报者（避免双通道重复打扰）
        if (!wasConfirmedBefore && !newlyRewardedIds.isEmpty()) {
            Set<Long> skip = new HashSet<>(newlyRewardedIds);
            skip.add(actorId);
            for (Long favoriterId : favoriteRepository.findUserIdsByVenueId(venueId)) {
                if (skip.contains(favoriterId)) {
                    continue;
                }
                messageService.create(favoriterId, MessageType.CROWD_CONFIRMED,
                        "收藏门店 · 今晚热度",
                        "你收藏的「" + venueName + "」今晚热度已被 " + agreeCount
                                + " 位舞友确认（数据仅供参考）",
                        RELATED_TYPE_VENUE, venueId);
            }
        }
        return new ConfirmOutcome(actorRewarded, agreeCount);
    }

    /** 提交后确认重算快照（判定与 resolveTier 同口径） */
    private record WindowSnapshot(boolean hasRows, int reporterCount, double femaleShare,
                                  int winnerLevel, List<VenueCrowdReport> rows,
                                  Map<Long, Double> weights) {
        /** 是否达确认态：≥3 人 且 众数权重占比 ≥ 0.6（与 CrowdTier.CONFIRMED 同判据） */
        boolean confirmed() {
            return hasRows && reporterCount >= CONFIRM_MIN_REPORTERS && femaleShare >= CONFIRM_SHARE;
        }
    }

    /** 确认激励结果（submit 响应即时反馈数据源） */
    private record ConfirmOutcome(boolean newlyRewarded, int agreeCount) {
        static ConfirmOutcome none() {
            return new ConfirmOutcome(false, 0);
        }
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
     * <p>
     * 2026-08-30 性能优化：人数经 {@link #badgeCountsCache} 缓存（TTL 30s）——
     * 6h 窗口聚合是低频变化数据，逐店缓存 + 批量回源，上报写路径显式失效
     * （{@link #invalidateVenueCrowdCaches}），列表接口省 1 次跨洲往返。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> badgeTextsByVenue(Collection<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> badges = new HashMap<>();
        List<Long> misses = new ArrayList<>();
        for (Long venueId : venueIds) {
            Long count = badgeCountsCache.getIfPresent(venueId);
            if (count != null) {
                if (count >= BADGE_MIN_REPORTERS) {
                    badges.put(venueId, count + "人报过");
                }
            } else {
                misses.add(venueId);
            }
        }
        if (!misses.isEmpty()) {
            LocalDateTime since = LocalDateTime.now().minusHours(CROWD_WINDOW_HOURS);
            for (Object[] row : crowdReportRepository.countDistinctUsersByVenueIdsSince(misses, since)) {
                Long venueId = (Long) row[0];
                Long count = ((Number) row[1]).longValue();
                badgeCountsCache.put(venueId, count);
                if (count >= BADGE_MIN_REPORTERS) {
                    badges.put(venueId, count + "人报过");
                }
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
     * <p>
     * 2026-08-30 性能优化：最新上报行经 {@link #latestReportsCache} 缓存（TTL 30s，
     * 只缓存 userId + createdAt——相对时间文案渲染时实时计算，缓存期不失真）；
     * 上报写路径显式失效（{@link #invalidateVenueCrowdCaches}），列表接口省 1 次跨洲往返。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> latestTextsByVenue(Collection<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LatestReport> latestByVenue = new HashMap<>();
        List<Long> misses = new ArrayList<>();
        for (Long venueId : venueIds) {
            LatestReport cached = latestReportsCache.getIfPresent(venueId);
            if (cached != null) {
                latestByVenue.put(venueId, cached);
            } else {
                misses.add(venueId);
            }
        }
        if (!misses.isEmpty()) {
            LocalDateTime since = LocalDateTime.now().minusHours(CROWD_WINDOW_HOURS);
            for (VenueCrowdReport r : crowdReportRepository.findLatestByVenueIdsSince(misses, since)) {
                // 同一店同一时刻多条（理论罕见，子查询等值匹配）→ 每店只取首条
                if (latestByVenue.containsKey(r.getVenueId())) {
                    continue;
                }
                LatestReport entry = new LatestReport(r.getUserId(), r.getCreatedAt());
                latestByVenue.put(r.getVenueId(), entry);
                latestReportsCache.put(r.getVenueId(), entry);
            }
        }
        if (latestByVenue.isEmpty()) {
            return Map.of();
        }
        Set<Long> userIds = latestByVenue.values().stream()
                .map(LatestReport::userId).collect(Collectors.toSet());
        Map<Long, Double> weights = trustWeights(userIds);
        Map<Long, String> texts = new HashMap<>();
        for (Map.Entry<Long, LatestReport> e : latestByVenue.entrySet()) {
            LatestReport entry = e.getValue();
            texts.put(e.getKey(),
                    ageTextFor(entry.createdAt()) + " · " + badgeFor(entry.userId(), weights) + "舞友上报");
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

    /**
     * 管理端按店明细分页（2026-09-01 热度管理下钻，GET /admin/crowd-reports/venues/{venueId}，
     * 仅 ADMIN）：最近 {@link #ADMIN_WINDOW_HOURS}h 该店全部上报，createdAt 倒序分页。
     * 运营据此定位「哪条不合理/错误」→ 删除（{@link #adminDelete}）；行字段服务端
     * 权威（badgeText 三档/档位名+锚点/reportDate/modifyCount/绝对时间），前端零拼接。
     */
    @Transactional(readOnly = true)
    public Page<AdminCrowdReportDetail> adminVenueDetails(Long venueId, int page, int size) {
        LocalDateTime since = LocalDateTime.now().minusHours(ADMIN_WINDOW_HOURS);
        Page<VenueCrowdReport> result = crowdReportRepository
                .findByVenueIdAndCreatedAtAfterAndDeletedFalse(venueId, since,
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
        List<AdminCrowdReportDetail> content = rows.stream()
                .map(r -> {
                    CrowdFemaleLevel female = CrowdFemaleLevel.of(r.getFemaleLevel());
                    CrowdMaleLevel male = r.getMaleLevel() != null
                            ? CrowdMaleLevel.of(r.getMaleLevel()) : null;
                    return new AdminCrowdReportDetail(
                            r.getId(),
                            r.getUserId(),
                            nicknames.getOrDefault(r.getUserId(), "匿名"),
                            badgeFor(r.getUserId(), weights),
                            female.getLevel(), female.getDisplayName(), female.getAnchor(),
                            male != null ? male.getLevel() : null,
                            male != null ? male.getDisplayName() : null,
                            r.getReportDate(),
                            r.getModifyCount() != null ? r.getModifyCount() : 0,
                            r.getCreatedAt());
                })
                .toList();
        return new PageImpl<>(content, result.getPageable(), result.getTotalElements());
    }

    /**
     * 管理端删除单条上报（2026-09-01 用户需求「可删除不合理/错误的今晚热度上报记录」，
     * DELETE /admin/crowd-reports/{id}，仅 ADMIN）：
     * 软删除（deleted=true，全库统一口径）——summary/history/列表角标/管理端聚合
     * 均带 deleted=false 过滤，删除后自动生效；该店角标与最新上报行缓存显式失效
     * （{@link #invalidateVenueCrowdCaches}，不依赖 TTL）。
     * <p>
     * 删除后用户当日可重新上报：每日一记部分唯一索引谓词 WHERE deleted=false，
     * 删除行不再命中约束 → 再次提交 upsert 生成新行（管理员删了错误记录，
     * 用户可报回正确数据）。
     */
    @Transactional
    public void adminDelete(Long reportId) {
        VenueCrowdReport report = crowdReportRepository.findById(reportId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(1019, "上报记录不存在或已删除"));
        report.setDeleted(true);
        crowdReportRepository.save(report);
        invalidateVenueCrowdCaches(report.getVenueId());
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
