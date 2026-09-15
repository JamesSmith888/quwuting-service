package org.quwuting.quwutingservice.venueactivity.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityBenefitKind;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityRedemptionMode;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityState;

import java.time.LocalDateTime;

/**
 * 用户端活动响应体（门店详情页活动卡 + 门店列表页活动标记共用同一条数据）。
 * <p>
 * 设计要点（都是为了让前端**零业务分支**）：
 * <ul>
 *   <li>{@code state} / {@code stateDisplay} 由后端派生好，前端不拿 windows 重算；</li>
 *   <li>{@code nextChangeAt} 是绝对时刻，前端"还有 xx 分钟"= 一次减法；</li>
 *   <li>{@code windowsText} / {@code validityText} 把"13:00-13:45 · 18:00-19:00"、
 *       "9月25日-10月8日"这类拼装收在服务端，避免两端各写一套格式化；</li>
 *   <li>{@code badgeLabel} 已按 benefitKind 缺省值兜底，列表页 chip 直接渲染，
 *       前端不需要知道"空则取缺省"这条规则。</li>
 * </ul>
 * 未登录也能取（门店详情页无需登录即可浏览）——此时 {@code checkedInToday}
 * 恒为 false、{@code checkinAvailable} 恒为 false，前端据此隐藏打卡动作即可，
 * **不弹登录**：扫码落地即要求登录是"到店后进小程序"链路里最该避免的一步
 * （登录成本必须推迟到用户已经拿到价值之后）。
 *
 * @param checkinAvailable 当前是否可打卡（需登录）
 * @param checkedInToday   本人今日是否已打卡
 */
public record VenueActivityResponse(
        Long id,
        Long venueId,
        String title,
        ActivityBenefitKind benefitKind,
        String benefitKindDisplay,
        String badgeLabel,
        String benefitSummary,
        ActivityRedemptionMode redemptionMode,
        String redemptionModeDisplay,
        String redemptionHint,
        String platformAddon,
        String windowsText,
        String validityText,
        ActivityState state,
        String stateDisplay,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime nextChangeAt,
        boolean checkinAvailable,
        boolean checkedInToday
) {
}
