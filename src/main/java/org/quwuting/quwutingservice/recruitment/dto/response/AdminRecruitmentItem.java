package org.quwuting.quwutingservice.recruitment.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端招工条目（列表与编辑回显共用）。含联系方式真实值与获取人数（仅 ADMIN 下发）。
 */
public record AdminRecruitmentItem(
        Long id,
        Long venueId,
        String venueName,
        List<String> positionCodes,
        List<String> positionLabels,
        Integer headcount,
        String term,
        String genderLimit,
        Integer ageMin,
        Integer ageMax,
        String salaryType,
        String salaryText,
        Boolean accommodation,
        Boolean travelPaid,
        String description,
        String contactName,
        String contactPhone,
        String contactWechat,
        boolean urgent,
        String status,
        String statusLabel,
        /** PUBLISHED 但已过有效期（待续期/下架决策） */
        boolean expired,
        /** 联系方式获取人数（幂等去重后的 UV） */
        long contactFetchCount,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime publishedAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime expiresAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt
) {
}
