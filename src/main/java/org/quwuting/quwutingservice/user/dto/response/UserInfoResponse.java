package org.quwuting.quwutingservice.user.dto.response;

import org.quwuting.quwutingservice.user.enums.UserRole;

/**
 * 用户信息响应体。
 * 登录接口（POST /auth/login）、用户信息（GET /user/me）、资料更新（POST /user/profile）共用。
 * avatarUrl 为用户主动上传的头像（chooseAvatar → Supabase 直传），未设置时为 null。
 * age / gender / city 为用户自主录入的资料字段（null = 未填写），经自愿分享通道
 * （GET /users/{id}）下发给舞伴，不面向公众广播。
 */
public record UserInfoResponse(
        Long id,
        String openId,
        String nickname,
        String avatarUrl,
        UserRole role,
        /** 年龄（null = 未填写） */
        Integer age,
        /** 性别（MALE / FEMALE，null = 未声明） */
        String gender,
        /** 常驻城市（行政区划名，null = 未填写） */
        String city
) {}
