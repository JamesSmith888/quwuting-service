package org.quwuting.quwutingservice.recruitment.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户侧招工列表卡片（服务端权威派生文案，前端零拼接零分支）。
 * 联系方式恒不下发（列表无 contact 字段）。
 */
public record RecruitmentListItem(
        Long id,
        Long venueId,
        String venueName,
        String venueCity,
        String venueImageUrl,
        boolean urgent,
        List<String> positionLabels,
        /** 性别限定（ANY → null，前端不渲染该行） */
        String genderText,
        /** 年龄要求（如「18-35岁」，无约束 → null） */
        String ageText,
        /** 薪资展示文案（salary_text 优先，缺省回落薪资类型 label） */
        String salaryText,
        String termLabel,
        /** 招聘人数（如「招30人」，未填 → null） */
        String headcountText,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime publishedAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime expiresAt
) {
}
