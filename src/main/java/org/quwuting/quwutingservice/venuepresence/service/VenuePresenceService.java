package org.quwuting.quwutingservice.venuepresence.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueType;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.dto.request.ReportPresenceRequest;
import org.quwuting.quwutingservice.venuepresence.dto.response.PresenceReportResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceConsentStats;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceStats;
import org.quwuting.quwutingservice.venuepresence.entity.VenuePresenceConsent;
import org.quwuting.quwutingservice.venuepresence.enums.ConsentSource;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 门店到访痕迹服务（2026-09-29，V33；文档 = docs/agents/52-venue-presence.md）。
 * <p>
 * <b>定位</b>：一行 ping = 一次「可证实的到店事实」，只服务 admin 展示与后续打标，
 * <b>不进热度公式</b>（到店数天然随曝光增长，线性进公式即马太闭环——热度四问判据
 * 第 3 问不过；待数据量起来后按 05 号文档流程另行评估）。
 * <p>
 * <b>写宽松读严格</b>：写入侧只做协议限幅（防脏数据），「到访 / 附近」口径
 * （{@link #HIT_RADIUS_M} / {@link #HIT_MAX_ACCURACY_M} / {@link #NEARBY_RADIUS_M}）
 * 全部在查询侧判定——门店坐标是人工选点（10~30m 误差），阈值定错时历史数据可回溯。
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

    /**
     * 采集总开关（常量定义在 {@link OpsConfigService#KEY_PRESENCE_COLLECT_ENABLED}，
     * V33 迁移插入默认行 true）：关闭后上报端点返回 {@code accepted=false(DISABLED)}
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
     * 口径过滤（20m/300m）在查询侧，本限幅只是写侧卫生。
     */
    public static final int WRITE_MAX_DISTANCE_M = 500;

    /** 写入精度限幅（米，协议常量）：wx 端 accuracy 常见 5~65m，>500m 的定位无判定价值 */
    public static final int WRITE_MAX_ACCURACY_M = 500;

    /**
     * 到访命中半径（米，口径参数）：取值依据 = 2026-09-29 真库实测 20m 内有邻居的
     * 门店仅 5.4%（归因唯一性成立）+ 需求方「20m 内才算准」的直觉。门店坐标本身
     * 带人工选点误差（10~30m），本阈值**必然漏检部分真到访**——admin 展示必须
     * 携带口径说明（严重低估真实到店量）。调整需发版并同步 52 号文档。
     */
    public static final int HIT_RADIUS_M = 20;

    /**
     * 到访命中的精度门槛（米，口径参数）：accuracy 超过本值的定位（城市级误差）
     * 即便距离凑巧 ≤ 20m 也不采信。NULL 精度视为达标（端侧未提供 ≠ 超标，
     * 判据见 Repository 口径注释）。
     */
    public static final int HIT_MAX_ACCURACY_M = 30;

    /**
     * 「附近」覆盖半径（米，口径参数）：对齐 GET /venues/nearby 的缺省 300m——
     * 同一「附近」语义在采集与统计两侧共用一个值，避免第二份真值。
     */
    public static final int NEARBY_RADIUS_M = 300;

    /** 每用户写频控（次/窗口，协议常量）：桶幂等已限 (user, venue) 粒度，此处限用户总写入速率 */
    private static final int WRITE_RATE_LIMIT = 10;
    private static final long WRITE_RATE_WINDOW_MS = 60_000L;

    private final VenuePresencePingRepository pingRepository;
    private final VenuePresenceConsentRepository consentRepository;
    private final VenueRepository venueRepository;
    private final OpsConfigService opsConfigService;

    /**
     * 每用户写入频控（滑动窗口）：键 = userId，值 = 窗口内写入时刻队列。
     * 30 分钟桶幂等挡不住「跨店狂刷」，此处兜底用户级速率。合法流量（每次打开
     * 至多 1 条）距上限差两个数量级。
     */
    private final Cache<Long, Deque<Long>> writeRateCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    /**
     * 上报一次到访痕迹（POST /venues/{venueId}/presence 的实现）。
     * 调用方必须已 {@code UserContext.requireAuth()}（重放安全不变量：鉴权在副作用之前）。
     */
    @Transactional
    public PresenceReportResponse report(Long venueId, Long userId, ReportPresenceRequest request) {
        if (!opsConfigService.isEnabled(OpsConfigService.KEY_PRESENCE_COLLECT_ENABLED, true)) {
            return new PresenceReportResponse(false, PresenceReportResponse.REASON_DISABLED);
        }
        if (exceedsWriteRate(userId)) {
            throw new BusinessException(1022, "上报过于频繁，请稍后再试");
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
        // 默认态确立（V34）：授权模型 09-29 四轮改版为「默认开启」——首次采集触达
        // 即补记出厂态，使「默认开启人群」进入 admin 开关统计（无用户动作、无弹窗）
        consentRepository.insertDefaultIfAbsent(userId, LocalDateTime.now());
        return new PresenceReportResponse(true, null);
    }

    /**
     * 记录一次用户手动开关变更（POST /venues/presence-consent 的实现）。
     * 「我的-设置」拨动开关时 fire-and-forget 上报；每次变更插一行 USER 流水
     * （不 upsert——保留变更历史才能回答「近期变更热度」；当前态由查询侧
     * 「每用户最新一条」口径派生）。enabled 为 null（缺字段）按 1022 拒绝，
     * 禁猜默认值。
     */
    @Transactional
    public void recordConsent(Long userId, Boolean enabled) {
        if (enabled == null) {
            throw new BusinessException(1022, "缺少开关状态");
        }
        VenuePresenceConsent consent = new VenuePresenceConsent();
        consent.setUserId(userId);
        consent.setEnabled(enabled);
        consent.setSource(ConsentSource.USER);
        // created_at/updated_at 由 BaseEntity 的 @CreationTimestamp/@UpdateTimestamp 托管
        consentRepository.save(consent);
    }

    /**
     * 开关统计（admin 门店列表页头）。当前态 = 每用户最新一条 consent 行；
     * 「默认开启」= 最新态仍是 DEFAULT 来源（从未手动改过设置）。数据量级 =
     * 用户数 × 变更次数（千级行），native 窗口函数一条 SQL 出分布，见
     * {@code VenuePresenceConsentRepository#countLatestByEnabledAndSource}。
     */
    public VenuePresenceConsentStats consentStats() {
        long enabledUsers = 0;
        long disabledUsers = 0;
        long defaultUsers = 0;
        for (Object[] row : consentRepository.countLatestByEnabledAndSource()) {
            boolean enabled = Boolean.TRUE.equals(row[0]);
            boolean isDefault = ConsentSource.DEFAULT.name().equals(String.valueOf(row[1]));
            long users = ((Number) row[2]).longValue();
            if (enabled) {
                enabledUsers += users;
                if (isDefault) {
                    defaultUsers += users;
                }
            } else {
                disabledUsers += users;
            }
        }
        long changes30d = consentRepository.countUserChangesSince(LocalDateTime.now().minusDays(30));
        return new VenuePresenceConsentStats(enabledUsers, disabledUsers, defaultUsers, changes30d);
    }

    /**
     * 单店到访统计（admin 详情卡）。
     * 三个数值 = 同一命中谓词、三种窗口/半径组合；口径参数随响应回显，
     * admin 端展示时必须与数值同屏（统计量不带口径 = 邀请误读）。
     */
    public VenuePresenceStats statsFor(Long venueId) {
        LocalDateTime now = LocalDateTime.now();
        long uv7d = distinctUsers(venueId, now.minusDays(7), HIT_RADIUS_M);
        long uv30d = distinctUsers(venueId, now.minusDays(30), HIT_RADIUS_M);
        long nearby30d = distinctUsers(venueId, now.minusDays(30), NEARBY_RADIUS_M);
        LocalDateTime lastPresenceAt = pingRepository
                .findFirstByVenueIdAndDeletedFalseOrderByCreatedAtDesc(venueId)
                .map(p -> p.getCreatedAt())
                .orElse(null);
        return new VenuePresenceStats(uv7d, uv30d, nearby30d, lastPresenceAt, HIT_RADIUS_M, NEARBY_RADIUS_M);
    }

    /**
     * 批量每店近 30 天到访人数（admin 列表列）：一次 IN 覆盖整页防 N+1，
     * 无数据的店不在返回 map 中（调用方按 0 兜底）。
     */
    public Map<Long, Long> visitUsers30dByVenueIds(Collection<Long> venueIds) {
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : pingRepository.countDistinctUsersByVenueIdsSince(
                venueIds, LocalDateTime.now().minusDays(30), HIT_RADIUS_M, HIT_MAX_ACCURACY_M)) {
            result.put((Long) row[0], (Long) row[1]);
        }
        return result;
    }

    private long distinctUsers(Long venueId, LocalDateTime since, int radiusM) {
        List<Object[]> rows = pingRepository.countDistinctUsersByVenueIdsSince(
                List.of(venueId), since, radiusM, HIT_MAX_ACCURACY_M);
        return rows.isEmpty() ? 0L : (Long) rows.get(0)[1];
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
