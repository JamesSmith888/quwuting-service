package org.quwuting.quwutingservice.points.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;

/**
 * 设置/更新/清除积分门槛请求（POST /points/gates，2026-08-14 公共模块）。
 * <p>
 * 语义：cost &gt; 0 = 设置/更新门槛（upsert，同目标幂等覆盖）；
 * cost = 0 = 清除门槛（软删行——"免费查看"）。cost 上限由
 * {@code app.points.gate.max-cost}（PointsProperties）校验（>0 且 ≤ 上限）。
 * <p>
 * 权限：目标属主（舞伴本人 createdBy）或平台管理员——设置门槛是"管理自己内容"
 * 的操作，与 dancer canManage 语义一致（普通用户不可为他人内容设门槛）。
 */
public record UpsertGateRequest(
        @NotNull(message = "门槛目标类型不能为空")
        PointsGateTargetType targetType,

        @NotNull(message = "门槛目标不能为空")
        Long targetId,

        /** 解锁所需积分：0 = 清除门槛（免费）；>0 = 设置门槛（≤ max-cost 配置上限） */
        @Min(value = 0, message = "门槛积分不能为负数")
        @Max(value = 9999, message = "门槛积分超出范围")
        int cost
) {}
