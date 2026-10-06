package org.quwuting.quwutingservice.venuepresence.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.spend.enums.WireEnums;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.enums.VenueType;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.dto.request.ReportPresenceRequest;
import org.quwuting.quwutingservice.venuepresence.dto.response.PresenceReportResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceConsentStats;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceStats;
import org.quwuting.quwutingservice.venuepresence.entity.VenuePresenceConsent;
import org.quwuting.quwutingservice.venuepresence.enums.CoLocatedAttribution;
import org.quwuting.quwutingservice.venuepresence.enums.ConsentSource;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 门店到访痕迹服务（2026-09-29，V33；文档 = docs/agents/52-venue-presence.md）。
 * <p>
 * <b>定位</b>：一行 ping = 一次「可证实的到店事实」，只服务 admin 展示与后续打标，
 * <b>不进热度公式</b>（到店数天然随曝光增长，线性进公式即马太闭环——热度四问判据
 * 第 3 问不过；待数据量起来后按 05 号文档流程另行评估）。
 * <p>
 * <b>写宽松读严格</b>：写入侧只做协议限幅（防脏数据），「到访 / 附近 / 同址 / 营业归因」口径
 * （{@link #HIT_RADIUS_M} / {@link #HIT_MAX_ACCURACY_M} / {@link #NEARBY_RADIUS_M} /
 * {@link #CO_LOCATED_RADIUS_M} / {@link #isInOperation}）全部在查询侧判定——门店坐标是地址级
 * 地理编码（同楼多店坐标重合，室内偏离 50~150m），阈值定错时历史数据可回溯（2026-10-03 即据此回溯修复）。
 * <p>
 * <b>同意门禁（2026-10-03 五轮）</b>：只收「最新一条状态确立是用户显式开启」的用户的 ping
 * （{@link #isExplicitlyEnabled}）——服务端是「先有同意、后有足迹」证据链的唯一收敛点，
 * 旧版默认开启端未经询问的采集在上线即被拒收，不依赖端上升级覆盖率（52 号 §5）。
 * <p>
 * <b>隐私红线</b>：本域数据面 = (venueId, distanceM, accuracyM) 三个标量 +
 * 开关偏好布尔（V34，consent 流水——不含任何位置信息），用户经纬度在协议上
 * 不存在（端侧经 /venues/nearby 取服务端算好的距离后原样回传）。
 * <p>
 * <b>信任边界</b>：distance_m 是端侧自报值，服务端不复算（复算需要坐标，与红线冲突）。
 * 防刷面 = 伪造 distance 刷「到访」；缓解 = 15 分钟桶幂等（本表唯一约束）+ 每用户
 * 滑动窗口频控 + admin 侧距离分布观察。完整论证见 52 号文档 §「信任边界」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenuePresenceService {

    /*
     * 采集总开关（常量定义在 OpsConfigService#KEY_PRESENCE_COLLECT_ENABLED，
     * V33 迁移插入默认行 true）：关闭后上报端点返回 accepted=false(DISABLED)
     * 而非报错——客户端对失败静默，开关只影响「新数据是否进库」，历史数据不受影响。
     * 提审/隐私争议时可一键停采。
     */

    /**
     * 写幂等桶宽度（分钟，协议常量）：采样主力 = onShow（每次打开一次），典型会话
     * 数分钟级；15 分钟 ≈ 会话粒度上界，桶内重复 onShow 一律吸收。调整需发版
     * （唯一键语义随桶宽变化，运行期改宽会出现「同桶已写、新桶再写」的口径漂移）。
     */
    public static final int WRITE_WINDOW_MINUTES = 15;

    /**
     * 写入距离限幅（米，协议常量）：与 /venues/nearby 的采集半径同量级——超出
     * 500m 的「命中」不可能是真到店（更可能是端侧伪造或逻辑错），拒收防脏数据。
     * 口径过滤（150m/300m）在查询侧，本限幅只是写侧卫生。
     */
    public static final int WRITE_MAX_DISTANCE_M = 500;

    /** 写入精度限幅（米，协议常量）：wx 端 accuracy 常见 5~65m，>500m 的定位无判定价值 */
    public static final int WRITE_MAX_ACCURACY_M = 500;

    /**
     * 到访命中半径（米，口径参数 = 「用户 ↔ 门店坐标」的容差）。
     * <p>
     * <b>2026-10-03 由 20m 改为 150m（根因修复，52 号 §4.1）</b>：原 20m 把两个不同的量
     * 混成了一个——它的论据「20m 内有邻居的店仅 5.4%」是<b>门店 ↔ 门店</b>间距统计
     * （回答「归因唯一吗」），却被用作<b>用户 ↔ 门店</b>的命中容差（回答「人在店里时
     * 离坐标多远」），而后者的误差预算完全不同：
     * <ul>
     *   <li>门店坐标是<b>地址级地理编码</b>（商场/大楼的锚点），不是原假设的
     *       「wx.chooseLocation 人工选点 10~30m」——同楼多店坐标完全重合即为铁证；</li>
     *   <li>室内定位（商场 3 层、无 GPS 直视）的实际偏离远大于 wx 回报的 accuracy。</li>
     * </ul>
     * 现网证据（截至 2026-10-03 共 21 条 ping）：需求方 10-02 夜在南通五洲国际广场现场
     * 连续 4 条均为 86~91m，其余疑似在店样本 56~147m；20m 口径只留下 3 条（2 个 UV），
     * <b>到访统计几乎全 0</b>。
     * 距离分布在 147m 与 159m 之间出现自然断点（之后是 202m / 301m 的路过样本），
     * 且 150m 落在 Android 地理围栏官方建议下限 100~150m 区间内。
     * <p>
     * 写宽松读严格 ⇒ 改值对历史 ping 立即回溯生效，无需重采。复核方法（数据量起来后
     * 按此重标定，禁凭直觉改）：见 52 号 §4.3 标定 SQL。
     * <p>
     * <b>镜像</b>（改值三处同改，52 号 §4 参数表）：admin-web {@code PRESENCE_HIT_RADIUS_M}（列表口径
     * 提示）、小程序 {@code ARRIVAL_PROMPT_RADIUS_M}（「真正到店」才首问的触发半径）。
     */
    public static final int HIT_RADIUS_M = 150;

    /**
     * 到访命中的精度门槛（米）：<b>派生于 {@link #HIT_RADIUS_M}，不是独立口径参数</b>。
     * 判据 = 定位自身的不确定半径大于判定圆时，这次定位在物理上就无法回答「在不在
     * 圈内」——门槛只该排除这类「无判定能力」的定位。
     * <p>
     * 原值 30m（「超过即城市级误差」）是错误前提：wx 端室内 accuracy 常见 30~65m
     * （iOS 无 GPS 时回报 65、Android 常见 35），30m 门槛恰好系统性剔除了最该被
     * 统计的室内样本。NULL 精度视为达标（判据见 Repository 口径注释）。
     */
    public static final int HIT_MAX_ACCURACY_M = HIT_RADIUS_M;

    /**
     * 同址半径（米，口径参数 = 「门店 ↔ 门店」的<b>定位不可分辨</b>距离；2026-10-03 新增，同日 20 → 50m）。
     * <p>
     * 两家店坐标间距 ≤ 本值 ⇒ 手机定位判断不了用户在哪一家，归因改用定位以外的事实（营业状态，
     * 52 号 §4.4），分不出时共享（组内用户并集）——而不是让 nearby 的「最近一家」替用户掷骰子。
     * 这里的「同址」= <b>定位上分不开</b>，不等于同一门牌：同楼不同层、同楼不同门牌、紧邻的两栋楼，
     * 对归因是同一个问题。
     * <p>
     * <b>20 → 50m 的根因（52 号 §4.2，勿重蹈）</b>：20m 的前提「同楼的店坐标重合；20~50m 已进入定位可分辨
     * 的量级」只对<b>同一地址字符串</b>编码出的坐标成立（丽莎 / 一壶淡泊逐位相同）。同一栋楼用不同写法的地址
     * 编码时锚点会散开：南通京扬广场「寻梦缘（校西路…京扬广场2楼）/ 抖舞（人民中路209号京扬数码城B座）/
     * 南来北往（人民中路209号）」实际同层、寻梦缘与抖舞面对面不到 10m，坐标却两两相距 27 / 39 / 44m——
     * 20m 把它们当成三家可区分的店，在楼里的用户（331：距南来北往坐标 20m、精度 15m）被记给已停业的
     * 南来北往。与命中半径 20m 是同一类错误：阈值的前提从未对数据检验过。
     * <p>
     * <b>标定（现网 1301 家有坐标门店，2026-10-03，复核 SQL = 52 号 §4.3 ④）</b>：
     * <ul>
     *   <li>地址指向同一楼 / 商场、且至少一家在营的门店对，坐标间距 27~44m（京扬 27/39/44、富江商业广场 32、
     *       书院万达 44）⇒ 取上界向上到 10m 档 = 50m；</li>
     *   <li>同楼散布的长尾（65~92m：阳光天地、力宝广场、杉杉 IN 象、联盛广场）目前<b>双方都不在营</b>，
     *       不影响归因——有在营门店落进这一段时按 §4.3 ④ 重标定；</li>
     *   <li>代价 = 都在营、只能共享的门店对：20m 2 对 → 50m 5 对；按营业状态即可分清的对 15 → 22。
     *       100m 会共享 12 对，多为确实不同楼的邻居，故不取。</li>
     * </ul>
     * 不变量：本值 &lt; {@link #HIT_RADIUS_M}（两个量回答的问题不同，见命中半径注释）。
     */
    public static final int CO_LOCATED_RADIUS_M = 50;

    /**
     * 「附近」覆盖半径（米，口径参数）：对齐 GET /venues/nearby 的缺省 300m——
     * 同一「附近」语义在采集与统计两侧共用一个值，避免第二份真值。
     */
    public static final int NEARBY_RADIUS_M = 300;

    /**
     * 「在营」状态集（2026-10-03，52 号 §4.4 营业状态消歧）：可被到访归因的门店状态。
     * <p>
     * 判据 = 「人此刻可能在这家店里」：OPEN 显然；CLOSED（休息中）是<b>短期态</b>（今天不开 ≠ 这家店
     * 不存在），按在营处理——否则一次休息日就把整个 30 天窗口的证据让给邻居。RENOVATING / SUSPENDED /
     * CEASED 是长期不在营：同址仍有在营门店时，该位置的证据不可能属于它们。
     * <p>
     * 已知局限（接受）：按<b>当前</b>状态归因、不按 ping 当时的状态——门店状态变更低频，且由每日同步
     * 维持新鲜度；查询侧归因让状态更正（如 CEASED→OPEN 反转）立即回溯生效。状态数据本身失真时归因随之
     * 失真，admin 详情页同屏展示同址门店状态，运营可据此识别。
     */
    private static final Set<VenueStatus> IN_OPERATION_STATUSES = EnumSet.of(VenueStatus.OPEN, VenueStatus.CLOSED);

    /** 每用户写频控（次/窗口，协议常量）：桶幂等已限 (user, venue) 粒度，此处限用户总写入速率 */
    private static final int WRITE_RATE_LIMIT = 10;
    private static final long WRITE_RATE_WINDOW_MS = 60_000L;

    /**
     * 排序口径的到访窗口（天，2026-10-06 V38）：与热度公式其余各项同窗（近 30 天滚动）。
     * 改本值必须与 V38 注释 / 52 号文档 / {@code VenueHeatWeights.VISIT} 论证同步。
     */
    private static final int RANKING_WINDOW_DAYS = 30;

    /** 展示用的近 7 天窗口（天）：物化表同列下发，**不进公式**（避免第二处时间项） */
    private static final int VISIT_RECENT_WINDOW_DAYS = 7;

    /**
     * 排除集合为空时的哨兵（2026-10-06）：{@code userId NOT IN :excludedUserIds} 在空集合上
     * 会生成 {@code NOT IN ()} 语法错误。公式侧由 {@code HeatAccountExclusionService} 恒非空保证，
     * 本类不依赖该服务（分层），故自带同款防御——哨兵是负数 id，任何真实用户都不可能命中。
     */
    private static final long NO_EXCLUSION_SENTINEL = -1L;

    private final VenuePresencePingRepository pingRepository;
    private final VenuePresenceConsentRepository consentRepository;
    private final VenueRepository venueRepository;
    private final OpsConfigService opsConfigService;

    /**
     * 每用户写入频控（滑动窗口）：键 = userId，值 = 窗口内写入时刻队列。
     * 15 分钟桶幂等挡不住「跨店狂刷」，此处兜底用户级速率。合法流量（每次打开
     * 至多 1 条）距上限差两个数量级。
     */
    private final Cache<Long, Deque<Long>> writeRateCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    // ── 写侧 ────────────────────────────────────────────────────────────────────

    /**
     * 上报一次到访痕迹（POST /venues/{venueId}/presence 的实现）。
     * 调用方必须已 {@code UserContext.requireAuth()}（重放安全不变量：鉴权在副作用之前）。
     * <p>
     * 判定序：运营总开关 → 用户写频控 → <b>同意门禁</b> → 门店与参数校验 → 桶幂等写入。
     * 门禁不通过返回 {@code accepted=false(CONSENT_REQUIRED)} 而非错误码：旧版默认开启端对失败
     * 静默，拒收不该在它们的日志里制造 4xx 噪音。
     */
    @Transactional
    public PresenceReportResponse report(Long venueId, Long userId, ReportPresenceRequest request) {
        if (!opsConfigService.isEnabled(OpsConfigService.KEY_PRESENCE_COLLECT_ENABLED, true)) {
            return new PresenceReportResponse(false, PresenceReportResponse.REASON_DISABLED);
        }
        if (exceedsWriteRate(userId)) {
            throw new BusinessException(1022, "上报过于频繁，请稍后再试");
        }
        if (!hasExplicitConsent(userId)) {
            return new PresenceReportResponse(false, PresenceReportResponse.REASON_CONSENT_REQUIRED);
        }
        Venue venue = venueRepository.findById(venueId)
                .filter(v -> !v.isDeleted())
                .orElseThrow(() -> new BusinessException(1022, "门店不存在"));
        // 歌友会（cityOnlyAddress）坐标在写路径被主动清空，端侧经 nearby 永远拿不到它；
        // 直连接口伪造 distance 的路径在此封死——无坐标门店不在采集范围（52 号文档 §边界）
        if (venue.getVenueType() == VenueType.SONG_CLUB
                || venue.getLatitude() == null || venue.getLongitude() == null) {
            throw new BusinessException(1022, "该门店不参与到访采集");
        }
        Integer distanceM = request.distanceMeters();
        if (distanceM == null || distanceM < 0 || distanceM > WRITE_MAX_DISTANCE_M) {
            throw new BusinessException(1022, "距离参数越界");
        }
        Integer accuracyM = request.accuracyMeters();
        if (accuracyM != null && (accuracyM < 0 || accuracyM > WRITE_MAX_ACCURACY_M)) {
            throw new BusinessException(1022, "精度参数越界");
        }
        // UTC epoch 分钟派生，与时区无关（V33 迁移头注 §防刷与幂等）
        long bucket = System.currentTimeMillis() / 60_000L / WRITE_WINDOW_MINUTES;
        pingRepository.upsertInBucket(userId, venueId, bucket, distanceM, accuracyM, LocalDateTime.now());
        return new PresenceReportResponse(true, null);
    }

    /**
     * 记录一次状态确立（POST /venues/presence-consent 的实现）：设置页拨动开关（USER）或到店首问
     * 回答（PROMPT）各插一行流水（不 upsert——保留变更历史才能回答「近期变更热度」与「首问效果」；
     * 当前态由查询侧「每用户最新一条」口径派生）。
     * <p>
     * enabled 缺失按 1022 拒绝（禁猜默认值）；来源解析见 {@link #resolveReportedSource}。
     */
    @Transactional
    public void recordConsent(Long userId, Boolean enabled, String sourceRaw) {
        if (enabled == null) {
            throw new BusinessException(1022, "缺少开关状态");
        }
        VenuePresenceConsent consent = new VenuePresenceConsent();
        consent.setUserId(userId);
        consent.setEnabled(enabled);
        consent.setSource(resolveReportedSource(sourceRaw));
        // created_at/updated_at 由 BaseEntity 的 @CreationTimestamp/@UpdateTimestamp 托管
        consentRepository.save(consent);
    }

    /**
     * 端上声明的确立来源：缺失 = USER（协议历史——10-03 前的端只在设置页上报且不带来源，这不是猜测）；
     * 可识别的显式来源原样采用；DEFAULT（服务端历史补记来源，端上无权声明）或无法识别的值按 1022 拒绝。
     */
    static ConsentSource resolveReportedSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return ConsentSource.USER;
        }
        ConsentSource parsed = WireEnums.parse(ConsentSource.class, raw);
        if (parsed == null || !parsed.isExplicit()) {
            throw new BusinessException(1022, "非法的开关来源");
        }
        return parsed;
    }

    /**
     * 同意判据（单点）：最新一条状态确立是<b>用户显式开启</b>——采集门禁与 admin「已允许」口径共用。
     * DEFAULT 来源（默认开启期补记、用户从未被问过）即使 enabled=true 也不构成同意。
     */
    public static boolean isExplicitlyEnabled(Boolean enabled, ConsentSource source) {
        return Boolean.TRUE.equals(enabled) && source != null && source.isExplicit();
    }

    private boolean hasExplicitConsent(Long userId) {
        return consentRepository.findFirstByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(userId)
                .map(c -> isExplicitlyEnabled(c.getEnabled(), c.getSource()))
                .orElse(false);
    }

    // ── 开关统计 ────────────────────────────────────────────────────────────────

    /**
     * 开关统计（admin 门店列表页头）。当前态 = 每用户最新一条 consent 行，按
     * {@link #isExplicitlyEnabled} 分为 已允许 / 已关闭 / 待补问（最新态仍是历史 DEFAULT）；
     * 另附首问回答分布与近 30 天设置变更次数。数据量级 = 用户数 × 变更次数（千级行），
     * native 窗口函数一条 SQL 出分布，见
     * {@code VenuePresenceConsentRepository#countLatestByEnabledAndSource}。
     */
    public VenuePresenceConsentStats consentStats() {
        long enabledUsers = 0;
        long disabledUsers = 0;
        long legacyDefaultUsers = 0;
        for (Object[] row : consentRepository.countLatestByEnabledAndSource()) {
            boolean enabled = Boolean.TRUE.equals(row[0]);
            ConsentSource source = WireEnums.parse(ConsentSource.class, String.valueOf(row[1]));
            long users = ((Number) row[2]).longValue();
            if (isExplicitlyEnabled(enabled, source)) {
                enabledUsers += users;
            } else if (enabled) {
                legacyDefaultUsers += users;
            } else {
                disabledUsers += users;
            }
        }
        long promptAllowed = 0;
        long promptDeclined = 0;
        for (Object[] row : consentRepository.countPromptAnswersByEnabled()) {
            long users = ((Number) row[1]).longValue();
            if (Boolean.TRUE.equals(row[0])) {
                promptAllowed += users;
            } else {
                promptDeclined += users;
            }
        }
        long changes30d = consentRepository.countUserChangesSince(LocalDateTime.now().minusDays(30));
        return new VenuePresenceConsentStats(enabledUsers, disabledUsers, legacyDefaultUsers,
                promptAllowed, promptDeclined, changes30d);
    }

    // ── 读侧：到访统计 ──────────────────────────────────────────────────────────

    /**
     * 单店到访统计（admin 详情卡）。到访人数 / 最近到访 = {@link #visitSummaries} 同一计算；
     * 附近人数是片区语义（这一带出现过多少人），归属范围恒为「本店 + 全部同址门店」、不做营业归因。
     * 口径参数与同址归因随响应回显，admin 端展示时必须与数值同屏（统计量不带口径 = 邀请误读）。
     */
    public VenuePresenceStats statsFor(Long venueId) {
        LocalDateTime now = LocalDateTime.now();
        Attribution attribution = attributionsFor(List.of(venueId)).get(venueId);
        Map<Long, Map<Long, LocalDateTime>> hitLastSeen = lastSeenByVenue(attribution.evidenceVenueIds(), HIT_RADIUS_M);
        VenueVisitSummary summary = summarize(attribution, hitLastSeen, now);
        Map<Long, Map<Long, LocalDateTime>> nearbyLastSeen = lastSeenByVenue(attribution.areaVenueIds(), NEARBY_RADIUS_M);
        long nearby30d = countSince(unionLastSeen(attribution.areaVenueIds(), nearbyLastSeen), now.minusDays(30));
        List<VenuePresenceStats.CoLocatedVenue> peers = attribution.peers().stream()
                .map(p -> new VenuePresenceStats.CoLocatedVenue(
                        p.id(), p.name(), displayOf(p.status()), isInOperation(p.status())))
                .toList();
        return new VenuePresenceStats(summary.visitUsers7d(), summary.visitUsers30d(), nearby30d,
                summary.lastVisitAt(), HIT_RADIUS_M, NEARBY_RADIUS_M, CO_LOCATED_RADIUS_M,
                summary.coLocatedAttribution(), peers);
    }

    /**
     * 批量到访摘要（admin 列表一页）：同址解析 1 次 + 命中查询 1 次覆盖整页，防 N+1。
     * 返回<b>每个</b>入参门店的摘要（无到访也有一行——归因方式本身是要展示的信息）。
     */
    public Map<Long, VenueVisitSummary> visitSummaries(Collection<Long> venueIds) {
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Attribution> attributions = attributionsFor(venueIds);
        Set<Long> evidence = new LinkedHashSet<>();
        attributions.values().forEach(a -> evidence.addAll(a.evidenceVenueIds()));
        Map<Long, Map<Long, LocalDateTime>> lastSeen = lastSeenByVenue(evidence, HIT_RADIUS_M);
        Map<Long, VenueVisitSummary> result = new LinkedHashMap<>();
        attributions.forEach((id, a) -> result.put(id, summarize(a, lastSeen, now)));
        return result;
    }

    /**
     * 全部「有过到访」门店的摘要（admin 按足迹排序 / 只看有足迹，52 号 §6.1）：从全量命中证据出发，
     * 候选 = 有证据的门店 + 它们的同址邻居（邻居可能经共享 / 并入获得到访），再走与
     * {@link #visitSummaries} 完全相同的归因与汇总——两条路径对同一家店必须给出同一组数字。
     * 只返回 lastVisitAt 非空的门店（稀疏结果；缺席 = 从无到访）。
     */
    public Map<Long, VenueVisitSummary> visitedVenueSummaries() {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Map<Long, LocalDateTime>> lastSeen =
                toLastSeenByVenue(pingRepository.findVisitorLastSeen(HIT_RADIUS_M, HIT_MAX_ACCURACY_M));
        if (lastSeen.isEmpty()) {
            return Map.of();
        }
        Set<Long> candidates = new LinkedHashSet<>(lastSeen.keySet());
        for (VenueRepository.CoLocatedVenueRow row
                : venueRepository.findCoLocatedPairs(lastSeen.keySet(), CO_LOCATED_RADIUS_M)) {
            candidates.add(row.getCoLocatedId());
        }
        Map<Long, VenueVisitSummary> result = new LinkedHashMap<>();
        attributionsFor(candidates).forEach((id, a) -> {
            VenueVisitSummary summary = summarize(a, lastSeen, now);
            if (summary.lastVisitAt() != null) {
                result.put(id, summary);
            }
        });
        return result;
    }

    /** 在营判定（可被到访归因的门店状态，{@link #IN_OPERATION_STATUSES}）；状态无法识别时按在营处理（不凭未知让渡证据） */
    public static boolean isInOperation(VenueStatus status) {
        return status == null || IN_OPERATION_STATUSES.contains(status);
    }

    // ── 读侧：排序口径到访份额（2026-10-06，V38） ─────────────────────────────────

    /**
     * 排序口径的到访份额：**可配排除账号 + 同址分摊 + 不在营记 0**，供定时刷新任务写入
     * {@code qwt_venue_visit_metrics}（唯一消费方 = {@code VenueVisitMetricsScheduler}）。
     * <p>
     * <b>为什么必须由本类产出、不能由公式侧自己算</b>：到访人数是派生量
     * （命中谓词 × 同址组几何 × 双方营业状态 × 用户并集），其中归因是 Java 侧计算，
     * JPQL 无 FROM 派生表能力 ⇒ 公式只能读物化结果。见 {@code VenueVisitMetric} 类注释。
     * <p>
     * <b>与 admin 展示口径 {@link #visitedVenueSummaries()} 的三处分叉</b>（有意，见
     * {@link VenueVisitShare}）：排除集合、分摊（1/k）而非共享、不在营门店不产出。
     * <p>
     * 时间窗只取 30 天（{@link #RANKING_WINDOW_DAYS}）：7 天份额由同一份命中集在内存里
     * 二次判定，不额外查库（"用户在某窗口内到访过 ⟺ 其最近命中时刻 ≥ 窗口起点"）。
     *
     * @param excludedUserIds 排除账号集合（恒非空由调用方保证；本方法对空集合退化为
     *                        哨兵值，避免 {@code NOT IN ()} 语法错误——与公式侧同款防御）
     * @return venueId → 份额；<b>缺席 = 排序记 0</b>（无到访 / 已让渡 / 不在营）
     */
    @Transactional(readOnly = true)
    public Map<Long, VenueVisitShare> visitSharesForRanking(Collection<Long> excludedUserIds) {
        LocalDateTime now = LocalDateTime.now();
        Collection<Long> exclusions = (excludedUserIds == null || excludedUserIds.isEmpty())
                ? List.of(NO_EXCLUSION_SENTINEL) : excludedUserIds;
        LocalDateTime windowStart = now.minusDays(RANKING_WINDOW_DAYS);
        Map<Long, Map<Long, LocalDateTime>> lastSeen = toLastSeenByVenue(
                pingRepository.findVisitorLastSeenSinceExcluding(
                        windowStart, HIT_RADIUS_M, HIT_MAX_ACCURACY_M, exclusions));
        if (lastSeen.isEmpty()) {
            return Map.of();
        }
        // 到访次数（V39）：单独取「去重到访日」明细。⛔ 不能从 lastSeen 推——那里面一天
        // 的多次命中已被压成 MAX(createdAt)，"来过几天"的信息不可恢复（同 PingRepository
        // 注释）。与人数共用同一套排除集与窗口起点，两列因此永远同窗。
        Map<Long, Map<Long, Set<LocalDate>>> visitDays = toVisitDaysByVenue(
                pingRepository.findVisitorDaysSinceExcluding(
                        windowStart, HIT_RADIUS_M, HIT_MAX_ACCURACY_M, exclusions));
        // 候选 = 有证据的门店 + 它们的同址邻居（邻居可能经共享 / 并入获得到访），
        // 与 admin 全量路径同一手法（52 号 §6.1）
        Set<Long> candidates = new LinkedHashSet<>(lastSeen.keySet());
        for (VenueRepository.CoLocatedVenueRow row
                : venueRepository.findCoLocatedPairs(lastSeen.keySet(), CO_LOCATED_RADIUS_M)) {
            candidates.add(row.getCoLocatedId());
        }
        Map<Long, VenueVisitShare> result = new LinkedHashMap<>();
        attributionsFor(candidates).forEach((id, attribution) -> {
            // 不在营 ⇒ 排序记 0：人不可能"到店"一家停业门店。展示口径保留该证据
            // 作为门店状态复核线索（48 号域价值），回归到排序的只有这一条
            if (!attribution.selfInOperation()) {
                return;
            }
            Map<Long, LocalDateTime> users = unionLastSeen(attribution.evidenceVenueIds(), lastSeen);
            if (users.isEmpty()) {
                return;
            }
            // 分摊分母 = 组内在营门店数（含本店，≥1）：同址组都在营 ⇒ 每位用户 1/k。
            // 与「共享」（每家都记满）的差别正是本表要防的"同楼双吃"（52 号 §1.1 第 1 条）
            double share = 1.0 / Math.max(1, attribution.inOperationCount());
            // 次数 = 证据门店的「(人, 日) 二元组并集」大小 × 同一 share（⛔ 不是日期并集：
            // 那会把"谁来的"压掉 —— 6 位用户散在 5 天会被并成 5 次，见 countVisitEvents）
            long eventCount = countVisitEvents(attribution.evidenceVenueIds(), visitDays);
            result.put(id, new VenueVisitShare(
                    scaleVisits(countSince(users, windowStart) * share),
                    scaleVisits(countSince(users, now.minusDays(VISIT_RECENT_WINDOW_DAYS)) * share),
                    scaleVisits(eventCount * share),
                    attribution.areaVenueIds().size()));
        });
        return result;
    }

    /** 份额落库精度（decimal(8,2)）：1/k 分之后四舍五入到 2 位，避免把 1/3 存成无限小数 */
    private static BigDecimal scaleVisits(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    // ── 同址归因 ────────────────────────────────────────────────────────────────

    /** 同址邻居（几何 + 状态事实，来自 {@link VenueRepository#findCoLocatedPairs}） */
    private record Peer(Long id, String name, VenueStatus status) {
    }

    /**
     * 一家店的归因结论。
     *
     * @param kind             同址归因方式
     * @param peers            同址邻居（不含本店）
     * @param evidenceVenueIds 到访证据取自哪些门店上的 ping（YIELDED = 空 ⇒ 本店计 0）
     * @param areaVenueIds     片区范围（本店 + 全部同址门店；附近人数用，不做营业归因）
     * @param selfInOperation  本店是否在营（2026-10-06 新增，V38）：**仅排序口径消费**——
     *                         不在营的门店排序记 0（人不可能"到店"一家停业门店）；admin 展示
     *                         不消费本字段（保留证据作为门店状态复核线索，见 52 号「到访进排序」）
     * @param inOperationCount 组内在营门店数（含本店，2026-10-06 新增）：**排序口径的分摊分母**——
     *                         同址组都在营时每位用户按 1/k 分给 k 家在营店，避免同楼各家
     *                         各吃一份整楼人流（52 号 §1.1 第 1 条）
     */
    private record Attribution(CoLocatedAttribution kind, List<Peer> peers,
                               Set<Long> evidenceVenueIds, Set<Long> areaVenueIds,
                               boolean selfInOperation, int inOperationCount) {
    }

    /**
     * 归因判定（52 号 §4.4，纯函数）：证据是「有人在这个位置」，归属看谁可能被到访——
     * <ul>
     *   <li>无同址 → NONE，只算本店；</li>
     *   <li>本店在营：同址另有在营店 → SHARED（分不出，共享）；否则 → ABSORBED（同址不在营店的证据并入本店）；</li>
     *   <li>本店不在营：同址有在营店 → YIELDED（证据归它们，本店 0）；全组都不在营 → SHARED（无从归属，如实共享）。</li>
     * </ul>
     * 2026-10-06（V38）：额外产出 {@code selfInOperation} 与 {@code inOperationCount} 两个
     * **只有排序口径消费**的字段（展示口径不受影响，见 {@link Attribution} 参数注释）。
     */
    private static Attribution attribute(Long venueId, VenueStatus selfStatus, List<Peer> peers) {
        Set<Long> area = new LinkedHashSet<>();
        area.add(venueId);
        peers.forEach(p -> area.add(p.id()));
        boolean selfInOperation = isInOperation(selfStatus);
        int inOperationCount = (selfInOperation ? 1 : 0)
                + (int) peers.stream().filter(p -> isInOperation(p.status())).count();
        if (peers.isEmpty()) {
            return new Attribution(CoLocatedAttribution.NONE, peers, area, area,
                    selfInOperation, inOperationCount);
        }
        boolean anyPeerInOperation = peers.stream().anyMatch(p -> isInOperation(p.status()));
        if (selfInOperation) {
            CoLocatedAttribution kind = anyPeerInOperation ? CoLocatedAttribution.SHARED : CoLocatedAttribution.ABSORBED;
            return new Attribution(kind, peers, area, area, selfInOperation, inOperationCount);
        }
        if (anyPeerInOperation) {
            return new Attribution(CoLocatedAttribution.YIELDED, peers, Set.of(), area,
                    false, inOperationCount);
        }
        return new Attribution(CoLocatedAttribution.SHARED, peers, area, area,
                false, inOperationCount);
    }

    /**
     * 批量归因：一次同址查询（几何 + 双方状态）。无同址邻居的店不在查询结果里，按 NONE 补齐——
     * 返回 map 覆盖全部入参（保序）。
     */
    private Map<Long, Attribution> attributionsFor(Collection<Long> venueIds) {
        Map<Long, VenueStatus> selfStatus = new HashMap<>();
        Map<Long, List<Peer>> peers = new HashMap<>();
        for (VenueRepository.CoLocatedVenueRow row : venueRepository.findCoLocatedPairs(venueIds, CO_LOCATED_RADIUS_M)) {
            selfStatus.put(row.getVenueId(), parseStatus(row.getVenueStatus()));
            peers.computeIfAbsent(row.getVenueId(), k -> new ArrayList<>())
                    .add(new Peer(row.getCoLocatedId(), row.getCoLocatedName(), parseStatus(row.getCoLocatedStatus())));
        }
        Map<Long, Attribution> result = new LinkedHashMap<>();
        for (Long id : venueIds) {
            result.put(id, attribute(id, selfStatus.get(id), peers.getOrDefault(id, List.of())));
        }
        return result;
    }

    /** 归因证据 → 摘要：组内用户并集（同一用户在两家都有 ping 只计 1，COUNT 相加会重复计） */
    private static VenueVisitSummary summarize(Attribution attribution,
                                               Map<Long, Map<Long, LocalDateTime>> lastSeen,
                                               LocalDateTime now) {
        Map<Long, LocalDateTime> users = unionLastSeen(attribution.evidenceVenueIds(), lastSeen);
        LocalDateTime last = users.values().stream().max(LocalDateTime::compareTo).orElse(null);
        return new VenueVisitSummary(countSince(users, now.minusDays(7)), countSince(users, now.minusDays(30)),
                last, attribution.kind(), attribution.peers().size());
    }

    // ── 证据读取（命中谓词唯一实现在 Repository） ─────────────────────────────────

    private Map<Long, Map<Long, LocalDateTime>> lastSeenByVenue(Collection<Long> venueIds, int radiusM) {
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        return toLastSeenByVenue(pingRepository.findVisitorLastSeenByVenueIds(venueIds, radiusM, HIT_MAX_ACCURACY_M));
    }

    /** Object[]{venueId, userId, lastSeenAt} → venueId → (userId → 最近命中时刻) */
    private static Map<Long, Map<Long, LocalDateTime>> toLastSeenByVenue(List<Object[]> rows) {
        Map<Long, Map<Long, LocalDateTime>> result = new HashMap<>();
        for (Object[] row : rows) {
            result.computeIfAbsent((Long) row[0], k -> new HashMap<>()).put((Long) row[1], (LocalDateTime) row[2]);
        }
        return result;
    }

    /** 多店证据的用户并集：userId → 该用户在这些店上的最近命中时刻 */
    private static Map<Long, LocalDateTime> unionLastSeen(Collection<Long> venueIds,
                                                          Map<Long, Map<Long, LocalDateTime>> lastSeen) {
        Map<Long, LocalDateTime> users = new HashMap<>();
        for (Long venueId : venueIds) {
            lastSeen.getOrDefault(venueId, Map.of())
                    .forEach((user, at) -> users.merge(user, at, (a, b) -> a.isAfter(b) ? a : b));
        }
        return users;
    }

    /**
     * Object[]{venueId, userId, visitDay} → venueId → (userId → 该用户的去重到店日集合)（V39）。
     * <p>
     * **保留 user维度**（⛔ 别在这里就把人压掉）：同址归因的并集在 {@code unionVisitDays}
     * 里按 {@code (user, day)} 二元组合并——若本方法返回 {@code Set<LocalDate>}，
     * "谁来的"这一维已被丢弃，6 位不同用户散在 5 天里会被并成 5 个日期
     * （现网实证：120/121 同址组 6 人 ⇒ 误算成 5 次/2.5 次 ⇒ 卡片显示"1 次"）。
     */
    private static Map<Long, Map<Long, Set<LocalDate>>> toVisitDaysByVenue(List<Object[]> rows) {
        Map<Long, Map<Long, Set<LocalDate>>> result = new HashMap<>();
        for (Object[] row : rows) {
            result.computeIfAbsent((Long) row[0], k -> new HashMap<>())
                    .computeIfAbsent((Long) row[1], k -> new HashSet<>())
                    .add((LocalDate) row[2]);
        }
        return result;
    }

    /**
     * 多店证据的<b>去重到店次数</b>（V39）= {@code (userId, visitDay)} 二元组并集的<b>大小</b>。
     *
     * <p><b>为什么并集键必须是 (人, 日) 二元组、不能只是日</b>（2026-10-06 实测修正）：
     * 同址组共享证据时（坐标完全重合的门店，常见于商场/大楼锚点），一家店的到访证据
     * 可能全部来自邻居店。真实样本（一壶淡泊 120 / 丽莎 121 坐标完全重合）：
     * <ul>
     *   <li>120 店 30 天内有 <b>6 位用户</b>，散在 <b>5 个日期</b>上；</li>
     *   <li>若按 {@code Set<LocalDate>} 去重 ⇒ 只剩 <b>5</b>（6 位不同用户被并成 5 个日期，
     *       "谁来的"这一维凭空消失）；再经同址 1/k 分摊 ⇒ 更小；</li>
     *   <li>⇒ 卡片显示「1 次真实到店足迹」，而用户明明看到<b>两位以上</b>被记录 ⇒ 读起来是
     *       "系统只认了一个人"，是对贡献者的直接否定。</li>
     * </ul>
     * 正确语义：<b>每个 (谁, 哪天) 算一次</b>。于是 6 位用户各来 1 天 = <b>6 次</b>；
     * 同一人连来 3 天 = 3 次；同一人同一天在同址两家都命中 = <b>1 次</b>（按日去重，正是
     * 用户定的口径）。
     *
     * <p>⚠️ 由此次数<b>恒 ≥ 人数</b>（每个用户至少贡献 1 次）⇒ 这不是巧合而是口径的必然，
     * 但也意味着<b>次数天然随用户数放大</b>：现网样本极稀（仅 1 家店过 ≥3 门槛）时，
     * 「6 位舞友 · 6 次」读起来仍偏弱是数据量问题，不是公式问题。
     */
    private static long countVisitEvents(Collection<Long> venueIds,
                                         Map<Long, Map<Long, Set<LocalDate>>> visitDays) {
        Set<String> userDays = new HashSet<>();
        for (Long venueId : venueIds) {
            visitDays.getOrDefault(venueId, Map.of())
                    .forEach((user, days) -> days.forEach(day -> userDays.add(user + "|" + day)));
        }
        return userDays.size();
    }

    /** 时间窗去重：用户在窗口内到访过 ⟺ 其最近命中时刻 ≥ 窗口起点 */
    private static long countSince(Map<Long, LocalDateTime> users, LocalDateTime since) {
        return users.values().stream().filter(at -> !at.isBefore(since)).count();
    }

    private static VenueStatus parseStatus(String raw) {
        return WireEnums.parse(VenueStatus.class, raw);
    }

    private static String displayOf(VenueStatus status) {
        return status == null ? "未知" : status.getDisplayName();
    }

    private boolean exceedsWriteRate(Long userId) {
        Deque<Long> window = writeRateCache.get(userId, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        while (!window.isEmpty() && now - window.peekFirst() > WRITE_RATE_WINDOW_MS) {
            window.pollFirst();
        }
        if (window.size() >= WRITE_RATE_LIMIT) {
            log.warn("presence write rate limited: userId={}", userId);
            return true;
        }
        window.addLast(now);
        return false;
    }
}
