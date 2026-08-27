package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancer.repository.DemandRecordRepository;
import org.quwuting.quwutingservice.points.repository.DailyCheckinRepository;
import org.quwuting.quwutingservice.points.repository.PointsAccountRepository;
import org.quwuting.quwutingservice.points.repository.PointsTransactionRepository;
import org.quwuting.quwutingservice.user.dto.response.CheckinSummary;
import org.quwuting.quwutingservice.user.dto.response.ClaimSummary;
import org.quwuting.quwutingservice.user.dto.response.DemandSummary;
import org.quwuting.quwutingservice.user.dto.response.PointsSummary;
import org.quwuting.quwutingservice.user.dto.response.ReportSummary;
import org.quwuting.quwutingservice.venueclaim.repository.VenueClaimRepository;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.quwuting.quwutingservice.venuefeedback.repository.VenueFeedbackRepository;
import org.quwuting.quwutingservice.venuestatusreport.repository.StatusReportRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
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
 * （积分账户收支 / 需求单 / 履约 / 上报 / 认领 / 打卡 / 最近活跃）集中在本服务，
 * 列表（GET /admin/users）与详情（GET /admin/users/{id}）复用同一批批量聚合方法——
 * 后续新增维度 = 加一个 repository GROUP BY 方法 + 一个聚合方法，列表/详情自动
 * 获得，杜绝「每次增强从零写聚合」的散落模式。
 * <p>
 * 性能：全部走<b>批量 GROUP BY</b>（一次查询覆盖一页用户，IN :userIds），
 * 结果集 = 用户数级别，内存合并无压力；单用户详情复用同一方法（集合 = 1）。
 * <p>
 * 最近活跃口径（<b>单一定义</b>，列表排序 / 列表行展示 / 详情展示 / 统计概览
 * 四端一致）：MAX(用户资料更新 updated_at、积分流水、邀约、打卡 的 created_at)——
 * 覆盖高频行为（打卡/解锁/赠送/采纳走流水）、中频行为（邀约）与资料维护；
 * 最低回退 = 加入时间（createdAt，资料维护兜底——从未有任何行为的用户
 * 「最近活跃」= 加入时间，列表行展示与排序、统计概览四端口径一致）。
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

    // ── 需求单（总数 + 履约 + 按状态分布；存量 NULL 状态 = APPROVED） ──────────

    /**
     * 批量需求单概览：总数 + 履约数（fulfilled_at 非空）+ 按状态分布
     * （TreeMap 字典序 key，前端按序渲染零逻辑）。无需求记录用户 → 全 0。
     */
    @Transactional(readOnly = true)
    public Map<Long, DemandSummary> demandSummaries(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> totals = toMap(demandRecordRepository.countGroupByUserIds(userIds));
        Map<Long, Long> fulfilled = toMap(demandRecordRepository.countFulfilledGroupByUserIds(userIds));
        Map<String, Map<Long, Long>> byStatus = demandRecordRepository.countByUserAndStatusGroup(userIds).stream()
                .collect(Collectors.groupingBy(
                        r -> (String) r[1],
                        Collectors.toMap(r -> (Long) r[0], r -> (Long) r[2], Long::sum)));
        return userIds.stream().collect(Collectors.toMap(
                Function.identity(),
                userId -> {
                    Map<String, Long> dist = new TreeMap<>();
                    byStatus.forEach((status, counts) -> {
                        long c = counts.getOrDefault(userId, 0L);
                        if (c > 0) {
                            dist.put(status, c);
                        }
                    });
                    return new DemandSummary(totals.getOrDefault(userId, 0L),
                            fulfilled.getOrDefault(userId, 0L), dist);
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

    // ── 最近活跃（四源 MAX；单一定义，见类注释） ──────────────────────────────

    /**
     * 批量最近活跃时间：MAX(资料更新 updatedAt、积分流水、邀约、打卡 created_at)，
     * 任一源为空（null）时其余源兜底；<b>最低回退 = 加入时间（createdAt）</b>——
     * 从未有任何行为的用户「最近活跃」= 加入时间（资料维护兜底，四端口径一致）。
     * 返回 userId → LocalDateTime；用户集合空 → 空 Map。
     */
    @Transactional(readOnly = true)
    public Map<Long, LocalDateTime> lastActiveFor(Collection<Long> userIds,
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
