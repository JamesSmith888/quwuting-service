package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.points.repository.PointsAccountRepository;
import org.quwuting.quwutingservice.points.service.ContributionService;
import org.quwuting.quwutingservice.user.dto.response.AdminUserDetailResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserItem;
import org.quwuting.quwutingservice.user.entity.User;
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
import java.util.stream.Collectors;

/**
 * 管理端用户列表服务（2026-08-27，docs/agents/23；仅 ADMIN）。
 * <p>
 * 定位：运营查用户/看贡献/识别异常的列表——行 = 用户公开资料（昵称/头像/角色/
 * 加入天数）+ 积分余额 + 贡献档案摘要（贡献值 + 等级称号）。展示边界 = 管理端
 * （Controller requireAdmin），<b>不建公开用户主页</b>（2026-08-21 审核驳回沉淀，
 * 见 AGENTS.md「小程序类目合规 UGC 红线」）；openId 等敏感字段绝不下发。
 * <p>
 * 性能：一页用户的余额与贡献聚合走<b>批量查询</b>（findBalancesByUserIds /
 * ContributionService.aggregatesFor），避免 N+1。
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /** 无昵称用户的展示占位（与 UserPublicService 同口径，管理端列表可读性） */
    private static final String NICKNAME_FALLBACK = "舞友";

    private final UserRepository userRepository;
    private final PointsAccountRepository pointsAccountRepository;
    private final ContributionService contributionService;

    /** 用户分页列表（keyword = 昵称模糊，空 = 全部；id 倒序） */
    @Transactional(readOnly = true)
    public Page<AdminUserItem> list(String keyword, int page, int size) {
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Page<User> users = userRepository.findPageByKeyword(kw, PageRequest.of(page, size));
        List<Long> userIds = users.getContent().stream().map(User::getId).toList();
        Map<Long, Long> balances = toMap(pointsAccountRepository.findBalancesByUserIds(userIds));
        Map<Long, ContributionService.ContributionAggregate> contributions =
                contributionService.aggregatesFor(userIds);
        return users.map(u -> toItem(u, balances.getOrDefault(u.getId(), 0L), contributions.get(u.getId())));
    }

    /**
     * 用户详情（GET /admin/users/{id}，仅 ADMIN）：公开资料 + 积分余额 + 贡献档案
     * 完整明细（等级 + 各维度计数）——管理端列表行点击进入，运营查看任意用户
     * 的详细贡献（识别异常/刷分）。用户不存在/已软删 → 1004。
     */
    @Transactional(readOnly = true)
    public AdminUserDetailResponse detail(Long id) {
        User user = userRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
        long pointsBalance = pointsAccountRepository.findByUserId(id)
                .map(a -> a.getBalance())
                .orElse(0L);
        long joinedDays = user.getCreatedAt() == null ? 0 :
                Math.max(0, ChronoUnit.DAYS.between(user.getCreatedAt().toLocalDate(), LocalDate.now()));
        String nickname = user.getNickname() == null || user.getNickname().isBlank()
                ? NICKNAME_FALLBACK : user.getNickname();
        return new AdminUserDetailResponse(
                id,
                nickname,
                user.getAvatarUrl(),
                user.getRole(),
                joinedDays,
                pointsBalance,
                contributionService.briefFor(id));
    }

    private AdminUserItem toItem(User user, long pointsBalance, ContributionService.ContributionAggregate agg) {
        long joinedDays = user.getCreatedAt() == null ? 0 :
                Math.max(0, ChronoUnit.DAYS.between(user.getCreatedAt().toLocalDate(), LocalDate.now()));
        String nickname = user.getNickname() == null || user.getNickname().isBlank()
                ? NICKNAME_FALLBACK : user.getNickname();
        return new AdminUserItem(
                user.getId(),
                nickname,
                user.getAvatarUrl(),
                user.getRole(),
                joinedDays,
                pointsBalance,
                agg != null ? agg.score() : 0,
                agg != null ? agg.levelName() : "新晋舞友");
    }

    private static Map<Long, Long> toMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
                row -> (Long) row[0],
                row -> (Long) row[1]));
    }
}
