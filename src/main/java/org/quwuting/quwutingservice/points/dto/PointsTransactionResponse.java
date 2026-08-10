package org.quwuting.quwutingservice.points.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 积分流水条目（GET /points/transactions）。
 * sourceTypeDisplay 后端下发（挣取来源中文标签，唯一事实源在后端）。
 */
public record PointsTransactionResponse(
        long id,
        long delta,
        /** 挣取为正、赠送为负 */
        boolean earned,
        String sourceType,
        String sourceTypeDisplay,
        String targetType,
        Long targetId,
        String remark,
        long balanceAfter,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {}
