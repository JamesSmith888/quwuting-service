package org.quwuting.quwutingservice.timershare.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.ratelimit.SlidingWindowLimiter;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.spend.enums.WireEnums;
import org.quwuting.quwutingservice.timershare.dto.request.SetTimerShareLikeRequest;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeSetResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikesResponse;
import org.quwuting.quwutingservice.timershare.entity.TimerShare;
import org.quwuting.quwutingservice.timershare.entity.TimerShareJoin;
import org.quwuting.quwutingservice.timershare.entity.TimerShareLike;
import org.quwuting.quwutingservice.timershare.enums.TimerShareLikeOutcome;
import org.quwuting.quwutingservice.timershare.enums.TimerShareRole;
import org.quwuting.quwutingservice.timershare.repository.TimerShareJoinRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareLikeRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareRepository;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeRules.JoinFact;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeRules.Pair;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeRules.ShareFact;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 计时「给同行者点赞」（2026-10-12，V48；文档 = docs/agents/54-timer-share.md §十五）。
 * <p>
 * 是什么：同一场二维码同步的计时里，主持方与加入者可以对彼此点一个赞——结算时顺手致意，不是评价、
 * 不是礼物。🚫 <b>无任何经济价值且永不进算法</b>：不给积分、不进榜、不进任何主页、不下发对方 userId，
 * 也没有「赞数」这个跨场聚合量（2026-10-13 起一位用户的点赞记录可被同场对家查看，但那是按人的一份时间线，
 * 见 {@code TimerShareLikeHistoryService}，不是聚合）。
 *
 * <h3>为什么不放进 {@link TimerShareService}</h3>
 * 那个类是<b>刻意没有 {@code @Transactional} 的编排层</b>——它要在事务外做微信码图外呼与唯一键冲突重试。
 * 点赞没有任何远程 I/O，也不需要「事务外捕获唯一键」（toggle 用 {@code ON DUPLICATE KEY UPDATE} 一条语句幂等），
 * 所以这里直接事务化，与 {@code CrowdReportLikeService} 同构，不去污染那个类的事务边界约定。
 *
 * <h3>错误模型</h3>
 * 同 {@code TimerShareJoinOutcome}：所有预期的业务状态（开关关 / 非成员 / 目标非法 / 窗口已过 / 太快）以
 * {@link TimerShareLikeOutcome} 数据回传（HTTP 200），不抛异常；只有未登录走 401（控制器首行 requireAuth）。
 *
 * <h3>被赞通知</h3>
 * 仅该对<b>首次赞</b>（INSERT 受影响行数 == 1）触发；取消后再赞不重发。同一场里的未读被赞通知合并成一条
 * 「收到 N 个赞」（{@link MessageService#createOrMergeUnread}）；<b>不点名赞者</b>。
 */
@Slf4j
@Service
public class TimerShareLikeService {

    /** 站内信 relatedType：仅作合并键（同一收件人 × 同一会话），<b>不深链</b>（见 MessageType.TIMER_SHARE_LIKED） */
    static final String RELATED_TYPE = "TIMER_SHARE";

    static final String LIKED_TITLE = "收到点赞";

    private final TimerShareRepository shareRepository;
    private final TimerShareJoinRepository joinRepository;
    private final TimerShareLikeRepository likeRepository;
    private final MessageService messageService;
    private final OpsConfigService opsConfigService;
    private final LongSupplier clock;

    private final SlidingWindowLimiter writeLimiter = new SlidingWindowLimiter(
            TimerSharePolicy.LIKE_WRITE_RATE_LIMIT, TimerSharePolicy.LIKE_WRITE_RATE_WINDOW_MS,
            TimerSharePolicy.RATE_LIMITER_MAX_KEYS);
    private final SlidingWindowLimiter readLimiter = new SlidingWindowLimiter(
            TimerSharePolicy.LIKE_READ_RATE_LIMIT, TimerSharePolicy.LIKE_READ_RATE_WINDOW_MS,
            TimerSharePolicy.RATE_LIMITER_MAX_KEYS);

    @Autowired
    public TimerShareLikeService(TimerShareRepository shareRepository, TimerShareJoinRepository joinRepository,
                                 TimerShareLikeRepository likeRepository, MessageService messageService,
                                 OpsConfigService opsConfigService) {
        this(shareRepository, joinRepository, likeRepository, messageService, opsConfigService,
                System::currentTimeMillis);
    }

    /** 可注入时钟的构造器（单测用：窗口与限流都是时间的函数，不该依赖真实睡眠） */
    TimerShareLikeService(TimerShareRepository shareRepository, TimerShareJoinRepository joinRepository,
                          TimerShareLikeRepository likeRepository, MessageService messageService,
                          OpsConfigService opsConfigService, LongSupplier clock) {
        this.shareRepository = shareRepository;
        this.joinRepository = joinRepository;
        this.likeRepository = likeRepository;
        this.messageService = messageService;
        this.opsConfigService = opsConfigService;
        this.clock = clock;
    }

    // ── 读 ───────────────────────────────────────────────────────────────────

    /**
     * 调用者在这张会话里面前的每一位：我赞了没有 / 他赞了我没有 / 此刻还赞得了吗。
     * 非成员 / token 非法 / 开关关闭 → 空列表（不报错）。速率超限 → 同样返回空列表而不是抛错：
     * 这是详情卡的装饰性读取，限流时「不显示入口」比弹一个错误提示更合适。
     */
    @Transactional(readOnly = true)
    public TimerShareLikesResponse likes(Long userId, String token) {
        long nowMs = clock.getAsLong();
        boolean enabled = isEnabled();
        if (!enabled) {
            return new TimerShareLikesResponse(false, List.of(), nowMs);
        }
        if (!readLimiter.tryAcquire(limiterKey(userId), nowMs)) {
            log.warn("[timer-share] like read rate limited: uid={}", userId);
            return new TimerShareLikesResponse(true, List.of(), nowMs);
        }
        Context ctx = loadContext(userId, token);
        if (ctx == null) {
            return new TimerShareLikesResponse(true, List.of(), nowMs);
        }
        Set<Long> iLiked = new HashSet<>();
        for (TimerShareLike like : likeRepository.findByShareIdAndLikerIdAndDeletedFalse(ctx.share.getId(), userId)) {
            iLiked.add(like.getLikedUserId());
        }
        Set<Long> likedMe = new HashSet<>();
        for (TimerShareLike like : likeRepository.findByShareIdAndLikedUserIdAndDeletedFalse(ctx.share.getId(), userId)) {
            likedMe.add(like.getLikerId());
        }
        List<TimerShareLikesResponse.Item> items = new ArrayList<>(ctx.pairs.size());
        for (Pair pair : ctx.pairs) {
            items.add(new TimerShareLikesResponse.Item(pair.role().name(), pair.seq(),
                    iLiked.contains(pair.otherUserId()), likedMe.contains(pair.otherUserId()),
                    TimerShareLikeRules.withinWindow(pair, nowMs)));
        }
        return new TimerShareLikesResponse(true, items, nowMs);
    }

    /**
     * 点赞记录用（2026-10-13）：把请求里的「会话码 + 角色 + 序号」解析成<b>对方的用户 id</b>（仅服务端内部，不下发）。
     * 与读 / 写共用同一个 {@link #loadContext} 与 {@link TimerShareLikeRules#find}——「谁是这位对方」只有一份推导。
     * 不判窗口（记录是历史，窗口只管能不能赞）；非成员 / 目标不存在 / 协议值非法 → null（调用方当「解析不出」）。
     */
    @Transactional(readOnly = true)
    public Long resolvePeerUserId(Long userId, String token, String relation, Integer seq) {
        TimerShareRole role = WireEnums.parse(TimerShareRole.class, relation);
        if (role == null || (role == TimerShareRole.JOINER && !isPositive(seq))) {
            return null;
        }
        Context ctx = loadContext(userId, token);
        if (ctx == null) {
            return null;
        }
        Pair target = TimerShareLikeRules.find(ctx.pairs, role, role == TimerShareRole.HOST ? 0 : seq);
        return target == null ? null : target.otherUserId();
    }

    // ── 写 ───────────────────────────────────────────────────────────────────

    /**
     * 赞 / 取消赞（幂等）。判定链即优先级：开关 → 速率 → 请求形状 → 成员 → 目标 → 窗口 → 写入。
     * 赞与取消<b>共用同一条链</b>（含窗口）：窗口外冻结，不存在「赞得了但取消不了」的悬空态。
     */
    @Transactional
    public TimerShareLikeSetResponse set(Long userId, String token, SetTimerShareLikeRequest request) {
        long nowMs = clock.getAsLong();
        if (!isEnabled()) {
            return rejected(TimerShareLikeOutcome.DISABLED, nowMs);
        }
        if (!writeLimiter.tryAcquire(limiterKey(userId), nowMs)) {
            log.warn("[timer-share] like write rate limited: uid={}", userId);
            return rejected(TimerShareLikeOutcome.TOO_FREQUENT, nowMs);
        }
        TimerShareRole role = request == null ? null : WireEnums.parse(TimerShareRole.class, request.relation());
        if (role == null || request.liked() == null || (role == TimerShareRole.JOINER && !isPositive(request.seq()))) {
            return rejected(TimerShareLikeOutcome.INVALID_TARGET, nowMs);
        }
        Context ctx = loadContext(userId, token);
        if (ctx == null) {
            return rejected(TimerShareLikeOutcome.NOT_MEMBER, nowMs);
        }
        Pair target = TimerShareLikeRules.find(ctx.pairs, role, role == TimerShareRole.HOST ? 0 : request.seq());
        if (target == null) {
            return rejected(TimerShareLikeOutcome.INVALID_TARGET, nowMs);
        }
        if (!TimerShareLikeRules.withinWindow(target, nowMs)) {
            return rejected(TimerShareLikeOutcome.WINDOW_CLOSED, nowMs);
        }

        Long shareId = ctx.share.getId();
        LocalDateTime now = LocalDateTime.now();
        if (request.liked()) {
            int affected = likeRepository.like(shareId, userId, target.otherUserId(), TimerSharePolicy.LIKE_CODE, now);
            // 受影响行数 1 = 该对首次赞（唯一触发通知的机会）；2 = 取消后恢复或重复赞，义务已履行，不重发
            if (affected == 1) {
                notifyLiked(ctx.share, target.otherUserId());
            }
            log.info("[timer-share] like set: shareId={} liker={} role={} seq={} first={}", shareId, userId,
                    target.role(), target.seq(), affected == 1);
        } else {
            likeRepository.unlike(shareId, userId, target.otherUserId(), now);
            log.info("[timer-share] like cleared: shareId={} liker={} role={} seq={}", shareId, userId,
                    target.role(), target.seq());
        }
        return new TimerShareLikeSetResponse(TimerShareLikeOutcome.OK.name(), request.liked(), clock.getAsLong());
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /** 会话 + 调用者面前的可赞对象；token 非法 / 不存在 / 非成员 → null（统一当「不存在」，不泄露 token 是否存在） */
    private Context loadContext(Long userId, String token) {
        if (!TimerShareTokens.isWellFormed(token)) {
            return null;
        }
        TimerShare share = shareRepository.findByTokenAndDeletedFalse(token).orElse(null);
        if (share == null) {
            return null;
        }
        List<TimerShareJoin> joins = joinRepository.findByShareIdAndDeletedFalseOrderByIdAsc(share.getId());
        List<JoinFact> facts = new ArrayList<>(joins.size());
        for (TimerShareJoin join : joins) {
            Long joinedAtMs = TimerShareClock.epochMsOf(join.getCreatedAt());
            // 创建时间缺失（理论上不会）→ 退回会话快照时刻，让窗口仍可算；不因此拒绝
            facts.add(new JoinFact(join.getUserId(), joinedAtMs == null ? share.getSnapshotAtMs() : joinedAtMs,
                    join.getSettledAtMs()));
        }
        List<Pair> pairs = TimerShareLikeRules.pairsOf(userId,
                new ShareFact(share.getHostUserId(), share.getSnapshotAtMs(), share.getHostSettledAtMs()), facts);
        return pairs == null ? null : new Context(share, pairs);
    }

    private record Context(TimerShare share, List<Pair> pairs) {
    }

    /**
     * 被赞通知：合并该收件人在这张会话里的未读被赞通知（窗口起点 = 会话创建时刻——更早的未读属于别的会话，
     * 不会进来；relatedId 就是会话 id，合并键 = 收件人 × 会话）。正文带累计数，不点名赞者。
     */
    private void notifyLiked(TimerShare share, Long recipientId) {
        long count = likeRepository.countByShareIdAndLikedUserIdAndDeletedFalse(share.getId(), recipientId);
        messageService.createOrMergeUnread(recipientId, MessageType.TIMER_SHARE_LIKED, LIKED_TITLE,
                likedContent(count), RELATED_TYPE, share.getId(), share.getCreatedAt());
    }

    /** 被赞通知正文：只说事实，不用评价 / 好评 / 礼物类字眼（合规文案，54 号 §六） */
    static String likedContent(long count) {
        return count <= 1
                ? "一同计时的舞伴给你点了个赞"
                : "一同计时的舞伴共给你点了 " + count + " 个赞";
    }

    private boolean isEnabled() {
        return opsConfigService.isEnabled(OpsConfigService.KEY_TIMER_SHARE_LIKE_ENABLED, true);
    }

    private static boolean isPositive(Integer seq) {
        return seq != null && seq > 0;
    }

    private static TimerShareLikeSetResponse rejected(TimerShareLikeOutcome outcome, long nowMs) {
        return new TimerShareLikeSetResponse(outcome.name(), false, nowMs);
    }

    private static String limiterKey(Long userId) {
        return "u" + userId;
    }
}
