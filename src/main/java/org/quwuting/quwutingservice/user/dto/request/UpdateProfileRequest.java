package org.quwuting.quwutingservice.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 更新用户资料请求体。
 * 产品定位为黄页工具（非社交），用户资料仅含昵称，无头像等社交属性字段。
 */
public record UpdateProfileRequest(
        @NotBlank(message = "昵称不能为空")
        @Size(max = 64, message = "昵称最长64个字符")
        String nickname
) {}
