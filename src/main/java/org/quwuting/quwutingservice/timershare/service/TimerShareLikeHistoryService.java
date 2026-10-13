package org.quwuting.quwutingservice.timershare.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.ratelimit.SlidingWindowLimiter;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeHistoryResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeHistoryResponse.Event;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeHistoryResponse.Group;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeHistoryResponse.Summary;
import org.quwuting.quwutingservice.timershare.entity.TimerShareLike;
import org.quwuting.quwutingservice.timershare.enums.TimerShareLikeSubject;
import org.quwuting.quwutingservice.timershare.repository.TimerShareJoinRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareLikeRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareRepository;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeHistoryRules.EventFact;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeHistoryRules.GroupFact;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeHistoryRules.LikeFact;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 计时「点赞记录」（2026-10-13 第二版；文档 = docs/agents/55-timer-share-likes.md §八）：<b>记录主人</b>在所有一同计时
 * 的会话里收到的赞与送出的赞，按对方分组。主人 = 调用者本人（不带焦点参数），或调用者在某张会话里点开的那位同行者
 * （带 token + relation + seq）——用户要的是「点头像就看到<b>该用户</b>所有的点赞记录」。
 *
 * <h3>为什么是新服务，而不是 {@link TimerShareLikeService} 多一个方法</h3>
 * 那个类的职责是「一张会话里的点赞状态与写入」（开关 → 速率 → 成员 → 窗口 → 写入 → 通知）。记录是另一个读模型：
 * 跨会话、只读、无窗口、无通知、有自己的速率与上限。分开后各自的构造器依赖与测试夹具都不被对方拖大。
 * 「谁是这位对方」只有一份推导（{@link TimerShareLikeService#resolvePeerUserId}）。
 *
 * <h3>取数路径与授权边界</h3>
 * <b>授权</b>：看别人的记录必须先过 {@code resolvePeerUserId}——调用者是这张会话的成员，且那位是他在这张会话里的对家
 * （主持方 ↔ 加入者；加入者之间不互通）。解析不出 ⇒ {@code UNRESOLVED} 空记录，<b>绝不退回成调用者自己的记录</b>。
 * <b>取数</b>：主人作为主持方 / 加入者参与过的会话 id（两条既有索引）→ 这些会话里与主人有关的有效点赞行
 * （唯一索引的 share_id 前缀）→ 内存分组。点赞行只可能产生在主人参与的会话里，所以这条路径<b>取得全</b>，
 * 同时<b>没有</b>以 liked_user_id 打头的索引、没有按被赞者扫表、没有任何全站聚合量（门禁 X14 / X15 钉住）。
 * 响应不含任何用户 id（{@link TimerShareLikeHistoryRules} 的 {@code peerUserId} 只在这里换成昵称 / 头像）。
 *
 * <h3>失败语义</h3>
 * 与 {@code GET likes} 不同，这是用户<b>主动打开</b>的页面，不是装饰性读取：速率超限抛 {@code BusinessException(1006)}
 * 让客户端显示「加载失败，重试」，而不是返回一个空记录让用户误以为「还没有人赞过 TA」。开关关闭是预期状态，以
 * {@code enabled=false} 回传。
 */
@Slf4j
@Service
public class TimerShareLikeHistoryService {

    private final TimerShareRepository shareRepository;
    private final TimerShareJoinRepository joinRepository;
    private final TimerShareLikeRepository likeRepository;
    private final UserRepository userRepository;
    private final TimerShareLikeService likeService;
    private final OpsConfigService opsConfigService;
    private final LongSupplier clock;

    private final SlidingWindowLimiter readLimiter = new SlidingWindowLimiter(
            TimerSharePolicy.LIKE_HISTORY_READ_RATE_LIMIT, TimerSharePolicy.LIKE_HISTORY_READ_RATE_WINDOW_MS,
            TimerSharePolicy.RATE_LIMITER_MAX_KEYS);

    @Autowired
    public TimerShareLikeHistoryService(TimerShareRepository shareRepository, TimerShareJoinRepository joinRepository,
                                        TimerShareLikeRepository likeRepository, UserRepository userRepository,
                                        TimerShareLikeService likeService, OpsConfigService opsConfigService) {
        this(shareRepository, joinRepository, likeRepository, userRepository, likeService, opsConfigService,
                System::currentTimeMillis);
    }

    /** 可注入时钟的构造器（单测用） */
    TimerShareLikeHistoryService(TimerShareRepository shareRepository, TimerShareJoinRepository joinRepository,
                                 TimerShareLikeRepository likeRepository, UserRepository userRepository,
                                 TimerShareLikeService likeService, OpsConfigService opsConfigService,
                                 LongSupplier clock) {
        this.shareRepository = shareRepository;
        this.joinRepository = joinRepository;
        this.likeRepository = likeRepository;
        this.userRepository = userRepository;
        this.likeService = likeService;
        this.opsConfigService = opsConfigService;
        this.clock = clock;
    }

    /**
     * @param token    可选；与 relation / seq 一起指明「用户此刻点开的那一位」。<b>三者全缺 = 要调用者本人的记录</b>；
     *                 只要带了任何一个，就是在要那一位的记录，解析不出就是 {@code UNRESOLVED}（不会退回成本人的）
     * @param relation 可选；对方的角色（HOST / JOINER）
     * @param seq      可选；对方是加入者时 = 第几位
     */
    @Transactional(readOnly = true)
    public TimerShareLikeHistoryResponse history(Long userId, String token, String relation, Integer seq) {
        long nowMs = clock.getAsLong();
        boolean peerRequested = token != null || relation != null || seq != null;
        TimerShareLikeSubject requested = peerRequested ? TimerShareLikeSubject.PEER : TimerShareLikeSubject.SELF;
        if (!opsConfigService.isEnabled(OpsConfigService.KEY_TIMER_SHARE_LIKE_ENABLED, true)) {
            return new TimerShareLikeHistoryResponse(false, requested.name(), new Summary(0, 0, 0), List.of(), false, nowMs);
        }
        if (!readLimiter.tryAcquire("u" + userId, nowMs)) {
            log.warn("[timer-share] like history rate limited: uid={}", userId);
            throw new BusinessException(TimerShareService.CODE_TOO_FREQUENT, "操作过于频繁，请稍后再试");
        }

        // 记录主人：本人，或调用者在这张会话里的那位对家。解析不出 = 空记录（授权边界，见类注释）
        long subjectId = userId;
        if (peerRequested) {
            Long peer = token == null ? null : likeService.resolvePeerUserId(userId, token, relation, seq);
            if (peer == null) {
                return new TimerShareLikeHistoryResponse(true, TimerShareLikeSubject.UNRESOLVED.name(),
                        new Summary(0, 0, 0), List.of(), false, nowMs);
            }
            subjectId = peer;
        }

        Set<Long> shareIds = new LinkedHashSet<>();
        List<Long> asHost = shareRepository.findRecentIdsByHost(subjectId,
                PageRequest.of(0, TimerSharePolicy.LIKE_HISTORY_MAX_SHARES));
        List<Long> asJoiner = joinRepository.findRecentShareIdsByUser(subjectId,
                PageRequest.of(0, TimerSharePolicy.LIKE_HISTORY_MAX_SHARES));
        shareIds.addAll(asHost);
        shareIds.addAll(asJoiner);
        boolean truncated = asHost.size() >= TimerSharePolicy.LIKE_HISTORY_MAX_SHARES
                || asJoiner.size() >= TimerSharePolicy.LIKE_HISTORY_MAX_SHARES;

        List<TimerShareLike> rows = shareIds.isEmpty()
                ? List.of()
                : likeRepository.findInvolving(shareIds, subjectId, PageRequest.of(0, TimerSharePolicy.LIKE_HISTORY_MAX_ROWS));
        truncated = truncated || rows.size() >= TimerSharePolicy.LIKE_HISTORY_MAX_ROWS;

        List<LikeFact> facts = new ArrayList<>(rows.size());
        for (TimerShareLike row : rows) {
            Long atMs = TimerShareClock.epochMsOf(row.getUpdatedAt());
            facts.add(new LikeFact(row.getLikerId(), row.getLikedUserId(), atMs == null ? 0L : atMs));
        }
        List<GroupFact> groups = TimerShareLikeHistoryRules.group(subjectId, facts,
                TimerSharePolicy.LIKE_HISTORY_EVENTS_PER_GROUP);
        int[] totals = TimerShareLikeHistoryRules.totals(groups);

        if (groups.size() > TimerSharePolicy.LIKE_HISTORY_MAX_GROUPS) {
            truncated = true;
        }
        // PEER 视角下「我和 TA 之间」那一组最前（用户最想先看的），SELF 视角没有这个概念
        List<GroupFact> shown = pickShown(groups, peerRequested ? userId : null);

        Map<Long, User> users = new HashMap<>();
        if (!shown.isEmpty()) {
            List<Long> ids = new ArrayList<>(shown.size());
            for (GroupFact group : shown) {
                ids.add(group.peerUserId());
            }
            for (User user : userRepository.findByIdInAndDeletedFalse(ids)) {
                users.put(user.getId(), user);
            }
        }

        List<Group> out = new ArrayList<>(shown.size());
        for (GroupFact group : shown) {
            User user = users.get(group.peerUserId());
            List<Event> events = new ArrayList<>(group.events().size());
            for (EventFact event : group.events()) {
                events.add(new Event(event.direction().name(), event.atMs()));
            }
            out.add(new Group(peerRequested && group.peerUserId() == userId,
                    user == null ? null : user.getNickname(), user == null ? null : user.getAvatarUrl(),
                    group.received(), group.given(), group.lastAtMs(), events));
        }
        return new TimerShareLikeHistoryResponse(true, requested.name(), new Summary(totals[0], totals[1], totals[2]),
                out, truncated, nowMs);
    }

    /**
     * 实际下发的分组：调用者自己那组（若有）最前，其余按已排好的顺序，总数不超过上限。
     * 没有占位组——「TA 还没有任何点赞」由空 groups + 总览 0 表达，客户端画空态。
     */
    private static List<GroupFact> pickShown(List<GroupFact> groups, Long viewerId) {
        List<GroupFact> shown = new ArrayList<>();
        if (viewerId != null) {
            for (GroupFact group : groups) {
                if (group.peerUserId() == viewerId) {
                    shown.add(group);
                    break;
                }
            }
        }
        for (GroupFact group : groups) {
            if (shown.size() >= TimerSharePolicy.LIKE_HISTORY_MAX_GROUPS) {
                break;
            }
            if (viewerId == null || group.peerUserId() != viewerId) {
                shown.add(group);
            }
        }
        return shown;
    }
}
