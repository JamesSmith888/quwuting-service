package org.quwuting.quwutingservice.points.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 积分流水条目（GET /points/transactions）。
 * sourceTypeDisplay 后端下发（挣取来源中文标签，唯一事实源在后端）。
 * giftName 仅赠送流水非空（2026-08-12 礼物化：前端渲染"赠送了小熊"零本地映射）。
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
        /** 所赠礼物 code（仅赠送流水非空；GiftCatalog 枚举名） */
        String giftCode,
        /** 所赠礼物中文名（仅赠送流水非空；后端唯一事实源下发） */
        String giftName,
        String remark,
        long balanceAfter,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {}
