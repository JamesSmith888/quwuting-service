package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.storage.ImageContentValidator;
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
    /** 图片内容校验（2026-08-12 恶意文件防线：头像 URL 落库前做内容级校验） */
    private final ImageContentValidator imageValidator;

    /** 获取用户最新信息（GET /user/me，前端静默刷新用户态用） */
    @Transactional(readOnly = true)
    public UserInfoResponse getUserInfo(Long userId) {
        return userInfoMapper.toResponse(requireUser(userId));
    }

    /**
     * 更新用户资料（昵称 / 头像，按请求中提供的字段局部更新）。
     * nickname 与 avatarUrl 至少提供一个，否则抛 1005 参数错误。
     */
    @Transactional
    public UserInfoResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = requireUser(userId);

        String nickname = request.nickname() == null ? null : request.nickname().trim();
        String avatarUrl = request.avatarUrl() == null ? null : request.avatarUrl().trim();
        if (isBlank(nickname) && isBlank(avatarUrl)) {
            throw new BusinessException(1005, "昵称或头像至少提供一项");
        }
        if (!isBlank(nickname)) {
            user.setNickname(nickname);
        }
        if (!isBlank(avatarUrl)) {
            imageValidator.validate(avatarUrl);
            user.setAvatarUrl(avatarUrl);
        }
        userRepository.save(user);
        return userInfoMapper.toResponse(user);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private User requireUser(Long userId) {
        return userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
    }
}
