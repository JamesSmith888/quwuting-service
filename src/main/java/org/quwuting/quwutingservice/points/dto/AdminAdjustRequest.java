package org.quwuting.quwutingservice.points.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.Length;

/**
 * 管理端人工调整请求（POST /admin/points/adjust，仅 ADMIN）。
 * delta 可正可负（负 = 扣分，纠正误发/惩罚刷分；delta=0 由 Service 拒绝）；
 * reason 必填（审计追溯）。
 */
public record AdminAdjustRequest(
        @NotNull(message = "目标用户不能为空")
        Long userId,
        @NotNull(message = "调整量不能为空")
        Integer delta,
        @NotBlank(message = "调整原因必填")
        @Length(max = 200, message = "调整原因最多 200 字")
        String reason
) {}
