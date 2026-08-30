package org.quwuting.quwutingservice.recruitment.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户侧招工详情。联系方式真实值恒不下发（与舞伴联系方式同一纪律）——
 * hasContact 仅驱动「获取联系方式」入口渲染，真实值经
 * POST /recruitments/{id}/contact 登录后实时返回。
 */
public record RecruitmentDetail(
        Long id,
        Long venueId,
        String venueName,
        String venueCity,
        String venueDistrict,
        String venueAddress,
        String venueImageUrl,
        boolean urgent,
        List<String> positionLabels,
        String genderText,
        String ageText,
        String salaryText,
        String termLabel,
        String headcountText,
        /** 包住宿三态（null = 未说明，前端不渲染） */
        String accommodationText,
        /** 报销路费三态（null = 未说明，前端不渲染） */
        String travelText,
        String description,
        boolean hasContact,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime publishedAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime expiresAt
) {
}
