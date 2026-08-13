package org.quwuting.quwutingservice.user.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.user.dto.response.UserProfileResponse;
import org.quwuting.quwutingservice.user.service.UserPublicService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户公开主页接口（2026-08-12 礼物赠送者弹层 → 用户详情页）。
 * <p>
 * GET /users/{id} — 任意用户的公开主页（昵称/头像/角色/加入时间 + TA 创建的公开舞伴）。
 * 公开只读、无需登录（与舞伴详情页同可见性口径）；隐私字段（openId/余额等）绝不下发。
 * 注意：/users/me/... 为用户级资源前缀（MyDancerController），本控制器只匹配
 * 单段路径 /users/{id}，两者无冲突。
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserPublicController {

    private final UserPublicService userPublicService;

    /** 用户公开主页（用户不存在/已软删 → 1004） */
    @GetMapping("/{id}")
    public ApiResponse<UserProfileResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(userPublicService.getProfile(id));
    }
}
