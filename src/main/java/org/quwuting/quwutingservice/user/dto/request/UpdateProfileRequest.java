package org.quwuting.quwutingservice.user.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 更新用户资料请求体（昵称 / 头像二选一或同传）。
 * <p>
 * nickname 与 avatarUrl 均选填，但二者至少提供一个（service 层校验兜底）——
 * 头像独立更新时前端只传 avatarUrl，不要求昵称。昵称判空由 @Size + service 层
 * blank 校验处理（trim 后为空视为未提供）。
 */
public record UpdateProfileRequest(
        @Size(max = 64, message = "昵称最长64个字符")
        String nickname,
        @Size(max = 500, message = "头像地址最长500个字符")
        String avatarUrl
) {}
