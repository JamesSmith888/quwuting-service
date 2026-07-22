package org.quwuting.quwutingservice.user.dto.response;

import org.quwuting.quwutingservice.user.enums.UserRole;

/**
 * 用户信息响应体。
 * 登录接口（POST /auth/login）、用户信息（GET /user/me）、资料更新（POST /user/profile）共用。
 * 产品为黄页工具（非社交），不含头像等社交属性字段。
 */
public record UserInfoResponse(
        Long id,
        String openId,
        String nickname,
        UserRole role
) {}
