package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.points.dto.ContributionBrief;
import org.quwuting.quwutingservice.points.repository.PointsAccountRepository;
import org.quwuting.quwutingservice.points.service.ContributionService;
import org.quwuting.quwutingservice.user.dto.response.UserDancerResponse;
import org.quwuting.quwutingservice.user.dto.response.UserProfileResponse;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 用户公开主页服务（2026-08-12：礼物赠送者 → 用户详情页）。
 * <p>
 * 隐私边界（与舞伴域同精神）：只聚合**公开可展示**的信息——用户资料字段
 * （昵称/头像/角色/加入时间/积分余额）与 TA 创建的 NORMAL 舞伴；绝不触碰
 * openId、流水明细等私人数据。PENDING/HIDDEN 舞伴天然排除（Repository 过滤）。
 * <p>
 * 2026-08-26 积分余额：积分 = 平台内虚拟社区贡献值（非实名身份信息），仅经
 * 用户主动分享邀约的自愿展示场景下发（邀约落地页访客信息卡），不面向公众；
 * 若提审再次驳回 → 移除字段与前端展示即可（见 UserProfileResponse 注释）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPublicService {

    /** 无昵称用户的展示占位（与无头像时的首字符占位不同：名字位需真实可读文案） */
    private static final String NICKNAME_FALLBACK = "舞友";

    private final UserRepository userRepository;
    private final DancerRepository dancerRepository;
    private final PointsAccountRepository pointsAccountRepository;
    private final ContributionService contributionService;

    /** 用户公开主页（公开只读；用户不存在/已软删 → 1004） */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Long userId) {
        User user = userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
        List<UserDancerResponse> dancers = dancerRepository.findPublicByCreatedBy(userId).stream()
                .map(this::toDancerResponse)
                .toList();
        // 积分余额 = 账户读写快照（无账户行 = 从未参与积分活动，恒 0，见 PointsAccount 注释）
        long pointsBalance = pointsAccountRepository.findByUserId(userId)
                .map(a -> a.getBalance())
                .orElse(0L);
        LocalDateTime createdAt = user.getCreatedAt();
        long joinedDays = createdAt == null ? 0 :
                Math.max(0, ChronoUnit.DAYS.between(createdAt.toLocalDate(), LocalDate.now()));
        String nickname = user.getNickname() == null || user.getNickname().isBlank()
                ? NICKNAME_FALLBACK : user.getNickname();
        // 贡献档案摘要（2026-08-27，docs/agents/23：自愿分享通道下发——用户主动
        // 分享邀约 = 默示授权向接收方舞伴展示社区共建行为记录）
        ContributionBrief contribution = contributionService.briefFor(userId);
        return new UserProfileResponse(
                user.getId(), nickname, user.getAvatarUrl(), user.getRole().name(),
                createdAt, joinedDays, pointsBalance, dancers,
                user.getAge(), user.getGender(), user.getCity(), contribution);
    }

    private UserDancerResponse toDancerResponse(Dancer d) {
        return new UserDancerResponse(d.getId(), d.getNickname(), d.getAvatarUrl(), d.getCity());
    }
}
