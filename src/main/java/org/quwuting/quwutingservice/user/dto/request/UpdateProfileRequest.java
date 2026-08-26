package org.quwuting.quwutingservice.user.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 更新用户资料请求体（昵称 / 头像 / 年龄 / 性别 / 城市，至少提供一项）。
 * <p>
 * 各字段均选填，但至少提供一个（service 层校验兜底）——头像独立更新时前端只传
 * avatarUrl，录入年龄/性别/城市时只传对应字段。昵称判空由 @Size + service 层
 * blank 校验处理（trim 后为空视为未提供）。
 */
public record UpdateProfileRequest(
        @Size(max = 64, message = "昵称最长64个字符")
        String nickname,
        @Size(max = 500, message = "头像地址最长500个字符")
        String avatarUrl,
        /** 年龄（null = 不更新；传值覆盖，0 视为未填写） */
        Integer age,
        /** 性别（MALE / FEMALE，null = 不更新；传非空覆盖） */
        @Size(max = 16, message = "性别取值非法")
        String gender,
        /** 常驻城市（行政区划名，null = 不更新；传非空覆盖） */
        @Size(max = 64, message = "城市最长64个字符")
        String city
) {}
