package org.quwuting.quwutingservice.webauth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.auth.dto.response.LoginResponse;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.webauth.dto.request.PasswordLoginRequest;
import org.quwuting.quwutingservice.webauth.dto.response.CreateSessionResponse;
import org.quwuting.quwutingservice.webauth.dto.response.PollSessionResponse;
import org.quwuting.quwutingservice.webauth.service.WebAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 管理后台登录接口（2026-08-31，独立前端项目 quwuting-admin-web 使用）。
 * <p>
 * 双通道登录：
 * <ul>
 *   <li>POST /web-auth/sessions                     — 创建扫码会话（返回小程序码）</li>
 *   <li>GET  /web-auth/sessions/{sessionId}         — 网页轮询（CONFIRMED 时一次性取 token）</li>
 *   <li>POST /web-auth/sessions/{sessionId}/confirm — 小程序内确认（仅 ADMIN，Bearer）</li>
 *   <li>POST /web-auth/sessions/{sessionId}/reject  — 小程序内拒绝（Bearer）</li>
 *   <li>POST /web-auth/password-login               — 账号密码兜底登录</li>
 * </ul>
 * 拿到 token 后与小程序共用 JWT 体系（Authorization: Bearer），
 * /admin/** 接口无需任何改造即可被 Web 后台复用。
 */
@RestController
@RequestMapping("/web-auth")
@RequiredArgsConstructor
public class WebAuthController {

    private final WebAuthService webAuthService;

    /** 创建扫码登录会话（无需登录；返回小程序码 + 会话 ID） */
    @PostMapping("/sessions")
    public ApiResponse<CreateSessionResponse> createSession() {
        return ApiResponse.ok(webAuthService.createSession());
    }

    /** 网页轮询会话状态（无需登录；CONFIRMED 时返回一次性 token） */
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<PollSessionResponse> poll(@PathVariable String sessionId) {
        return ApiResponse.ok(webAuthService.poll(sessionId));
    }

    /** 小程序内确认登录（仅平台管理员） */
    @PostMapping("/sessions/{sessionId}/confirm")
    public ApiResponse<Void> confirm(@PathVariable String sessionId) {
        webAuthService.confirm(sessionId);
        return ApiResponse.ok(null);
    }

    /** 小程序内拒绝登录 */
    @PostMapping("/sessions/{sessionId}/reject")
    public ApiResponse<Void> reject(@PathVariable String sessionId) {
        webAuthService.reject(sessionId);
        return ApiResponse.ok(null);
    }

    /** 账号密码兜底登录 */
    @PostMapping("/password-login")
    public ApiResponse<LoginResponse> passwordLogin(@Valid @RequestBody PasswordLoginRequest request) {
        return ApiResponse.ok(webAuthService.passwordLogin(request.username(), request.password()));
    }
}
