package org.quwuting.quwutingservice.points.dto;

import java.time.LocalDateTime;

/**
 * 履约确认响应（2026-08-27，POST /points/demands/{id}/confirm，docs/agents/23）。
 * <p>
 * confirmed = 本次是否新确认（false = 此前已确认，幂等返回）；
 * fulfilledAt = 履约确认时间（已确认后恒非空）；
 * cooperationCount = 该客人与该舞伴的履约确认总数（含本次，即「与 TA 已合作
 * N 次」——私域信号，不公开广播）。
 */
public record FulfillmentResponse(
        boolean confirmed,
        LocalDateTime fulfilledAt,
        long cooperationCount
) {}
