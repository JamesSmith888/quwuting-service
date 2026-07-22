package org.quwuting.quwutingservice.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.auth.dto.request.LoginRequest;
import org.quwuting.quwutingservice.auth.dto.response.LoginResponse;
import org.quwuting.quwutingservice.auth.service.AuthService;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 微信登录
     * POST /auth/login  body: {"code": "wx.login()返回的临时凭证"}
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.code()));
    }
}
