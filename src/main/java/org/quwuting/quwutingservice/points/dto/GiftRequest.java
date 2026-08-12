package org.quwuting.quwutingservice.points.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;

/**
 * 赠送请求（POST /points/gift，2026-08-12 礼物化）。
 * <p>
 * 载荷从 amount（积分数量）改为 giftCode（礼物 code）——一次赠送 = 一个礼物；
 * 价格由后端 {@code GiftCatalog} 权威校验（前端镜像仅展示，防"展示价 ≠ 实扣价"）。
 * 各上限（单次/每日/单目标每日）仍按礼物价格折算积分价值校验（PointsProperties）。
 */
public record GiftRequest(
        @NotNull(message = "赠送目标类型不能为空")
        PointsTargetType targetType,
        @NotNull(message = "赠送目标不能为空")
        Long targetId,
        @NotBlank(message = "请选择要赠送的礼物")
        String giftCode
) {}
