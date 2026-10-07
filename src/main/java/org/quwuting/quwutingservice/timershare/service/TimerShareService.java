package org.quwuting.quwutingservice.timershare.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.db.DbConstraintViolations;
import org.quwuting.quwutingservice.common.ratelimit.SlidingWindowLimiter;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.timershare.dto.request.CreateTimerShareRequest;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareCloseResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareJoinResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareRuleView;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareStatusResponse;
import org.quwuting.quwutingservice.timershare.entity.TimerShare;
import org.quwuting.quwutingservice.timershare.enums.TimerShareJoinOutcome;
import org.quwuting.quwutingservice.timershare.enums.TimerShareStatus;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.wxacode.service.WxacodeImage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * 计时「二维码同步给对方」编排层（2026-10-07，V42；文档 = docs/agents/54-timer-share.md）。
 * <p>
 * <b>本类没有 {@code @Transactional}，这是有意的</b>：它负责速率护栏、参数校验、
 * 微信码图外呼这些<b>事务外</b>的事（事务内禁远程 I/O），以及对唯一键冲突的重试——事务性读写全部
 * 下沉在 {@link TimerShareStore}。两者的分工理由见 Store 类注释。
 *
 * <h3>错误模型</h3>
 * 「预期的业务状态」（码过期 / 已满 / 已结束 / 自己扫自己）以 {@link TimerShareJoinOutcome}
 * 数据回给客户端，因为客户端请求层丢弃业务错误码、只留 message，无法据此分流；
 * 「调用方的错」走 {@link BusinessException}：1041 参数非法、
 * 1043 会话不存在（主持方轮询自己不存在的会话）、1006 速率/额度超限。
 *
 * <h3>鉴权</h3>
 * 控制器方法首行 {@code UserContext.requireAuth()}（重放安全不变量：鉴权先于任何副作用）；
 * 本类只接收已鉴权的 userId，不自己读 UserContext，便于单测。
 */
@Slf4j
@Service
public class TimerShareService {

    static final int CODE_INVALID = 1041;
    static final int CODE_NOT_FOUND = 1043;
    static final int CODE_TOO_FREQUENT = 1006;

    private static final Pattern SESSION_KEY =
            Pattern.compile("^[0-9A-Za-z:_-]{1," + TimerSharePolicy.SESSION_KEY_MAX_LENGTH + "}$");

    private final TimerShareStore store;
    private final TimerShareQrService qrService;
    private final VenueRepository venueRepository;
    private final LongSupplier clock;

    private final SlidingWindowLimiter writeLimiter = new SlidingWindowLimiter(
            TimerSharePolicy.WRITE_RATE_LIMIT, TimerSharePolicy.WRITE_RATE_WINDOW_MS,
            TimerSharePolicy.RATE_LIMITER_MAX_KEYS);
    private final SlidingWindowLimiter statusLimiter = new SlidingWindowLimiter(
            TimerSharePolicy.STATUS_RATE_LIMIT, TimerSharePolicy.STATUS_RATE_WINDOW_MS,
            TimerSharePolicy.RATE_LIMITER_MAX_KEYS);
    private final SlidingWindowLimiter joinLimiter = new SlidingWindowLimiter(
            TimerSharePolicy.JOIN_RATE_LIMIT, TimerSharePolicy.JOIN_RATE_WINDOW_MS,
            TimerSharePolicy.RATE_LIMITER_MAX_KEYS);

    @Autowired
    public TimerShareService(TimerShareStore store, TimerShareQrService qrService,
                             VenueRepository venueRepository) {
        this(store, qrService, venueRepository, System::currentTimeMillis);
    }

    /** 可注入时钟的构造器（单测用：限流 / 过期判定都是时间的函数，不该依赖真实睡眠） */
    TimerShareService(TimerShareStore store, TimerShareQrService qrService,
                      VenueRepository venueRepository, LongSupplier clock) {
        this.store = store;
        this.qrService = qrService;
        this.venueRepository = venueRepository;
        this.clock = clock;
    }

    // ── 主持方 ────────────────────────────────────────────────────────────────

    /**
     * 创建或刷新一场计时的分享会话。同一 (用户, sessionKey) 幂等：重复调用 = 刷新快照与有效期，
     * token / 码图不变。
     */
    public TimerShareResponse createOrRefresh(Long hostUserId, CreateTimerShareRequest request) {
        long nowMs = clock.getAsLong();
        if (!writeLimiter.tryAcquire(limiterKey(hostUserId), nowMs)) {
            log.warn("[timer-share] write rate limited: host={}", hostUserId);
            throw new BusinessException(CODE_TOO_FREQUENT, "操作过于频繁，请稍后再试");
        }
        if (request == null) {
            throw invalid("请求体为空");
        }
        String sessionKey = requireSessionKey(request.sessionKey());
        if (request.wallElapsedMs() == null || request.netElapsedSeconds() == null || request.running() == null) {
            throw invalid("读数字段缺失");
        }

        TimerShareClock.Anchors anchors;
        String ruleJson;
        try {
            anchors = TimerShareClock.anchor(nowMs, request.wallElapsedMs(), request.netElapsedSeconds(),
                    request.running());
            ruleJson = TimerShareRules.serialize(TimerShareRules.normalize(request.rule()));
        } catch (IllegalArgumentException e) {
            throw invalid("host=" + hostUserId + " " + e.getMessage());
        }

        Long venueId = resolveVenueId(request.venueId());
        Long parentShareId = resolveParentShareId(request.parentToken());

        TimerShareStore.Upserted upserted = upsertWithRetry(hostUserId, sessionKey, anchors, ruleJson,
                venueId, parentShareId, nowMs);
        TimerShare share = upserted.share();
        log.info("[timer-share] {} shareId={} host={} paused={} joinCount={} refreshCount={}",
                upserted.created() ? "created" : "refreshed", share.getId(), hostUserId,
                anchors.paused(), share.getJoinCount(), share.getRefreshCount());
        return new TimerShareResponse(share.getToken(), qrPath(share.getToken()), share.getExpiresAtMs(),
                clock.getAsLong(), share.getJoinCount(), share.getMaxJoins());
    }

    /** 主持方轮询：对方加入了几人。不是自己的 / 不存在 → 1043（主持方永远只会查自己的会话，这是调用方的错） */
    public TimerShareStatusResponse status(Long hostUserId, String token) {
        long nowMs = clock.getAsLong();
        if (!statusLimiter.tryAcquire(limiterKey(hostUserId), nowMs)) {
            throw new BusinessException(CODE_TOO_FREQUENT, "操作过于频繁，请稍后再试");
        }
        TimerShare share = TimerShareTokens.isWellFormed(token)
                ? store.findOwned(token, hostUserId).orElse(null)
                : null;
        if (share == null) {
            throw new BusinessException(CODE_NOT_FOUND, "二维码不存在或已失效");
        }
        String status = share.getStatus() == TimerShareStatus.CLOSED ? "CLOSED"
                : (nowMs > share.getExpiresAtMs() ? "EXPIRED" : "ACTIVE");
        return new TimerShareStatusResponse(status, share.getJoinCount(), share.getMaxJoins(),
                share.getExpiresAtMs(), nowMs);
    }

    /** 主持方结束 / 单独结算 / 丢弃计时时的清理；幂等，永不报错（见 TimerShareCloseResponse） */
    public TimerShareCloseResponse close(Long hostUserId, String token) {
        if (!TimerShareTokens.isWellFormed(token)) {
            return new TimerShareCloseResponse(false);
        }
        boolean closed = store.close(token, hostUserId, clock.getAsLong());
        if (closed) {
            log.info("[timer-share] closed by host: host={}", hostUserId);
        }
        return new TimerShareCloseResponse(closed);
    }

    // ── 接收方 ────────────────────────────────────────────────────────────────

    /**
     * 扫码加入。任何「预期的业务状态」都以 outcome 数据返回（HTTP 200），不抛异常。
     */
    public TimerShareJoinResponse join(Long userId, String token) {
        long nowMs = clock.getAsLong();
        if (!joinLimiter.tryAcquire(limiterKey(userId), nowMs)) {
            log.warn("[timer-share] join rate limited: uid={}", userId);
            return TimerShareJoinResponse.withoutSnapshot(TimerShareJoinOutcome.TOO_FREQUENT.name(), nowMs);
        }
        if (!TimerShareTokens.isWellFormed(token)) {
            return TimerShareJoinResponse.withoutSnapshot(TimerShareJoinOutcome.NOT_FOUND.name(), nowMs);
        }

        TimerShareStore.JoinResult result = store.join(token, userId, nowMs);
        TimerShare share = result.share();
        log.info("[timer-share] join uid={} outcome={} shareId={} newUser={}", userId, result.outcome(),
                share == null ? null : share.getId(), result.newUser());
        if (share == null) {
            return TimerShareJoinResponse.withoutSnapshot(result.outcome().name(), clock.getAsLong());
        }

        TimerShareRuleView rule = TimerShareRules.parseOrNull(share.getRuleJson());
        if (rule == null) {
            return TimerShareJoinResponse.withoutSnapshot(TimerShareJoinOutcome.NOT_FOUND.name(), clock.getAsLong());
        }
        TimerShareJoinResponse.Snapshot snapshot = new TimerShareJoinResponse.Snapshot(
                share.getStartServerMs(), share.getExcludedSeconds(), share.getPausedAtServerMs(), rule,
                resolveVenueView(share.getVenueId()));
        // serverNowMs 在所有 DB 工作之后取：客户端按「往返中点」估算偏移，取得越靠近响应发出越准
        return new TimerShareJoinResponse(result.outcome().name(), clock.getAsLong(), snapshot);
    }

    // ── 码图 ─────────────────────────────────────────────────────────────────

    /**
     * 码图（公开端点，token 即凭据）。仅当：token 格式合法 + 会话存在且 ACTIVE 未过期，
     * 才允许生成——其余一律 empty（控制器回 404），且<b>不触发任何微信外呼</b>。
     */
    public Optional<WxacodeImage> renderQr(String token) {
        if (!TimerShareTokens.isWellFormed(token) || !store.isJoinable(token, clock.getAsLong())) {
            return Optional.empty();
        }
        return Optional.of(qrService.render(token));
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private static String limiterKey(Long userId) {
        return "u" + userId;
    }

    private static String qrPath(String token) {
        return "/timer-shares/" + token + "/wxacode.jpg";
    }

    private static BusinessException invalid(String reason) {
        // reason 只进日志；回给客户端的文案固定，不暴露校验细节
        log.warn("[timer-share] invalid payload: {}", reason);
        return new BusinessException(CODE_INVALID, "计时读数异常，无法同步");
    }

    private static String requireSessionKey(String sessionKey) {
        if (sessionKey == null || !SESSION_KEY.matcher(sessionKey).matches()) {
            throw invalid("sessionKey 非法");
        }
        return sessionKey;
    }

    /** 门店 id → 存在才保留（客户端传来的 id 不可信；不存在就当没有，不因此拒绝整个请求） */
    private Long resolveVenueId(Long venueId) {
        if (venueId == null || venueId <= 0) {
            return null;
        }
        return venueRepository.findByIdAndDeletedFalse(venueId).map(Venue::getId).orElse(null);
    }

    /** 加入响应里的门店：据 id 取当前名称（不信任、也不存客户端传的名称） */
    private TimerShareJoinResponse.VenueView resolveVenueView(Long venueId) {
        if (venueId == null) {
            return null;
        }
        return venueRepository.findByIdAndDeletedFalse(venueId)
                .map(v -> new TimerShareJoinResponse.VenueView(v.getId(), v.getName()))
                .orElse(null);
    }

    /** 传播链：parentToken 格式合法且存在才记；否则忽略（它只是统计口径，不值得为它拒绝请求） */
    private Long resolveParentShareId(String parentToken) {
        if (!TimerShareTokens.isWellFormed(parentToken)) {
            return null;
        }
        return store.findIdByToken(parentToken).orElse(null);
    }

    /** 写入最多尝试几次（含首次）：并发竞态的输家通常第二次就能看到赢家的行；3 次是「极端抖动」的硬顶 */
    private static final int UPSERT_MAX_ATTEMPTS = 3;

    /**
     * 写入并对<b>并发竞态</b>重试（最多 {@value #UPSERT_MAX_ATTEMPTS} 次，每次换新 token）。可重试的只有两类：
     * <ol>
     *   <li>唯一键冲突（{@code DbConstraintViolations.isUniqueViolation}）：同一场并发创建 ⇒ 下一次进入会找到胜出者
     *       的行走刷新分支；token 撞号（62^10，概率可忽略但要有确定的后果）⇒ 下一次换新 token；</li>
     *   <li>锁失败（{@link PessimisticLockingFailureException}：死锁 / 锁等待超时）：{@code createOrRefresh} 已改用
     *       READ_COMMITTED 避免了首次创建的间隙锁死锁，这里是兜底——MySQL 文档明确要求应用对死锁回滚做重试。</li>
     * </ol>
     * 非唯一键的完整性错误（NOT NULL / 列约束）不是并发竞态，原样抛出（DbConstraintViolations 约定）。
     */
    private TimerShareStore.Upserted upsertWithRetry(Long hostUserId, String sessionKey,
                                                     TimerShareClock.Anchors anchors, String ruleJson,
                                                     Long venueId, Long parentShareId, long nowMs) {
        for (int attempt = 1; ; attempt++) {
            try {
                return store.createOrRefresh(hostUserId, sessionKey, anchors, ruleJson, venueId, parentShareId,
                        TimerShareTokens.generate(), nowMs);
            } catch (DataIntegrityViolationException e) {
                if (!DbConstraintViolations.isUniqueViolation(e) || attempt >= UPSERT_MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("[timer-share] unique conflict on upsert, retrying ({}/{}): host={}",
                        attempt, UPSERT_MAX_ATTEMPTS, hostUserId);
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= UPSERT_MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("[timer-share] lock failure on upsert, retrying ({}/{}): host={} ({})",
                        attempt, UPSERT_MAX_ATTEMPTS, hostUserId, e.getClass().getSimpleName());
            }
        }
    }
}
