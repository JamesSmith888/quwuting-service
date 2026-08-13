package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
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
 * （昵称/头像/角色/加入时间）与 TA 创建的 NORMAL 舞伴；绝不触碰 openId、
 * 积分余额、流水等私人数据。PENDING/HIDDEN 舞伴天然排除（Repository 过滤）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPublicService {

    /** 无昵称用户的展示占位（与无头像时的首字符占位不同：名字位需真实可读文案） */
    private static final String NICKNAME_FALLBACK = "舞友";

    private final UserRepository userRepository;
    private final DancerRepository dancerRepository;

    /** 用户公开主页（公开只读；用户不存在/已软删 → 1004） */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Long userId) {
        User user = userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
        List<UserDancerResponse> dancers = dancerRepository.findPublicByCreatedBy(userId).stream()
                .map(this::toDancerResponse)
                .toList();
        LocalDateTime createdAt = user.getCreatedAt();
        long joinedDays = createdAt == null ? 0 :
                Math.max(0, ChronoUnit.DAYS.between(createdAt.toLocalDate(), LocalDate.now()));
        String nickname = user.getNickname() == null || user.getNickname().isBlank()
                ? NICKNAME_FALLBACK : user.getNickname();
        return new UserProfileResponse(
                user.getId(), nickname, user.getAvatarUrl(), user.getRole().name(),
                createdAt, joinedDays, dancers);
    }

    private UserDancerResponse toDancerResponse(Dancer d) {
        return new UserDancerResponse(d.getId(), d.getNickname(), d.getAvatarUrl(), d.getCity());
    }
}
