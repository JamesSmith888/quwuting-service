package org.quwuting.quwutingservice.timershare.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.timershare.entity.TimerShare;
import org.quwuting.quwutingservice.timershare.entity.TimerShareJoin;
import org.quwuting.quwutingservice.timershare.enums.TimerShareJoinOutcome;
import org.quwuting.quwutingservice.timershare.enums.TimerShareStatus;
import org.quwuting.quwutingservice.timershare.repository.TimerShareJoinRepository;
import org.quwuting.quwutingservice.timershare.repository.TimerShareRepository;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 计时分享的<b>事务性持久化操作</b>（2026-10-07，V42；文档 = docs/agents/54-timer-share.md §并发）。
 * <p>
 * 为什么拆出独立 Bean 而不是写在 {@link TimerShareService} 里：创建路径需要「写入遇到唯一键冲突
 * → 事务结束后重试」。Spring 的 {@code @Transactional} 是代理式的，<b>同类自调用不经代理</b>，而
 * 事务内一旦出现唯一键异常该事务就是 rollback-only，必须在事务<b>外</b>捕获并重试。所以事务边界
 * 放在本 Bean 的公开方法上，{@code TimerShareService} 作为非事务的编排层在外面捕获。
 * 这也让事务内只剩纯 DB 操作——<b>事务内禁远程 I/O</b>（13 号文档「事务边界」），微信外呼
 * （生成码图）与运营开关读取都在编排层。
 * <p>
 * 并发正确性靠<b>悲观行锁</b>而非 save + catch 唯一键：加入路径对分享行 {@code FOR UPDATE}，把
 * 「人数上限判定 → 计数自增 → 流水写入」串行化，不存在「两个人同时抢最后一个名额」或
 * 「同一人并发双扫写出两行」的窗口，也就没有需要吞的唯一键异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimerShareStore {

    private final TimerShareRepository shareRepository;
    private final TimerShareJoinRepository joinRepository;
    private final UserRepository userRepository;

    /** 创建 / 刷新的结果：实体 + 本次是否新建行 */
    public record Upserted(TimerShare share, boolean created) {
    }

    /** 加入的结果：预期业务状态 + 命中的分享行（仅 JOINED / ALREADY_JOINED 带行） */
    public record JoinResult(TimerShareJoinOutcome outcome, TimerShare share, boolean newUser) {
        static JoinResult of(TimerShareJoinOutcome outcome) {
            return new JoinResult(outcome, null, false);
        }
    }

    /**
     * 创建或刷新主持方某一场计时的分享会话。
     * <p>
     * 已存在 (host, session_key) → 原地刷新快照与有效期（token 不变 ⇒ 码图不变），并把 CLOSED 重新
     * 激活（「单独结算后撤销」会让同一场重新回到计时中）；不存在 → 新建（受每日新建上限约束）。
     *
     * @param newToken  新建时使用的 token（刷新时忽略）；由编排层生成，撞号重试时换新
     * @throws org.springframework.dao.DataIntegrityViolationException 并发新建同一场 / token 撞号时由唯一键抛出，
     *         事务回滚，编排层据此重试
     *
     * <h3>为什么是 READ_COMMITTED（真实 MySQL 8 实测的根因，2026-10-07）</h3>
     * 首次创建走的是「{@code SELECT … FOR UPDATE} 一行不存在的 (host, session_key) → 再 INSERT」。默认的
     * REPEATABLE READ 下，对不存在的行加锁会落成<b>间隙锁</b>：多个并发事务可以<b>同时</b>持有同一个间隙锁，
     * 随后各自 INSERT 时，插入意向锁与对方的间隙锁互相冲突 ⇒ <b>死锁（错误 1213）</b>。真机实测 8 线程并发首次创建同一场，
     * 30 轮里 209 次被回滚成死锁，而不是预期的「一个赢、其余撞唯一键」。READ_COMMITTED 不加间隙锁：
     * 并发者都看到「行不存在」，INSERT 时在唯一索引上排队，输家拿到 1062（唯一键冲突）——这正是编排层已经会重试的那一种。
     * 只有本方法需要（加入 / 关闭锁的是<b>已存在</b>的行，不产生间隙锁）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Upserted createOrRefresh(Long hostUserId, String sessionKey, TimerShareClock.Anchors anchors,
                                    String ruleJson, Long venueId, Long parentShareId,
                                    String newToken, long nowMs) {
        Optional<TimerShare> existing = shareRepository.findByHostAndSessionKeyForUpdate(hostUserId, sessionKey);
        if (existing.isPresent()) {
            TimerShare share = existing.get();
            applySnapshot(share, anchors, ruleJson, venueId, nowMs);
            share.setStatus(TimerShareStatus.ACTIVE);
            share.setClosedAtMs(null);
            // 重新激活即结清（2026-10-08，V45）：刷新发生在「单独结算后撤销、重新打开弹层」等路径——
            // 主持方又回到计时中，「已结算」事实必须一并作废（与 closedAtMs 同口径，见 V45 迁移头注）
            share.setHostSettledAtMs(null);
            share.setHostSettledNetSeconds(null);
            share.setRefreshCount(share.getRefreshCount() + 1);
            return new Upserted(shareRepository.save(share), false);
        }

        long createdToday = shareRepository.countNewSince(hostUserId, LocalDateTime.now().minusDays(1));
        if (createdToday >= TimerSharePolicy.MAX_NEW_SHARES_PER_DAY) {
            log.warn("[timer-share] daily create cap hit: host={} count={}", hostUserId, createdToday);
            throw new BusinessException(1006, "今天同步的次数太多了，明天再试");
        }

        TimerShare share = new TimerShare();
        share.setToken(newToken);
        share.setHostUserId(hostUserId);
        share.setSessionKey(sessionKey);
        share.setParentShareId(parentShareId);
        share.setStatus(TimerShareStatus.ACTIVE);
        share.setMaxJoins(TimerSharePolicy.MAX_JOINS);
        share.setJoinCount(0);
        share.setRefreshCount(1);
        applySnapshot(share, anchors, ruleJson, venueId, nowMs);
        // saveAndFlush：唯一键冲突必须在本事务内立刻暴露，而不是拖到提交时才在代理外抛出
        return new Upserted(shareRepository.saveAndFlush(share), true);
    }

    /**
     * 扫码加入。所有「预期的业务状态」以 {@link TimerShareJoinOutcome} 返回，不抛异常。
     * <p>
     * 判定顺序即优先级（每一步都只依赖前面已确定的事实）：
     * 不存在 → 自己的码 → 已关闭 → 已过期 → 已加入过（幂等，<b>不受人数上限约束</b>：
     * 已经在名单里的人重扫不该因为后来者占满了名额而被拒）→ 人数已满 → 快照可解析 → 新加入。
     */
    @Transactional
    public JoinResult join(String token, Long userId, long nowMs) {
        Optional<TimerShare> found = shareRepository.findByTokenForUpdate(token);
        if (found.isEmpty()) {
            return JoinResult.of(TimerShareJoinOutcome.NOT_FOUND);
        }
        TimerShare share = found.get();
        if (share.getHostUserId().equals(userId)) {
            return JoinResult.of(TimerShareJoinOutcome.SELF);
        }
        if (share.getStatus() == TimerShareStatus.CLOSED) {
            return JoinResult.of(TimerShareJoinOutcome.CLOSED);
        }
        if (nowMs > share.getExpiresAtMs()) {
            return JoinResult.of(TimerShareJoinOutcome.EXPIRED);
        }
        if (joinRepository.findByShareIdAndUserIdAndDeletedFalse(share.getId(), userId).isPresent()) {
            return new JoinResult(TimerShareJoinOutcome.ALREADY_JOINED, share, false);
        }
        if (share.getJoinCount() >= share.getMaxJoins()) {
            return JoinResult.of(TimerShareJoinOutcome.FULL);
        }
        // 规则 JSON 损坏（只可能来自手改库）：在写入流水 / 占用名额之前就拒绝，别让一条坏数据白耗一个名额
        if (TimerShareRules.parseOrNull(share.getRuleJson()) == null) {
            return JoinResult.of(TimerShareJoinOutcome.NOT_FOUND);
        }

        boolean newUser = isNewUser(userId, nowMs);
        TimerShareJoin join = new TimerShareJoin();
        join.setShareId(share.getId());
        join.setUserId(userId);
        join.setNewUser(newUser);
        join.setNetSecondsAtJoin(TimerShareClock.netSecondsAt(anchorsOf(share), nowMs));
        joinRepository.save(join);
        share.setJoinCount(share.getJoinCount() + 1);
        shareRepository.save(share);
        return new JoinResult(TimerShareJoinOutcome.JOINED, share, newUser);
    }

    /**
     * 主持方关闭（结算 / 单独结算 / 丢弃计时时的清理）。幂等：不存在 / 不是自己的 / 已关闭 → false。
     * 统一 false 而不区分原因——既不泄露 token 是否存在，也让清理调用无需处理失败。
     */
    @Transactional
    public boolean close(String token, Long hostUserId, long nowMs) {
        Optional<TimerShare> found = shareRepository.findByTokenForUpdate(token);
        if (found.isEmpty()) {
            return false;
        }
        TimerShare share = found.get();
        if (!share.getHostUserId().equals(hostUserId) || share.getStatus() == TimerShareStatus.CLOSED) {
            return false;
        }
        share.setStatus(TimerShareStatus.CLOSED);
        share.setClosedAtMs(nowMs);
        shareRepository.save(share);
        return true;
    }

    /**
     * 结算事实上报（2026-10-08，V45）。调用者是主持方 → 写分享行；是该会话的加入者 → 写其流水行；
     * 两者都不是（或 token 不存在）→ {@code recorded=false}（静默，调用方据此忽略）。
     * <p>
     * 三条刻意的设计（见 V45 迁移头注）：① <b>不检查会话状态</b>——结算大量发生在码过期 / 会话关闭之后，
     * 这不是异常路径；② <b>覆盖语义</b>——重复上报覆盖为最新一次（客户端重试与「撤销后重结算」都靠它）；
     * ③ 结算时刻由服务端盖章（{@code nowMs} 由编排层取），不信客户端时间。
     * <p>
     * 锁的是分享行（{@code FOR UPDATE}）：与 join / close 保持同一把锁、同一加锁顺序；结算与「加入」
     * 并发时天然串行，不会出现「刚写完结算又被人刷进新状态」的读改写竞态。
     */
    @Transactional
    public boolean settle(String token, Long userId, Integer netSeconds, long nowMs) {
        Optional<TimerShare> found = shareRepository.findByTokenForUpdate(token);
        if (found.isEmpty()) {
            return false;
        }
        TimerShare share = found.get();
        if (share.getHostUserId().equals(userId)) {
            share.setHostSettledAtMs(nowMs);
            share.setHostSettledNetSeconds(netSeconds);
            shareRepository.save(share);
            return true;
        }
        Optional<TimerShareJoin> join = joinRepository.findByShareIdAndUserIdAndDeletedFalse(share.getId(), userId);
        if (join.isEmpty()) {
            return false;
        }
        TimerShareJoin row = join.get();
        row.setSettledAtMs(nowMs);
        row.setSettledNetSeconds(netSeconds);
        joinRepository.save(row);
        return true;
    }

    /**
     * 加入者视角读会话（peer 接口用，2026-10-08，V45）：token 找到会话<b>且</b>调用者确实加入过它才返回。
     * 主持方不从此通道读（那是 status 的职责）；任何状态都算（CLOSED / EXPIRED 下结算事实仍要可读）。
     */
    @Transactional(readOnly = true)
    public Optional<TimerShare> findForJoiner(String token, Long userId) {
        return shareRepository.findByTokenAndDeletedFalse(token)
                .filter(s -> joinRepository.findByShareIdAndUserIdAndDeletedFalse(s.getId(), userId).isPresent());
    }

    /** 会话的加入流水（按加入先后；status 装配用）。调用方保证 shareId 存在 */
    @Transactional(readOnly = true)
    public List<TimerShareJoin> listJoins(Long shareId) {
        return joinRepository.findByShareIdAndDeletedFalseOrderByIdAsc(shareId);
    }

    /** 主持方轮询用：只读取自己的会话（不是自己的一律视作不存在） */
    @Transactional(readOnly = true)
    public Optional<TimerShare> findOwned(String token, Long hostUserId) {
        return shareRepository.findByTokenAndDeletedFalse(token)
                .filter(s -> s.getHostUserId().equals(hostUserId));
    }

    /** 码图端点用：仅当会话存在、ACTIVE 且未过期才允许生成码图（防止用随意 token 触发微信外呼） */
    @Transactional(readOnly = true)
    public boolean isJoinable(String token, long nowMs) {
        return shareRepository.findByTokenAndDeletedFalse(token)
                .filter(s -> s.getStatus() == TimerShareStatus.ACTIVE && nowMs <= s.getExpiresAtMs())
                .isPresent();
    }

    /** 传播链：token → 会话 id（不存在 / 格式非法 → empty；任何状态都算，包含已过期与已关闭） */
    @Transactional(readOnly = true)
    public Optional<Long> findIdByToken(String token) {
        return shareRepository.findByTokenAndDeletedFalse(token).map(TimerShare::getId);
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private static void applySnapshot(TimerShare share, TimerShareClock.Anchors anchors,
                                      String ruleJson, Long venueId, long nowMs) {
        share.setStartServerMs(anchors.startServerMs());
        share.setExcludedSeconds(anchors.excludedSeconds());
        share.setPausedAtServerMs(anchors.pausedAtServerMs());
        share.setRuleJson(ruleJson);
        share.setVenueId(venueId);
        share.setSnapshotAtMs(nowMs);
        share.setExpiresAtMs(nowMs + TimerSharePolicy.SNAPSHOT_TTL_MS);
    }

    /** 实体 → 服务端时间轴锚点（{@link TimerShareClock} 的唯一入参形态） */
    static TimerShareClock.Anchors anchorsOf(TimerShare share) {
        return new TimerShareClock.Anchors(share.getStartServerMs(), share.getExcludedSeconds(),
                share.getPausedAtServerMs());
    }

    /**
     * 新用户判据：账号创建距加入 ≤ {@link TimerSharePolicy#NEW_USER_WINDOW_MS}。
     * 启发式——静默登录在用户第一次打开小程序时建号，经扫码进入的新用户建号时刻与加入时刻几乎重合；
     * 老用户的账号早已存在。它量的是「这次扫码带来了一个刚建号的账号」，不是严格的归因（用户可能
     * 先打开小程序逛了 20 分钟才扫），口径已在 54 号文档如实登记。
     */
    private boolean isNewUser(Long userId, long nowMs) {
        LocalDateTime createdAt = userRepository.findById(userId).map(User::getCreatedAt).orElse(null);
        if (createdAt == null) {
            return false;
        }
        long createdMs = createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long age = nowMs - createdMs;
        return age >= 0 && age <= TimerSharePolicy.NEW_USER_WINDOW_MS;
    }
}
