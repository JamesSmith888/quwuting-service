package org.quwuting.quwutingservice.user.dto.response;

import org.quwuting.quwutingservice.user.enums.UserRole;

/**
 * 用户信息响应体。
 * 登录接口（POST /auth/login）、用户信息（GET /user/me）、资料更新（POST /user/profile）共用。
 * avatarUrl 为用户主动上传的头像（chooseAvatar → Supabase 直传），未设置时为 null。
 */
public record UserInfoResponse(
        Long id,
        String openId,
        String nickname,
        String avatarUrl,
        UserRole role
) {}
