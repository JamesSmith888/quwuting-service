package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.user.dto.request.UpdateProfileRequest;
import org.quwuting.quwutingservice.user.dto.response.UserInfoResponse;
import org.quwuting.quwutingservice.user.mapper.UserInfoMapper;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserInfoMapper userInfoMapper;

    /** 获取用户最新信息（GET /user/me，前端静默刷新用户态用） */
    @Transactional(readOnly = true)
    public UserInfoResponse getUserInfo(Long userId) {
        return userInfoMapper.toResponse(requireUser(userId));
    }

    /** 更新用户昵称（nickname 必填，请求体已校验） */
    @Transactional
    public UserInfoResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = requireUser(userId);
        user.setNickname(request.nickname().trim());
        userRepository.save(user);
        return userInfoMapper.toResponse(user);
    }

    private User requireUser(Long userId) {
        return userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
    }
}
