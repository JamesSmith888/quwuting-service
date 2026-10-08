package org.quwuting.quwutingservice.venuecrowd.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
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
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdBaseline;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdSummary;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReport;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdFemaleLevel;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdMaleLevel;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdTier;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository;
import org.quwuting.quwutingservice.venuecrowd.stat.BusinessDay;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdBaselineBuilder;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdConsensus;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdHeadline;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdPolicy;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdTimeText;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdVerdict;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdVote;
import org.quwuting.quwutingservice.venuecrowd.stat.SampleTier;
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
import java.util.Collections;
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
 * 门店热度上报服务（2026-08-29，docs/agents/27-venue-crowd-report.md；
 * 2026-10-07 统计口径重定，docs/agents/53-venue-crowd-stats.md）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>双维信号</b>：femaleLevel（在店舞伴，主）+ maleLevel（男客，次，可空）；</li>
 *   <li><b>每营业夜一记防刷</b>：UNIQUE(venue,user,business_date)（V41，05:00 分界）+ ON DUPLICATE KEY
 *       幂等 upsert，同夜再次上报（含跨午夜）= UPDATE 原行 + modify_count+1；<b>确认后积分</b>
 *       （2026-09-03 推翻首版零积分）——上报本身零分，被 ≥3 人确认才发，防「为分而报」污染信号；</li>
 *   <li><b>统计口径只有一处</b>：一人一票 → （加权）下中位数 → 中位数 ±1 档内算一致 → 置信度分层，
 *       全在 {@link CrowdConsensus}（纯函数），本类<b>只负责取数、组装、落库</b>，不含任何统计判定
 *       （门禁 {@code CrowdDomainSingleSourceTest}）。常量唯一出处 {@link CrowdPolicy}；</li>
 *   <li><b>有效期窗口（1 天，2026-10-08 由 6h 放宽）</b>：聚合只取最近
 *       {@link CrowdPolicy#VALID_WINDOW_HOURS} 小时记录；窗口外的数据走独立历史页（{@link #history}）、
 *       折叠头摘要（{@link CrowdHeadline}）与常态人气（{@link #baseline}）。窗口只定义「数据还算不算数」——
 *       「今晚」是营业日概念，措辞由 {@link CrowdHeadline} 按营业日独立分流（勿用窗口长度近似），见该类注释；</li>
 *   <li><b>认领人不进统计</b>：商家自报有营销动机；其上报照常落库与展示（标「店家」），
 *       但不参与中位数 / 确认积分 / 列表角标 / 最新上报行；</li>
 *   <li><b>确认后积分 + 反馈闭环</b>（2026-09-03）：submit() 重算命中确认态时，给「与中位数一致」的
 *       上报者发放确认奖励（PointsSourceType.CROWD_CONFIRMED，幂等键 = 上报行 id）；被确认者收到站内信
 *       （不含本次触发者）；该店<b>首次</b>达确认时给收藏者发联动通知；提交响应带
 *       rewardText/upgradedBadgeText 即时反馈文案（服务端权威）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CrowdReportService {

    /** 站内信 relatedType（VENUE = 深链场所详情页；与 MessageType 注释约定一致） */
    private static final String RELATED_TYPE_VENUE = "VENUE";

    /** 历史页单页大小上限（分页查询防深翻页） */
    static final int HISTORY_PAGE_SIZE_LIMIT = 50;

    /** 管理端聚合窗口（小时）：运营看「今天有什么异常」，24h 覆盖前晚场次 */
    static final int ADMIN_WINDOW_HOURS = 24;

    /** 高频修改阈值（modify_count ≥ 3 = 反复改，刷量/反复横跳嫌疑，运营核实） */
    static final int HIGH_MODIFY_THRESHOLD = 3;

    /** 空态文案 */
    public static final String EMPTY_TEXT = "暂无舞友上报，来报第一个";

    /** 窗口内只有认领人上报时的主文案（统计里没有舞友，但明细表里有店家行——不能让卡片静默空白） */
    public static final String OWNER_ONLY_TEXT = "仅店家自报，暂无其他舞友上报";

    private final VenueCrowdReportRepository crowdReportRepository;
    private final VenueRepository venueRepository;
    private final UserRepository userRepository;
    /** 可信度权重 + 徽标分档（2026-10-07 抽出，与点赞名单共享） */
    private final CrowdTrustService crowdTrustService;
    /** 确认后积分发放（2026-09-03：CROWD_CONFIRMED 来源，幂等键 = 上报行 id） */
    private final PointsService pointsService;
    /** 确认结果站内信 + 收藏联动通知（2026-09-03，MessageType.CROWD_CONFIRMED） */
    private final MessageService messageService;
    /** 收藏该店的用户（2026-09-03 收藏联动通知受众查询） */
    private final FavoriteRepository favoriteRepository;
    /** 行级点赞「有用」聚合（2026-09-03：summary/history 行赞数 + likedByMe 回填） */
    private final CrowdReportLikeService crowdReportLikeService;

    // ===== 列表公共读缓存（2026-08-30 性能优化，根因见 AGENTS.md「首页性能优化」） =====
    //
    // 背景：列表接口每次请求 8~9 次 DB 往返，其中门店热度角标（badgeTextsByVenue /
    // latestTextsByVenue）是「有效期窗口（1 天）+ 每夜一记」的低频变化数据，却每次列表都重查。
    // 两者均为「与请求用户无关 / 低频变化」的公共数据，短 TTL 缓存 + 写路径显式失效
    // （不串用户、相对时间文案实时渲染）。
    //
    // 相对时间语义约束（latestReportsCache）：列表行文案含「N 分钟前」相对时间，
    // 不能缓存渲染后的文案——缓存「最新上报原始行」（userId + createdAt），
    // 渲染时实时重算 ageTextFor。

    /** 列表角标「N人报过」人数缓存（venueId → 有效期窗口内独立人数，已剔除认领人），TTL 30s。 */
    private final Cache<Long, Long> badgeCountsCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    /** 列表「最新上报」行原始数据缓存（venueId → 最新上报行，已剔除认领人），TTL 30s。 */
    private final Cache<Long, LatestReport> latestReportsCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    /** 列表「最新上报」行缓存值：上报者 + 上报时刻（渲染相对时间文案用） */
    private record LatestReport(Long userId, LocalDateTime createdAt) {}

    /**
     * 有效期窗口快照：窗口内全部行 + 女 / 男两个维度的统计结论（认领人已剔除出统计，但仍在 rows 里）。
     * <p>
     * 2026-10-07：原带一个 weights 字段，明细行改由 {@link #recentDetailRows} 独立取数后
     * 已无人读取，一并移除（留着一个恒不被读的空壳字段，下一个人会以为明细行权重从这里来）。
     * <p>
     * 2026-10-08：新增 {@code femaleVotes}——折叠头摘要改按<b>营业日</b>分流（措辞「今晚」只给
     * 当前营业日的票）后，headline 需要票级数据（不只是 verdict），由本字段透传。
     * <p>
     * 命名（2026-10-08）：窗口由 6h 放宽为 1 天后，本快照不再等价于「今晚」（白天查询会含昨晚的票）
     * ——原名 {@code Tonight} 与「今晚」营业日语义混淆，随常量一并改为 ValidWindow，勿改回。
     */
    private record ValidWindow(List<VenueCrowdReport> rows,
                               CrowdVerdict female, CrowdVerdict male,
                               List<CrowdVote> femaleVotes) {}

    /**
     * 提交 / 更新今晚热度（需登录；每营业夜一记，同夜幂等 UPDATE）。
     * 返回提交后的聚合摘要（前端立即刷新展示）。
     * <p>
     * 2026-09-03「确认后积分」反馈闭环：upsert 后重算确认态——命中确认态
     * （≥3 人且一致占比达标）→ 给「与中位数一致」的上报者发确认奖励 + 站内信
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
        // 禁 DB now()（见 VenueCrowdReportRepository.upsert 注释，2026-08-29 修复）。
        // 本次请求内只取一次时钟：营业日、窗口、写入时刻同源，跨 05:00 / 窗口边界时不会前后不一致。
        LocalDateTime now = LocalDateTime.now();
        Long claimantId = venue.getClaimedBy();
        // 反馈闭环基线（2026-09-03）：升级检测需「提交前」身份（信任权重刷新为新鲜值——
        // 60s 缓存可能掩盖刚由他人提交触发的确认奖励对权重的贡献）；收藏联动只在
        // 「该店首次达确认」时通知（防骚扰），需提交前确认态做差。
        crowdTrustService.invalidate(userId);
        String oldBadge = CrowdTrustService.badgeFor(userId, crowdTrustService.weights(Set.of(userId)), claimantId);
        boolean wasConfirmed = validWindow(venueId, claimantId, now).female().confirmed();
        crowdReportRepository.upsert(venueId, userId, female.getLevel(),
                male != null ? male.getLevel() : null, now.toLocalDate(), BusinessDay.of(now), now, now);
        // 上报写路径：该店角标人数/最新上报行缓存立即失效（新数据此刻生效，不依赖 TTL）；
        // 信任权重缓存不失效——权重是用户历史行为事实，与本次上报无关（确认奖励会
        // 影响权重，由 confirmAndReward 内对获奖用户显式失效）
        invalidateVenueCrowdCaches(venueId);
        ConfirmOutcome outcome = confirmAndReward(venue, userId, wasConfirmed, now);
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
        return "您的上报被 " + agreeCount + " 位舞友确认 · +" + pointsService.crowdConfirmReward()
                + " 积分已到账";
    }

    /** 摘要整体替换 rewardText/upgradedBadgeText（仅 POST 提交响应填充，GET 恒 null） */
    private CrowdSummary withSubmitTexts(CrowdSummary s, String rewardText, String upgradedBadgeText) {
        return new CrowdSummary(s.hasData(), s.female(), s.male(), s.reporterCount(), s.tier(),
                s.tierText(), s.mainText(), s.maleText(), s.ageText(), s.emptyText(), s.mine(),
                s.rows(), rewardText, upgradedBadgeText, s.headlineText());
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
        LocalDateTime now = LocalDateTime.now();
        Long claimantId = claimantOf(venueId);
        ValidWindow t = validWindow(venueId, claimantId, now);
        CrowdSummary.CrowdMineView mine = mine(venueId, now);
        // 明细行取数与统计取数**刻意分开**（2026-10-07 用户拍板「必须展示最近的三条，
        // 不管它是否过期」）：统计仍只认有效期窗口（tonight，2026-10-08 起 1 天），
        // 展示另取最近 N 条（含过期）。两者混用会让「今晚人气」显示昨晚的数据。
        List<CrowdSummary.CrowdReportRow> detailRows = recentDetailRows(venueId, claimantId, now);
        if (t.rows().isEmpty()) {
            return new CrowdSummary(false, null, null, 0, CrowdTier.EMPTY.name(),
                    CrowdTier.EMPTY.getText(), null, null, null, EMPTY_TEXT, mine, detailRows,
                    null, null, recentHeadline(venueId, claimantId, now));
        }
        CrowdVerdict female = t.female();
        if (female.isEmpty()) {
            // 窗口内只有认领人的上报：统计里没有舞友，但明细表里有店家行——不能让卡片静默空白
            return new CrowdSummary(true, null, null, 0, CrowdTier.EMPTY.name(),
                    CrowdTier.EMPTY.getText(), OWNER_ONLY_TEXT, null, null, EMPTY_TEXT, mine, detailRows,
                    null, null, recentHeadline(venueId, claimantId, now));
        }
        CrowdTier tier = CrowdConsensus.tierOf(female);
        CrowdSummary.CrowdLevelView femaleView = femaleLevelView(female);
        CrowdSummary.CrowdLevelView maleView = t.male().isEmpty() ? null : maleLevelView(t.male());
        // 展示文案（服务端权威）
        String ageText = ageTextFor(latestVoterAt(t.rows(), claimantId));
        String mainText = buildMainText(female, ageText, tier);
        String maleText = maleView != null ? buildMaleText(t.male()) : null;
        // 折叠头摘要（2026-10-08）：措辞按营业日分流——当前营业日有票 ⇒「今晚 …」（只统计当天票）；
        // 否则（有效期放宽到 1 天后，窗口内可能只有昨晚的票）⇒「昨晚 23:40 …」。
        // 不能直接用整个窗口的 verdict——窗口（1 天）≠ 营业夜，混用会把昨晚的票说成「今晚」。
        String headline = CrowdHeadline.fromWindow(now, t.femaleVotes()).orElse(null);
        return new CrowdSummary(true, femaleView, maleView, female.voterCount(), tier.name(),
                tier.getText(), mainText, maleText, ageText, EMPTY_TEXT, mine, detailRows,
                null, null, headline);
    }

    /**
     * 详情页明细行数据源（2026-10-07 用户拍板）：该店<b>最近 {@link CrowdPolicy#DETAIL_ROWS_LIMIT} 条</b>
     * 上报，<b>不过滤有效期窗口</b>——过期记录照样上屏，每行带 {@code expired} 标记由前端置灰
     * +「已过期」如实告知。
     * <p>
     * 根因：有效期一过（清晨 / 次日白天查「昨晚怎么样」），详情页整张卡退化成「暂无舞友上报」，
     * 恰恰是用户最想看的时刻什么也看不到——折叠头摘要能带出一句结论，但明细行是空的，
     * 「谁报的、报了多少」全部丢失。
     * <p>
     * ⚠️ <b>与统计的边界</b>：本方法只产出<b>展示</b>行，绝不参与 hasData / mainText / tier /
     * headline 的判定（那些仍只由 {@link #tonight} 的有效期窗口决定，见 {@link CrowdPolicy#DETAIL_ROWS_LIMIT}）。
     * 认领人行照常保留在明细里（如实标「店家」），只是不计入任何统计，与原口径一致。
     * <p>
     * 复用 {@link #history} 的同一仓储查询（全量 createdAt 倒序分页）取第一页，
     * 不新增 SQL —— 展示条数是上限裁剪，不是新口径。
     */
    private List<CrowdSummary.CrowdReportRow> recentDetailRows(Long venueId, Long claimantId,
                                                                LocalDateTime now) {
        List<VenueCrowdReport> rows = crowdReportRepository
                .findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(venueId,
                        PageRequest.of(0, CrowdPolicy.DETAIL_ROWS_LIMIT))
                .getContent();
        if (rows.isEmpty()) {
            return List.of();
        }
        // 用户资料 / 权重 / 点赞聚合批量回填（防 N+1；与 buildDetailRows 同口径）
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        Map<Long, User> users = usersByIds(userIds);
        Map<Long, Double> weights = crowdTrustService.weights(userIds);
        Long currentUserId = UserContext.getCurrentUserId();
        return buildDetailRows(rows, weights, users, currentUserId, claimantId,
                likeAggregates(rows, currentUserId), now);
    }

    /**
     * 常态人气（2026-10-07，公开读，热度页「实时人气」卡消费）：前 7 / 30 个营业日（不含今晚）
     * 的中位数按样本量诚实表达 + 今晚 vs 常态的比较结论。统计与措辞全在
     * {@link CrowdBaselineBuilder}（纯函数），本方法只取数。
     */
    @Transactional(readOnly = true)
    public CrowdBaseline baseline(Long venueId) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new BusinessException(1017, "门店不存在"));
        LocalDateTime now = LocalDateTime.now();
        Long claimantId = venue.getClaimedBy();
        ValidWindow t = validWindow(venueId, claimantId, now);
        LocalDate today = BusinessDay.of(now);
        int longestWindowDays = Collections.max(CrowdPolicy.BASELINE_WINDOW_DAYS);
        List<VenueCrowdReport> past = crowdReportRepository
                .findByVenueIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndDeletedFalse(venueId,
                        BusinessDay.startOf(today.minusDays(longestWindowDays)), BusinessDay.startOf(today));
        Set<Long> userIds = past.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        List<CrowdVote> votes = votesOf(past, claimantId, crowdTrustService.weights(userIds),
                VenueCrowdReport::getFemaleLevel);
        return CrowdBaselineBuilder.build(now, t.female(), votes);
    }

    /**
     * 全部热度历史（2026-08-29 用户需求「用户可以看到过期后的记录」，最终形态：
     * 详情页右下角「查看全部热度」链接 → 独立历史页；公开读，无需登录）。
     * <p>
     * 全量分页（createdAt 倒序，不过滤窗口）；行字段全部服务端权威派生——
     * badgeText（资深/常客/普通/店家）、档位名/锚点、ageText（相对时间）、
     * reportAt（绝对时间 yyyy-MM-dd HH:mm:ss）、expired（是否已出有效期窗口，
     * 前端仅据此派生「已过期」标签 + 置灰，不参与任何聚合）。
     * <p>
     * ⚠️ 与 summary 的边界：summary = 窗口内有效信号（决策用）；history =
     * 全部记录（回顾用）。历史页不禁用入口——窗口无数据时用户仍可回看。
     */
    @Transactional(readOnly = true)
    public Page<CrowdSummary.CrowdHistoryRow> history(Long venueId, int page, int size) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new BusinessException(1017, "门店不存在"));
        Long claimantId = venue.getClaimedBy();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(CrowdPolicy.VALID_WINDOW_HOURS);
        Page<VenueCrowdReport> result = crowdReportRepository
                .findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(venueId,
                        PageRequest.of(page, Math.min(size, HISTORY_PAGE_SIZE_LIMIT)));
        List<VenueCrowdReport> rows = result.getContent();
        if (rows.isEmpty()) {
            return new PageImpl<>(List.of(), result.getPageable(), result.getTotalElements());
        }
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        Map<Long, Double> weights = crowdTrustService.weights(userIds);
        // 用户资料批量回填（昵称防 N+1；2026-09-03 头像 + 本人标记 isMine 同源一次查全）
        Map<Long, User> users = usersByIds(userIds);
        Long currentUserId = UserContext.getCurrentUserId();
        // 行级点赞赞数（2026-09-03）：历史页行赞数只读展示（整页锁定，无点赞交互）
        Map<Long, Long> likeCounts = crowdReportLikeService.likeCountsByReportIds(
                rows.stream().map(VenueCrowdReport::getId).toList());
        List<CrowdSummary.CrowdHistoryRow> content = rows.stream()
                .map(r -> {
                    CrowdFemaleLevel female = CrowdFemaleLevel.of(r.getFemaleLevel());
                    CrowdMaleLevel male = r.getMaleLevel() != null
                            ? CrowdMaleLevel.of(r.getMaleLevel()) : null;
                    return new CrowdSummary.CrowdHistoryRow(
                            r.getId(),
                            r.getUserId(),
                            CrowdTrustService.badgeFor(r.getUserId(), weights, claimantId),
                            nicknameOf(users.get(r.getUserId())),
                            avatarOf(users.get(r.getUserId())),
                            currentUserId != null && currentUserId.equals(r.getUserId()),
                            female.getDisplayName(), female.getAnchor(),
                            male != null ? male.getDisplayName() : null,
                            male != null ? male.getAnchor() : null,
                            r.getCreatedAt(),
                            ageTextFor(r.getCreatedAt()),
                            r.getCreatedAt().isBefore(since),
                            likeCounts.getOrDefault(r.getId(), 0L).intValue());
                })
                .toList();
        return new PageImpl<>(content, result.getPageable(), result.getTotalElements());
    }

    /** 我今晚（本营业日）的上报（可改；未登录 / 未上报 → null） */
    private CrowdSummary.CrowdMineView mine(Long venueId, LocalDateTime now) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        List<VenueCrowdReport> mine = crowdReportRepository
                .findByVenueIdAndUserIdAndBusinessDateAndDeletedFalse(venueId, userId, BusinessDay.of(now));
        if (mine.isEmpty()) {
            return null;
        }
        VenueCrowdReport row = mine.get(0);
        return new CrowdSummary.CrowdMineView(row.getFemaleLevel(), row.getMaleLevel(),
                CrowdFemaleLevel.of(row.getFemaleLevel()).getDisplayName());
    }

    // ===== 取数 → 票 → 统计（统计本身全在 CrowdConsensus） =====

    /** 门店认领人（无认领 / 门店不存在 → null） */
    private Long claimantOf(Long venueId) {
        return venueRepository.findById(venueId).map(Venue::getClaimedBy).orElse(null);
    }

    /**
     * 取有效期窗口并统计（认领人剔除出票，仍保留在 rows 里）。
     * <p>
     * ⚠️ rows 的消费者只有统计侧（{@code latestVoterAt} / 空态判定）、折叠头摘要与
     * 「只有认领人上报」分支；详情页<b>展示</b>的明细行走 {@link #recentDetailRows}
     * （最近 N 条、含过期），两者刻意分开——统计认窗口、展示认「最近」，混用会让
     * 「今晚人气」显示昨晚的数据。
     */
    private ValidWindow validWindow(Long venueId, Long claimantId, LocalDateTime now) {
        List<VenueCrowdReport> rows = crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(
                venueId, now.minusHours(CrowdPolicy.VALID_WINDOW_HOURS));
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        Map<Long, Double> weights = crowdTrustService.weights(userIds);
        List<CrowdVote> femaleVotes = votesOf(rows, claimantId, weights, VenueCrowdReport::getFemaleLevel);
        CrowdVerdict female = CrowdConsensus.evaluate(CrowdConsensus.latestPerVoter(femaleVotes));
        CrowdVerdict male = CrowdConsensus.evaluate(CrowdConsensus.latestPerVoter(
                votesOf(rows, claimantId, weights, VenueCrowdReport::getMaleLevel)));
        return new ValidWindow(rows, female, male, femaleVotes);
    }

    /**
     * 上报行 → 票（某个维度）：剔除认领人与该维度未填的行。
     * 同一人的多行（跨 05:00 边界的两个营业日）此处保留，由调用方选择折票语义
     * （今晚 = latestPerVoter；常态 = typicalPerVoter）。
     */
    private static List<CrowdVote> votesOf(List<VenueCrowdReport> rows, Long ownerId,
                                           Map<Long, Double> weights,
                                           Function<VenueCrowdReport, Integer> levelOf) {
        List<CrowdVote> votes = new ArrayList<>(rows.size());
        for (VenueCrowdReport r : rows) {
            Integer level = levelOf.apply(r);
            if (level == null || r.getCreatedAt() == null || r.getUserId().equals(ownerId)) {
                continue;
            }
            votes.add(new CrowdVote(r.getUserId(), level, weights.getOrDefault(r.getUserId(), 1.0),
                    r.getCreatedAt(), r.getId()));
        }
        return votes;
    }

    /** 窗口外回看摘要（窗口内没有任何行时才调用；多一次按店回看查询）。 */
    private String recentHeadline(Long venueId, Long claimantId, LocalDateTime now) {
        LocalDateTime from = BusinessDay.startOf(
                BusinessDay.of(now).minusDays(CrowdPolicy.HEADLINE_LOOKBACK_DAYS));
        List<VenueCrowdReport> rows =
                crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(venueId, from);
        if (rows.isEmpty()) {
            return null;
        }
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        return CrowdHeadline.recent(now, votesOf(rows, claimantId, crowdTrustService.weights(userIds),
                VenueCrowdReport::getFemaleLevel)).orElse(null);
    }

    /** 窗口内最新一条「计入统计」的上报时刻（认领人的不算，否则「N 分钟前」会被店家刷新）。 */
    private static LocalDateTime latestVoterAt(List<VenueCrowdReport> rows, Long ownerId) {
        return rows.stream()
                .filter(r -> !r.getUserId().equals(ownerId))
                .map(VenueCrowdReport::getCreatedAt)
                .filter(at -> at != null)
                .max(LocalDateTime::compareTo).orElse(null);
    }

    /** 主信号视图（levelName/levelHint 后端权威；level = 下中位档，count = 独立投票人数） */
    private CrowdSummary.CrowdLevelView femaleLevelView(CrowdVerdict v) {
        CrowdFemaleLevel female = CrowdFemaleLevel.of(v.medianLevel());
        return new CrowdSummary.CrowdLevelView(female.getLevel(), female.getDisplayName(),
                female.getAnchor(), v.voterCount(), round2(v.agreementShare()));
    }

    /** 次信号视图（男客 1-8，细粒度档位同女） */
    private CrowdSummary.CrowdLevelView maleLevelView(CrowdVerdict v) {
        CrowdMaleLevel male = CrowdMaleLevel.of(v.medianLevel());
        return new CrowdSummary.CrowdLevelView(male.getLevel(), male.getDisplayName(),
                male.getAnchor(), v.voterCount(), round2(v.agreementShare()));
    }

    private static double round2(double share) {
        return Math.round(share * 100.0) / 100.0;
    }

    /**
     * 主信号展示文案：「舞伴 约100 · 3 位舞友 · 1 小时前」；样本充足（SOLID）时带中间一半区间：
     * 「舞伴 约100（约80~约150）· 6 位舞友 · 刚刚」。
     */
    private String buildMainText(CrowdVerdict female, String ageText, CrowdTier tier) {
        String range = female.sampleTier() == SampleTier.SOLID && female.q1Level() != female.q3Level()
                ? "（" + CrowdFemaleLevel.of(female.q1Level()).getDisplayName() + "~"
                + CrowdFemaleLevel.of(female.q3Level()).getDisplayName() + "）"
                : "";
        String core = "舞伴 " + CrowdFemaleLevel.of(female.medianLevel()).getDisplayName() + range + " · "
                + female.voterCount() + " 位舞友 · " + ageText;
        if (tier == CrowdTier.CONFLICT) {
            return core + " · 请以现场为准";
        }
        return core;
    }

    /** 次信号展示文案：「男客 约50 · 2 人」（人数 = 报了男客档位的独立人数） */
    private String buildMaleText(CrowdVerdict male) {
        return "男客 " + CrowdMaleLevel.of(male.medianLevel()).getDisplayName() + " · " + male.voterCount() + " 人";
    }

    /** 单条上报的相对时间（「刚刚 / N 分钟前 / N 小时前」）；createdAt 为 null 时返回空串 */
    private String ageTextFor(LocalDateTime at) {
        return CrowdTimeText.ageText(at, LocalDateTime.now());
    }

    /**
     * 每个用户的上报明细（2026-08-29「表格式列表展示每个用户上报」；2026-09-03
     * 用户改判：详情页表格<b>直接展示用户头像 + 名称（超长省略）</b>——推翻
     * 「列表行不展示用户名」旧决策；列表页卡片仍维持匿名——公共面不点名，N人报过/
     * 最新上报行不带头像昵称）：
     * createdAt 倒序（最新在前）；male 未报时 maleLevelName/maleLevelHint 为 null；
     * badgeText = 上报者可信度权重分档（服务端权威，认领人恒为「店家」）；
     * nickname = 完整昵称（空兜底「匿名」）；avatarUrl = 头像（空 = 未设头像，
     * 前端首字占位）；isMine = 当前登录用户本人（高亮 +「我」标记，登录后回填）。
     * <p>
     * <b>2026-10-07 用户拍板：输入不再限有效期窗口</b>（数据源 = {@link #recentDetailRows}
     * 的最近 {@link CrowdPolicy#DETAIL_ROWS_LIMIT} 条）。每行 {@code expired} 如实标注是否
     * 已出窗口，前端据此置灰 +「已过期」——展示放宽，统计不退（统计仍只认窗口内，见
     * {@link #summary}）。
     */
    private List<CrowdSummary.CrowdReportRow> buildDetailRows(List<VenueCrowdReport> rows,
                                                              Map<Long, Double> weights,
                                                              Map<Long, User> users,
                                                              Long currentUserId,
                                                              Long claimantId,
                                                              CrowdLikeAggregates likes,
                                                              LocalDateTime now) {
        LocalDateTime windowStart = now.minusHours(CrowdPolicy.VALID_WINDOW_HOURS);
        return rows.stream()
                .sorted(Comparator.comparing(VenueCrowdReport::getCreatedAt).reversed())
                .map(r -> {
                    CrowdFemaleLevel female = CrowdFemaleLevel.of(r.getFemaleLevel());
                    CrowdMaleLevel male = r.getMaleLevel() != null
                            ? CrowdMaleLevel.of(r.getMaleLevel()) : null;
                    return new CrowdSummary.CrowdReportRow(
                            r.getUserId(),
                            CrowdTrustService.badgeFor(r.getUserId(), weights, claimantId),
                            nicknameOf(users.get(r.getUserId())),
                            avatarOf(users.get(r.getUserId())),
                            currentUserId != null && currentUserId.equals(r.getUserId()),
                            female.getDisplayName(), female.getAnchor(),
                            male != null ? male.getDisplayName() : null,
                            male != null ? male.getAnchor() : null,
                            r.getCreatedAt(),
                            ageTextFor(r.getCreatedAt()),
                            r.getId(),
                            likes.counts().getOrDefault(r.getId(), 0L).intValue(),
                            currentUserId != null && likes.liked().contains(r.getId()),
                            r.getCreatedAt() != null && r.getCreatedAt().isBefore(windowStart));
                })
                .toList();
    }

    /** 行级点赞聚合快照（详情页热度卡行「有用」按钮数据源；窗口/行数小，无缓存） */
    private CrowdLikeAggregates likeAggregates(List<VenueCrowdReport> rows, Long currentUserId) {
        List<Long> reportIds = rows.stream().map(VenueCrowdReport::getId).toList();
        Map<Long, Long> counts = crowdReportLikeService.likeCountsByReportIds(reportIds);
        Set<Long> liked = crowdReportLikeService.likedReportIds(currentUserId, reportIds);
        return new CrowdLikeAggregates(counts, liked);
    }

    /** 行级点赞聚合中间结果（赞数 + 我已赞的 reportId 集） */
    private record CrowdLikeAggregates(Map<Long, Long> counts, Set<Long> liked) {
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

    // ===== 确认后积分 + 反馈闭环（2026-09-03，docs/agents/27「确认后积分」） =====

    /**
     * 提交后确认重算 + 激励闭环（与 submit 同事务，任一失败整体回滚）：
     * <ol>
     *   <li><b>确认判定</b>：读 {@link CrowdVerdict#confirmed()}——与详情页 CONFIRMED 态是<b>同一个函数的
     *       同一次结论</b>（2026-10-07 前这里曾是另一份独立实现，靠注释维系一致）；</li>
     *   <li><b>确认后积分</b>：对结论里「与中位数一致」的上报者（一人一票，票上携带其代表行 id）逐人调用
     *       {@link PointsService#rewardCrowdConfirm}（幂等键 = 上报行 id，每行至多一次；V41 起每营业夜
     *       每人只有一行，同一夜不可能靠跨午夜重报拿两次）；触发者本人获奖 → outcome 标记
     *       （提交响应即时展示），其余获奖者 → 站内信 CROWD_CONFIRMED（不含触发者）；</li>
     *   <li><b>收藏联动（受众放大）</b>：该店<b>本次提交前未达确认</b>、提交后首次达成 → 给收藏该店的
     *       用户发联动站内信（跳过触发者与已收确认信的上报者，每店每晚仅首次达成触发一次）。</li>
     * </ol>
     * 认领人不在票里，因此既不会被确认也不会拿奖励。
     */
    private ConfirmOutcome confirmAndReward(Venue venue, Long actorId, boolean wasConfirmedBefore,
                                            LocalDateTime now) {
        Long venueId = venue.getId();
        CrowdVerdict verdict = validWindow(venueId, venue.getClaimedBy(), now).female();
        if (!verdict.confirmed()) {
            return ConfirmOutcome.none();
        }
        List<CrowdVote> agreeing = verdict.agreeingVotes();
        int agreeCount = agreeing.size();
        int reward = pointsService.crowdConfirmReward();
        Set<Long> newlyRewardedIds = new HashSet<>();
        boolean actorRewarded = false;
        for (CrowdVote vote : agreeing) {
            Long reportUserId = vote.userId();
            // 发放（幂等：该行已拿过确认奖 → null，跳过；已发放用户权重可能因
            // 新流水提升——显式失效其权重缓存，供本次提交的升级检测读到新鲜值）
            Long balance = pointsService.rewardCrowdConfirm(reportUserId, vote.sourceId());
            if (balance == null) {
                continue;
            }
            crowdTrustService.invalidate(reportUserId);
            newlyRewardedIds.add(reportUserId);
            if (reportUserId.equals(actorId)) {
                actorRewarded = true; // 触发者本人：提交响应即时告知，不发站内信
            } else {
                messageService.create(reportUserId, MessageType.CROWD_CONFIRMED,
                        "今晚热度已确认",
                        "您在「" + venue.getName() + "」的今晚热度上报已被 " + agreeCount
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
                        "您收藏的「" + venue.getName() + "」今晚热度已被 " + agreeCount
                                + " 位舞友确认（数据仅供参考）",
                        RELATED_TYPE_VENUE, venueId);
            }
        }
        return new ConfirmOutcome(actorRewarded, agreeCount);
    }

    /** 确认激励结果（submit 响应即时反馈数据源） */
    private record ConfirmOutcome(boolean newlyRewarded, int agreeCount) {
        static ConfirmOutcome none() {
            return new ConfirmOutcome(false, 0);
        }
    }

    /**
     * 列表角标批量生成（2026-08-29，VenueService.listVenues 调用）：
     * 一次 IN + GROUP BY 覆盖整页（防 N+1），返回 venueId → 中性文案「N人报过」。
     * 门槛 = 最近 {@link CrowdPolicy#VALID_WINDOW_HOURS}h 窗口独立上报人数 ≥ {@link CrowdPolicy#BADGE_MIN_VOTERS}（3）——
     * 列表是公共面，&lt;3 人不上（防误伤/防商家找两三个朋友刷「火爆」）；
     * 文案中性不带档位词（「热闹/冷清」不上列表——给门店贴正负定性有商家争议
     * 与数据误伤风险，具体档位留给详情页，同一事实只呈现一次）。
     * 认领人上报不计入（2026-10-07，SQL 层排除）。
     * <p>
     * 2026-08-30 性能优化：人数经 {@link #badgeCountsCache} 缓存（TTL 30s）——
     * 逐店缓存 + 批量回源，上报写路径显式失效（{@link #invalidateVenueCrowdCaches}）。
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
                if (count >= CrowdPolicy.BADGE_MIN_VOTERS) {
                    badges.put(venueId, count + "人报过");
                }
            } else {
                misses.add(venueId);
            }
        }
        if (!misses.isEmpty()) {
            LocalDateTime since = LocalDateTime.now().minusHours(CrowdPolicy.VALID_WINDOW_HOURS);
            for (Object[] row : crowdReportRepository.countDistinctUsersByVenueIdsSince(misses, since)) {
                // 原生 SQL 返回的数值列类型由驱动决定（BIGINT → Long，但不同驱动 / 版本可能是 BigInteger）：
                // 一律经 Number 转，禁直接强转（2026-10-06 到访调度 ClassCastException 事故同款）
                Long venueId = ((Number) row[0]).longValue();
                Long count = ((Number) row[1]).longValue();
                badgeCountsCache.put(venueId, count);
                if (count >= CrowdPolicy.BADGE_MIN_VOTERS) {
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
     *   <li>**不公开昵称**：标识由上报者可信度权重分档（服务端权威，资深/常客/普通 + 「舞友」后缀——
     *       列表无表头，需自解释）；</li>
     *   <li>展示门槛：窗口内有上报即返回（「有人刚报过」是事实非结论，
     *       与 ≥3 人角标语义解耦、互补：胶囊 = 多少人，本行 = 最新动态）；</li>
     *   <li>认领人的上报不参与（2026-10-07，SQL 层排除）。</li>
     * </ul>
     * 返回 venueId → 文案；无上报的店不在 map（前端 null 不渲染）。
     * <p>
     * 2026-08-30 性能优化：最新上报行经 {@link #latestReportsCache} 缓存（TTL 30s，
     * 只缓存 userId + createdAt——相对时间文案渲染时实时计算，缓存期不失真）；
     * 上报写路径显式失效（{@link #invalidateVenueCrowdCaches}）。
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
            LocalDateTime since = LocalDateTime.now().minusHours(CrowdPolicy.VALID_WINDOW_HOURS);
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
        Map<Long, Double> weights = crowdTrustService.weights(userIds);
        Map<Long, String> texts = new HashMap<>();
        for (Map.Entry<Long, LatestReport> e : latestByVenue.entrySet()) {
            LatestReport entry = e.getValue();
            texts.put(e.getKey(),
                    ageTextFor(entry.createdAt()) + " · "
                            + CrowdTrustService.badgeFor(entry.userId(), weights, null) + "舞友上报");
        }
        return texts;
    }

    // ===== 管理端 =====

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
        Map<Long, Double> weights = crowdTrustService.weights(userIds);
        List<AdminCrowdReportSummary> summaries = new ArrayList<>();
        for (Map.Entry<Long, List<VenueCrowdReport>> e : byVenue.entrySet()) {
            List<VenueCrowdReport> venueRows = e.getValue();
            summaries.add(buildAdminSummary(e.getKey(),
                    venueNames.getOrDefault(e.getKey(), "门店" + e.getKey()),
                    venueRows, nicknames, weights));
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
        Long claimantId = claimantOf(venueId);
        Set<Long> userIds = rows.stream().map(VenueCrowdReport::getUserId).collect(Collectors.toSet());
        Map<Long, Double> weights = crowdTrustService.weights(userIds);
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
                            CrowdTrustService.badgeFor(r.getUserId(), weights, claimantId),
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
     * 删除后用户同一营业夜可重新上报：唯一键谓词 WHERE deleted=0，删除行不再命中约束
     * → 再次 upsert 生成新行（管理员删了错误记录，用户可报回正确数据）。
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
                                                      Map<Long, String> nicknames,
                                                      Map<Long, Double> weights) {
        // 档位分布（按条数，降序；male 用 CrowdMaleLevel 解析，勿复用 female 枚举）
        List<AdminCrowdReportSummary.LevelCount> femaleDist = femaleDistribution(rows);
        List<AdminCrowdReportSummary.LevelCount> maleDist = maleDistribution(rows);
        // 「说法不一」与详情页同一个判定函数（一人一票取最新 + 中位数 ±1 档）；运营视角看原始事实，
        // 所以不剔除认领人（ownerId = null）。旧实现是「众数条数占比 < 0.6」——又一份独立口径。
        boolean conflict = CrowdConsensus.isConflicting(CrowdConsensus.evaluate(CrowdConsensus.latestPerVoter(
                votesOf(rows, null, weights, VenueCrowdReport::getFemaleLevel))));
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
