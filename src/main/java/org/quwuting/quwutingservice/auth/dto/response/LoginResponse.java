package org.quwuting.quwutingservice.auth.dto.response;

import org.quwuting.quwutingservice.user.dto.response.UserInfoResponse;

/**
 * 登录取凭证响应。
 *
 * @param token     访问凭证（HS256 JWT）
 * @param expiresIn 凭证有效期（<b>秒</b>，OAuth2 {@code expires_in} 语义）。客户端据此
 *                  计算本地过期时刻并在失效前主动静默续期——禁止客户端自行解析 JWT
 *                  payload 推算（凭证编码是服务端实现细节，客户端不解读）。
 * @param user      用户信息快照
 */
public record LoginResponse(
        String token,
        long expiresIn,
        UserInfoResponse user
) {}
