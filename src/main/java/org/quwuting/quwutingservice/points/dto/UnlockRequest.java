package org.quwuting.quwutingservice.points.dto;

import jakarta.validation.constraints.NotNull;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;

/**
 * 积分解锁请求（POST /points/unlock，2026-08-14）。
 * <p>
 * 幂等语义：已解锁（存在解锁记录）直接返回 unlocked=true + 内容，<b>不重复扣费</b>
 * （一人一目标只扣一次费，UNIQUE 兜底并发，见 PointsUnlock）。
 * 校验链（服务层）：门槛存在（cost>0）→ 目标对当前用户可见 → 余额足够 →
 * 原子扣减 → 写解锁流水（source_type=UNLOCK，单向燃烧）→ 写解锁记录。
 */
public record UnlockRequest(
        @NotNull(message = "解锁目标类型不能为空")
        PointsGateTargetType targetType,

        @NotNull(message = "解锁目标不能为空")
        Long targetId
) {}
