package org.quwuting.quwutingservice.dancer.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 舞伴统计「需求热度」下钻行。
 *
 * <p>明细仅供该舞伴资料管理者或平台管理员查看。用户昵称和头像仅作与「解锁信息」
 * 同款列表展示；不返回联系方式、openId 或任何可用于联系用户的敏感字段。
 */
public record DancerDemandRecord(
        Long id,
         Long userId,
         String nickname,
         String avatarUrl,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
        String message,
        String status,
        String statusText
) {
}