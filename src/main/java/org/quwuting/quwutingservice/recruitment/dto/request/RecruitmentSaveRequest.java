package org.quwuting.quwutingservice.recruitment.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.recruitment.enums.RecruitGenderLimit;
import org.quwuting.quwutingservice.recruitment.enums.RecruitSalaryType;
import org.quwuting.quwutingservice.recruitment.enums.RecruitTerm;

import java.util.List;

/**
 * 招工创建 / 编辑请求（编辑 = 全量覆盖，POST /admin/recruitments/{id}/update）。
 * <p>
 * positions 传枚举 name 列表（受控职位字典）；expiresInDays 仅创建时生效
 * （默认 30 天，编辑后延期走 /renew 一键续期）；联系方式发布时要求电话/微信至少其一。
 */
public record RecruitmentSaveRequest(
        @NotNull Long venueId,
        @NotEmpty @Size(max = 8) List<@NotBlank @Size(max = 32) String> positions,
        @Min(1) @Max(999) Integer headcount,
        RecruitTerm term,
        RecruitGenderLimit genderLimit,
        @Min(16) @Max(70) Integer ageMin,
        @Min(16) @Max(70) Integer ageMax,
        RecruitSalaryType salaryType,
        @Size(max = 64) String salaryText,
        Boolean accommodation,
        Boolean travelPaid,
        @NotBlank @Size(max = 1000) String description,
        @Size(max = 32) String contactName,
        @Size(max = 20) String contactPhone,
        @Size(max = 64) String contactWechat,
        Boolean urgent,
        @Min(1) @Max(365) Integer expiresInDays
) {
}
