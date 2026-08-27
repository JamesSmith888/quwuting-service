package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.points.repository.PointsAccountRepository;
import org.quwuting.quwutingservice.points.service.ContributionService;
import org.quwuting.quwutingservice.user.dto.response.AdminUserDetailResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserItem;
import org.quwuting.quwutingservice.user.dto.response.AdminUserStatsResponse;
import org.quwuting.quwutingservice.user.dto.response.CheckinSummary;
import org.quwuting.quwutingservice.user.dto.response.ClaimSummary;
import org.quwuting.quwutingservice.user.dto.response.DemandSummary;
import org.quwuting.quwutingservice.user.dto.response.PointsSummary;
import org.quwuting.quwutingservice.user.dto.response.ReportSummary;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.enums.UserSortMode;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 管理端用户列表/详情服务（2026-08-27，docs/agents/23；仅 ADMIN；2026-08-27
 * 用户管理增强）。
 * <p>
 * 定位：运营查用户/看贡献/识别异常的列表——行 = 用户公开资料（昵称/头像/角色/
 * 加入天数 + <b>V53 资料字段 age/gender/city</b>）+ 积分余额 + 贡献档案摘要
 * （贡献值 + 等级称号）+ 行为信号（需求/履约/最近活跃）；详情 = 完整画像
 * （积分收支 + 贡献明细 + 需求/上报/认领分布 + 打卡连续性）。展示边界 =
 * 管理端（Controller requireAdmin），<b>不建公开用户主页</b>（2026-08-21 审核
 * 驳回沉淀，见 AGENTS.md「小程序类目合规 UGC 红线」）；openId 等敏感字段绝不下发。
 * <p>
 * 性能：一页用户的余额/贡献/需求/履约/最近活跃聚合走<b>批量查询</b>
 * （findBalancesByUserIds / ContributionService.aggregatesFor /
 * AdminUserStatsService 各 GROUP BY），避免 N+1。
 * <p>
 * 维度聚合单一权威 = {@link AdminUserStatsService}（新增维度加在那里，列表与
 * 详情自动获得——长期方案，杜绝散落聚合）。
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /** 无昵称用户的展示占位（与 UserPublicService 同口径，管理端列表可读性） */
    private static final String NICKNAME_FALLBACK = "舞友";

    private final UserRepository userRepository;
    private final PointsAccountRepository pointsAccountRepository;
    private final ContributionService contributionService;
    private final AdminUserStatsService statsService;

    /** 用户分页列表（keyword 昵称模糊 + role/city 筛选 + 排序模式，全部可空/可缺省） */
    @Transactional(readOnly = true)
    public Page<AdminUserItem> list(String keyword, UserRole role, String city,
                                    UserSortMode sort, int page, int size) {
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        PageRequest pageable = PageRequest.of(page, size);
        Page<User> users = switch (sort == null ? UserSortMode.LATEST_JOINED : sort) {
            case POINTS_DESC -> userRepository.findPageByFiltersOrderByPoints(kw, role, city, pageable);
            // 原生 SQL 绑定 enum 无 JPA 元数据（ORDINAL 错配先例）——role 传 name() 字符串
            case LAST_ACTIVE_DESC -> userRepository.findPageByFiltersOrderByLastActive(
                    kw, role == null ? null : role.name(), city, pageable);
            case LATEST_JOINED -> userRepository.findPageByFilters(kw, role, city, pageable);
        };
        List<Long> userIds = users.getContent().stream().map(User::getId).toList();
        Map<Long, Long> balances = toMap(pointsAccountRepository.findBalancesByUserIds(userIds));
        Map<Long, ContributionService.ContributionAggregate> contributions =
                contributionService.aggregatesFor(userIds);
        Map<Long, DemandSummary> demands = statsService.demandSummaries(userIds);
        Map<Long, LocalDateTime> lastActive = statsService.lastActiveFor(userIds, profileUpdatedAt(users.getContent()));
        return users.map(u -> toItem(u, balances.getOrDefault(u.getId(), 0L),
                contributions.get(u.getId()), demands.get(u.getId()), lastActive.get(u.getId())));
    }

    /**
     * 用户详情（GET /admin/users/{id}，仅 ADMIN）：公开资料 + 积分账户收支 +
     * 贡献档案完整明细 + 需求/上报/认领分布 + 打卡连续性——管理端列表行点击进入，
     * 运营查看任意用户的<b>完整画像</b>（识别异常/刷分/流失）。用户不存在/已软删
     * → 1004。
     */
    @Transactional(readOnly = true)
    public AdminUserDetailResponse detail(Long id) {
        User user = userRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
        List<Long> ids = List.of(id);
        PointsSummary points = statsService.pointsSummaries(ids).getOrDefault(id, emptyPoints());
        DemandSummary demand = statsService.demandSummaries(ids).getOrDefault(id, emptyDemand());
        ReportSummary reports = statsService.reportSummaries(ids).getOrDefault(id, emptyReports());
        ClaimSummary claims = statsService.claimSummaries(ids).getOrDefault(id, emptyClaims());
        CheckinSummary checkin = statsService.checkinSummary(id);
        LocalDateTime lastActive = statsService.lastActiveFor(ids, profileUpdatedAt(List.of(user)))
                .getOrDefault(id, null);
        long joinedDays = joinedDays(user);
        String nickname = displayName(user);
        return new AdminUserDetailResponse(
                id,
                nickname,
                user.getAvatarUrl(),
                user.getRole(),
                joinedDays,
                user.getCreatedAt(),
                user.getAge(),
                user.getGender(),
                user.getCity(),
                lastActive,
                points,
                contributionService.briefFor(id),
                demand,
                reports,
                claims,
                checkin);
    }

    /** 统计概览（GET /admin/users/stats）：总用户 / 今日新增 / 管理员 / 近 7 日活跃 */
    @Transactional(readOnly = true)
    public AdminUserStatsResponse stats() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime activeSince = now.minusDays(7);
        return new AdminUserStatsResponse(
                userRepository.countByDeletedFalse(),
                userRepository.countByDeletedFalseAndCreatedAtGreaterThanEqual(todayStart),
                userRepository.countByDeletedFalseAndRole(UserRole.ADMIN),
                userRepository.countActiveSince(activeSince));
    }

    private AdminUserItem toItem(User user, long pointsBalance,
                                 ContributionService.ContributionAggregate agg,
                                 DemandSummary demand, LocalDateTime lastActiveAt) {
        return new AdminUserItem(
                user.getId(),
                displayName(user),
                user.getAvatarUrl(),
                user.getRole(),
                joinedDays(user),
                user.getCreatedAt(),
                user.getAge(),
                user.getGender(),
                user.getCity(),
                pointsBalance,
                agg != null ? agg.score() : 0,
                agg != null ? agg.levelName() : "新晋舞友",
                demand != null ? demand.total() : 0,
                demand != null ? demand.fulfilled() : 0,
                lastActiveAt);
    }

    /** 最近活跃四源之一的「资料更新」源：updated_at 兜底 createdAt（无任何更新记录） */
    private static Map<Long, LocalDateTime> profileUpdatedAt(List<User> users) {
        return users.stream().collect(java.util.stream.Collectors.toMap(
                User::getId,
                u -> u.getUpdatedAt() != null ? u.getUpdatedAt() : u.getCreatedAt()));
    }

    private static long joinedDays(User user) {
        return user.getCreatedAt() == null ? 0 :
                Math.max(0, ChronoUnit.DAYS.between(user.getCreatedAt().toLocalDate(), LocalDate.now()));
    }

    private static String displayName(User user) {
        return user.getNickname() == null || user.getNickname().isBlank()
                ? NICKNAME_FALLBACK : user.getNickname();
    }

    private static Map<Long, Long> toMap(List<Object[]> rows) {
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                row -> (Long) row[0],
                row -> (Long) row[1]));
    }

    private static PointsSummary emptyPoints() {
        return new PointsSummary(0, 0, 0, 0);
    }

    private static DemandSummary emptyDemand() {
        return new DemandSummary(0, 0, Map.of());
    }

    private static ReportSummary emptyReports() {
        return new ReportSummary(0, 0);
    }

    private static ClaimSummary emptyClaims() {
        return new ClaimSummary(0, Map.of());
    }
}
