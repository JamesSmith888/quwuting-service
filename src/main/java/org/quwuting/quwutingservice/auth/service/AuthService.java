package org.quwuting.quwutingservice.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.auth.dto.response.LoginResponse;
import org.quwuting.quwutingservice.security.JwtUtil;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.mapper.UserInfoMapper;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final WechatService wechatService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final UserInfoMapper userInfoMapper;

    /**
     * 微信登录：code → openid → 查找/创建用户 → 签发 JWT。
     * 首次登录自动注册，角色默认 USER（超管由数据库手动设置）。
     * 昵称不在登录链路获取（微信平台策略：jscode2session 仅返回 openid），
     * 由用户在"我"页面通过昵称编辑入口主动提交（POST /user/profile）。
     */
    @Transactional
    public LoginResponse login(String code) {
        String openId = wechatService.code2Session(code);

        User user = userRepository.findByOpenIdAndDeletedFalse(openId)
                .orElseGet(() -> createUser(openId));

        String token = jwtUtil.generateToken(user.getId(), user.getRole());
        return new LoginResponse(token, userInfoMapper.toResponse(user));
    }

    private User createUser(String openId) {
        User user = new User();
        user.setOpenId(openId);
        user.setNickname("微信用户");
        log.info("New user registered: openId={}", openId);
        return userRepository.save(user);
    }
}
