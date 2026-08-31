package org.quwuting.quwutingservice.webauth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 管理后台账号密码登录请求（POST /web-auth/password-login）。
 * <p>
 * 账号/密码由服务器配置 web-auth.username / web-auth.password 提供
 * （密码走环境变量 WEB_ADMIN_PASSWORD，仓库零敏感信息），
 * 不依赖用户表——扫码登录之外的兜底通道。
 */
public record PasswordLoginRequest(
        @NotBlank(message = "账号不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password
) {}
