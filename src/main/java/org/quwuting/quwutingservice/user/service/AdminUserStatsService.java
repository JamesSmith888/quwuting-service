package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancer.repository.DemandRecordRepository;
import org.quwuting.quwutingservice.points.repository.DailyCheckinRepository;
import org.quwuting.quwutingservice.points.repository.PointsAccountRepository;
import org.quwuting.quwutingservice.points.repository.PointsTransactionRepository;
import org.quwuting.quwutingservice.user.dto.response.CheckinSummary;
import org.quwuting.quwutingservice.user.dto.response.ClaimSummary;
import org.quwuting.quwutingservice.user.dto.response.PointsSummary;
import org.quwuting.quwutingservice.user.dto.response.ReportSummary;
import org.quwuting.quwutingservice.user.dto.response.TopVenue;
import org.quwuting.quwutingservice.user.repository.UserBehaviorEvent;
import org.quwuting.quwutingservice.user.repository.UserBehaviorRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venueclaim.repository.VenueClaimRepository;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.quwuting.quwutingservice.venuefeedback.repository.VenueFeedbackRepository;
import org.quwuting.quwutingservice.venuestatusreport.repository.StatusReportRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户全维度聚合服务（2026-08-27 用户管理增强，docs/agents/23；仅 ADMIN 消费）。
 * <p>
 * <b>定位（系统性的长期方案）</b>：管理端用户管理需要的<b>所有行为维度聚合</b>
 * （积分账户收支 / 上报 / 认领 / 打卡 / 最近露面 / 常去门店）集中在本服务，
 * 列表（GET /admin/users）与详情（GET /admin/users/{id}）复用同一批批量聚合方法——
 * 后续新增维度 = 加一个 repository GROUP BY 方法 + 一个聚合方法，列表/详情自动
 * 获得，杜绝「每次增强从零写聚合」的散落模式。
 * <p>
 * 性能：全部走<b>批量 GROUP BY</b>（一次查询覆盖一页用户，IN :userIds），
 * 结果集 = 用户数级别，内存合并无压力；单用户详情复用同一方法（集合 = 1）。
 * <p>
 * <b>命名契约（2026-09-15 起，强制）</b>：本服务的「露面」口径（四源 MAX，含登录
 * 自动打卡）<b>不是</b>「活跃」。管理端一切「活跃」指标专指
 * {@code UserStatsSql.ACTIVE_FACT_UNION}（用户主动行为）口径，见
 * {@code AdminUserService#stats} 与 docs/agents/35；两个概念字面与语义都不得混用。
 * <p>
 * 最近露面口径（<b>单一定义</b>，列表排序 / 列表行展示 / 详情展示三端一致）：
 * MAX(用户资料更新 updated_at、积分流水、邀约、打卡 的 created_at)——覆盖高频行为
 * （打卡/解锁/赠送/采纳走流水）、中频行为（邀约）与资料维护；最低回退 = 加入时间
 * （createdAt，资料维护兜底——从未有任何行为的用户「最近露面」= 加入时间，
 * 列表行展示与排序、详情三端口径一致）。
 */
@Service
@RequiredArgsConstructor
public class AdminUserStatsService {

    private final PointsAccountRepository pointsAccountRepository;
    private final PointsTransactionRepository transactionRepository;
    private final DemandRecordRepository demandRecordRepository;
    private final DailyCheckinRepository checkinRepository;
    private final VenueFeedbackRepository feedbackRepository;
    private final StatusReportRepository statusReportRepository;
    private final VenueClaimRepository claimRepository;
    private final UserBehaviorRepository behaviorRepository;
    private final VenueRepository venueRepository;

    /**
     * 「常去门店」窗口（近 90 天，含今日）：太短（7 天）绝大多数用户只有零星动作、
     * 认不出人，太长则把早已不去的店也算进来——90 天是「还记得、也还没过期」的量级。
     */
    private static final int VENUE_AFFINITY_WINDOW_DAYS = 90;

    /**
     * 产生「常去门店」的最小动作次数：<b>1 次是偶然，不是身份标签</b>。
     * 把一次浏览渲染成「常去 XX」会让运营据此错误归类，故阈值在后端判、
     * 未达阈值一律下发 null（前端零口径，见 {@link TopVenue}）。
     */
    private static final long MIN_VENUE_AFFINITY_COUNT = 2;

    /**
     * 门店维度事件码（<b>由行为目录派生，禁止手写清单</b>）：
     * 手写清单的代价是以后新增一个门店类事件时会静默漏掉它，且没有任何告警。
     */
    private static final List<String> VENUE_EVENT_CODES = Arrays.stream(UserBehaviorEvent.values())
            .filter(event -> event.refKind() == UserBehaviorEvent.RefKind.VENUE)
            .map(UserBehaviorEvent::code)
            .toList();

    // ── 积分账户（余额 + 累计收支 + 流水条数） ────────────────────────────────

    /**
     * 批量积分账户概览：balance/earnedTotal/spentTotal（账户表快照，一次查询）
     * + 流水条数（行为活跃度）。无账户用户 → 全 0（从未参与积分活动）。
     * 返回 userId → PointsSummary；用户集合空 → 空 Map。
     */
    @Transactional(readOnly = true)
    public Map<Long, PointsSummary> pointsSummaries(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, long[]> snapshots = pointsAccountRepository
                .findAccountSummariesByUserIds(userIds).stream()
                .collect(Collectors.toMap(
                        r -> (Long) r[0],
                        r -> new long[]{(Long) r[1], (Long) r[2], (Long) r[3]}));
        Map<Long, Long> txCounts = toMap(transactionRepository.countGroupByUserIds(userIds));
        return userIds.stream().collect(Collectors.toMap(
                Function.identity(),
                userId -> {
                    long[] s = snapshots.getOrDefault(userId, new long[]{0L, 0L, 0L});
                    return new PointsSummary(s[0], s[1], s[2], txCounts.getOrDefault(userId, 0L));
                }));
    }

    // ── 上报（信息上报 + 暂停营业报告合并：总数 + 待处理） ─────────────────────

    /**
     * 批量上报概览：总数 = 信息上报（未软删且 user_id 非空）+ 暂停营业报告
     * （未软删）；待处理 = 信息上报 PENDING + 报告 admin_action IS NULL。
     * 无上报用户 → 全 0。采纳数见 ContributionBrief（贡献档案维度，不重复）。
     */
    @Transactional(readOnly = true)
    public Map<Long, ReportSummary> reportSummaries(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> feedbackTotal = toMap(feedbackRepository.countGroupByUserIds(userIds));
        Map<Long, Long> feedbackPending = toMap(feedbackRepository.countGroupByUserIdsAndStatus(
                userIds, ReportStatus.PENDING));
        Map<Long, Long> reportTotal = toMap(statusReportRepository.countGroupByUserIds(userIds));
        Map<Long, Long> reportPending = toMap(statusReportRepository.countPendingGroupByUserIds(userIds));
        return userIds.stream().collect(Collectors.toMap(
                Function.identity(),
                userId -> new ReportSummary(
                        feedbackTotal.getOrDefault(userId, 0L) + reportTotal.getOrDefault(userId, 0L),
                        feedbackPending.getOrDefault(userId, 0L) + reportPending.getOrDefault(userId, 0L))));
    }

    // ── 认领（总数 + 按状态分布） ─────────────────────────────────────────────

    /**
     * 批量认领概览：总数 + 按状态分布（TreeMap 字典序 key：PENDING/APPROVED/
     * REJECTED/WITHDRAWN）。无认领用户 → 全 0。
     */
    @Transactional(readOnly = true)
    public Map<Long, ClaimSummary> claimSummaries(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<Long, Long>> byStatus = claimRepository.countByUserAndStatusGroup(userIds).stream()
                .collect(Collectors.groupingBy(
                        r -> (String) r[1],
                        Collectors.toMap(r -> (Long) r[0], r -> (Long) r[2], Long::sum)));
        return userIds.stream().collect(Collectors.toMap(
                Function.identity(),
                userId -> {
                    Map<String, Long> dist = new TreeMap<>();
                    long total = 0;
                    for (Map.Entry<String, Map<Long, Long>> e : byStatus.entrySet()) {
                        long c = e.getValue().getOrDefault(userId, 0L);
                        if (c > 0) {
                            dist.put(e.getKey(), c);
                            total += c;
                        }
                    }
                    return new ClaimSummary(total, dist);
                }));
    }

    // ── 打卡（总天数 + 连续天数 + 最近时间） ───────────────────────────────────

    /**
     * 单用户打卡概览（详情页用，集合 = 1；连续天数需逐日序列，不做批量——
     * 列表行不展示连续天数，见 CheckinSummary javadoc）。无打卡 → 全 0 / null。
     */
    @Transactional(readOnly = true)
    public CheckinSummary checkinSummary(Long userId) {
        List<LocalDate> dates = checkinRepository.findDatesByUserIdDesc(userId, PageRequest.of(0, 400));
        if (dates.isEmpty()) {
            return new CheckinSummary(0, 0, null);
        }
        LocalDateTime lastAt = checkinRepository.findLatestGroupByUserIds(List.of(userId)).stream()
                .findFirst().map(r -> (LocalDateTime) r[1]).orElse(null);
        return new CheckinSummary(dates.size(), computeStreak(dates), lastAt);
    }

    /**
     * 连续打卡天数：从最近一天往回数连续自然日。锚点 = 今天或昨天（今天未打不
     * 打断连续——昨晚打卡、今晨未打的真实用户不应归零）；与锚点不相邻 = 连续已
     * 断（0）。dates 已按日期倒序，且 UNIQUE(user_id, checkin_date) 保证无重复。
     */
    static long computeStreak(List<LocalDate> datesDesc) {
        if (datesDesc.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate expected = datesDesc.get(0);
        if (expected.isBefore(today.minusDays(1)) || expected.isAfter(today)) {
            return 0; // 最近打卡早于昨天（连续已断）或数据异常（未来日期）
        }
        long streak = 0;
        for (LocalDate d : datesDesc) {
            if (!d.equals(expected)) {
                break;
            }
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }

    // ── 最近露面（四源 MAX；单一定义，见类注释） ──────────────────────────────

    /**
     * 批量最近露面时间：MAX(资料更新 updatedAt、积分流水、邀约、打卡 created_at)，
     * 任一源为空（null）时其余源兜底；<b>最低回退 = 加入时间（createdAt）</b>——
     * 从未有任何行为的用户「最近露面」= 加入时间（资料维护兜底，三端口径一致）。
     * 返回 userId → LocalDateTime；用户集合空 → 空 Map。
     * <p>
     * <b>勿与「活跃」混用</b>：含登录自动打卡，只回答「这个账号最后一次出现」。
     */
    @Transactional(readOnly = true)
    public Map<Long, LocalDateTime> lastSeenFor(Collection<Long> userIds,
                                                Map<Long, LocalDateTime> profileUpdatedAt) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LocalDateTime> latest = new LinkedHashMap<>();
        userIds.forEach(id -> latest.put(id, profileUpdatedAt.get(id)));
        mergeLatest(latest, toTimeMap(transactionRepository.findLatestGroupByUserIds(userIds)));
        mergeLatest(latest, toTimeMap(demandRecordRepository.findLatestGroupByUserIds(userIds)));
        mergeLatest(latest, toTimeMap(checkinRepository.findLatestGroupByUserIds(userIds)));
        return latest;
    }

    // ── 常去门店（辨认维度，2026-09-29） ──────────────────────────────────────

    /**
     * 批量「最常去的门店」——<b>管理端辨认匿名用户的描述性身份</b>：
     * 窗口内该用户动作次数最多的那家门店；未达 {@link #MIN_VENUE_AFFINITY_COUNT}
     * 或门店已软删 → 该用户不在结果 Map 里（前端收到 null 即不渲染）。
     * <p>
     * <b>为什么它能辨认人</b>：平台 98% 的用户昵称仍是注册默认值，昵称零辨认力；
     * 而「常去 XX 舞厅的那个人」是运营真正记得住、也能在沟通里指代的描述。
     * 它与 {@link #lastSeenFor} 同签名风格（批量、单用户复用、空集合返回空 Map），
     * 列表与详情自动同时获得。
     * <p>
     * <b>事件范围刻意包含非活跃档</b>（认领 / 上报暂停）：辨认要的是「他对哪家店
     * 有过动作」，不是「他算不算活跃」——一个只认领过门店、从没浏览过的人，
     * 恰恰是最需要被认出来的那类（门店维护者）。
     *
     * @return userId → TopVenue；不含无符合条件的用户
     */
    @Transactional(readOnly = true)
    public Map<Long, TopVenue> topVenuesFor(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        LocalDate sinceDay = LocalDate.now().minusDays(VENUE_AFFINITY_WINDOW_DAYS - 1L);
        Map<Long, long[]> best = new HashMap<>();
        for (UserBehaviorRepository.VenueAffinityRow row :
                behaviorRepository.listVenueAffinityByUserIds(userIds, sinceDay, VENUE_EVENT_CODES)) {
            Long userId = row.getUserId();
            Long venueId = row.getRefId();
            if (userId == null || venueId == null) {
                continue;
            }
            long cnt = row.getCnt() == null ? 0L : row.getCnt();
            long[] cur = best.get(userId);
            if (cur == null || cnt > cur[1] || (cnt == cur[1] && venueId < cur[0])) {
                best.put(userId, new long[]{venueId, cnt});
            }
        }
        Map<Long, long[]> qualified = new HashMap<>();
        best.forEach((userId, arr) -> {
            if (arr[1] >= MIN_VENUE_AFFINITY_COUNT) {
                qualified.put(userId, arr);
            }
        });
        if (qualified.isEmpty()) {
            return Map.of();
        }
        Map<Long, Venue> venues = venueRepository
                .findByIdInAndDeletedFalse(new ArrayList<>(
                        qualified.values().stream().map(arr -> arr[0]).toList()))
                .stream()
                .collect(Collectors.toMap(Venue::getId, Function.identity(), (a, b) -> a, HashMap::new));
        Map<Long, TopVenue> result = new HashMap<>();
        qualified.forEach((userId, arr) -> {
            Venue venue = venues.get(arr[0]);
            if (venue != null) {
                result.put(userId, new TopVenue(venue.getId(), venue.getName(), venue.getCity(), arr[1]));
            }
        });
        return result;
    }

    private static void mergeLatest(Map<Long, LocalDateTime> acc, Map<Long, LocalDateTime> src) {
        src.forEach((userId, at) -> acc.merge(userId, at,
                (a, b) -> a != null && b != null ? (a.isAfter(b) ? a : b) : (a != null ? a : b)));
    }

    private static Map<Long, LocalDateTime> toTimeMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> (Long) r[0], r -> (LocalDateTime) r[1]));
    }

    private static Map<Long, Long> toMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> (Long) r[0], r -> (Long) r[1]));
    }
}
