package org.quwuting.quwutingservice.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.dto.request.UpdateProfileRequest;
import org.quwuting.quwutingservice.user.dto.response.UserInfoResponse;
import org.quwuting.quwutingservice.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（均需登录）。
 * <p>
 * GET  /user/me      — 获取当前用户最新信息（前端静默刷新用户态的唯一通道）
 * POST /user/profile — 更新昵称
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 获取当前登录用户的最新信息。
     * GET /user/me
     * <p>
     * 用途：客户端缓存的用户信息（角色、昵称）可能过期（如管理员在数据库中调整角色），
     * 前端在页面可见时静默调用本接口同步最新状态，保证权限 UI 与服务端一致。
     */
    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> getCurrentUser() {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(userService.getUserInfo(userId));
    }

    /**
     * 更新当前用户昵称。
     * POST /user/profile
     */
    @PostMapping("/profile")
    public ApiResponse<UserInfoResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(userService.updateProfile(userId, request));
    }
}
