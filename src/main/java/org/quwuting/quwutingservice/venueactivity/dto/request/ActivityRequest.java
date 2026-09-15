package org.quwuting.quwutingservice.venueactivity.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venueactivity.dto.ActivityWindow;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityBenefitKind;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityOuterSchedule;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityRedemptionMode;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 活动创建 / 更新请求（admin 专用，与门店动态域同一条纪律：**活动内容一律平台
 * admin 直发，不给门店自主发布权**）。
 * <p>
 * 为什么这一条是硬约束而不是偏好：门店老板发的是「带优惠承诺的经营信息」——
 * 兑现不了投诉落在小程序主体（我们只是个人主体，更没有承担这种责任的空间）；
 * 且舞厅行业宣传语擦边概率高，平台不能原样放行。老板把海报发给我们、
 * 我们审核 + 结构化后录入，是唯一可接受的通道。
 *
 * @param venueId        所属门店（更新时忽略，以路径 / 实体为准）
 * @param badgeLabel     列表页短标签（≤4 字）；留空则取 benefitKind 的缺省值
 * @param weekdays       生效星期（ISO 1=周一 … 7=周日）；null/空 = 每天
 * @param windows        生效时段；null/空 = 全时段有效
 * @param publish        true = 保存并直接发布；false = 存草稿
 */
public record ActivityRequest(

        Long venueId,

        @NotBlank(message = "活动名称不能为空")
        @Size(max = 60, message = "活动名称最长60个字符")
        String title,

        @NotNull(message = "权益类别不能为空")
        ActivityBenefitKind benefitKind,

        @Size(max = 8, message = "短标签最长8个字符")
        String badgeLabel,

        @Size(max = 500, message = "权益说明最长500个字符")
        String benefitSummary,

        @NotNull(message = "核销方式不能为空")
        ActivityRedemptionMode redemptionMode,

        @Size(max = 200, message = "核销提示最长200个字符")
        String redemptionHint,

        @Size(max = 200, message = "平台专属加项最长200个字符")
        String platformAddon,

        @NotNull(message = "有效期类型不能为空")
        ActivityOuterSchedule outerType,

        LocalDate startDate,

        LocalDate endDate,

        Set<Integer> weekdays,

        @Valid
        List<ActivityWindow> windows,

        Integer sortWeight,

        Boolean publish
) {
}
