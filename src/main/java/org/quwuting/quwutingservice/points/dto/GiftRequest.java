package org.quwuting.quwutingservice.points.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;

/**
 * 赠送请求（POST /points/gift）。
 * amount 范围 [1, maxPerGift] 由 Service 按配置校验（DTO 仅做基础非负守卫，
 * 上限值来自 PointsProperties——配置唯一事实源，禁止在 DTO/Service 硬编码）。
 */
public record GiftRequest(
        @NotNull(message = "赠送目标类型不能为空")
        PointsTargetType targetType,
        @NotNull(message = "赠送目标不能为空")
        Long targetId,
        @Min(value = 1, message = "赠送数量至少 1 分")
        Integer amount
) {}
