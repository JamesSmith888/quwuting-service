package org.quwuting.quwutingservice.venueactivity.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venueactivity.dto.ActivityWindow;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityBenefitKind;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityOuterSchedule;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityRedemptionMode;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端活动响应体（列表 + 编辑回显共用）。
 * <p>
 * 比用户端多三样东西，都是为了"运营看得懂、能决策"：
 * <ul>
 *   <li>{@code status} —— 生命周期态（草稿/已发布/已下线），与用户端派生态
 *       {@code state} 是**两个不同的问题**（前者是我们的编排状态，后者是此刻对
 *       用户呈现什么），列表页并排展示，别混为一谈；</li>
 *   <li>{@code venueName} —— 活动是多店并存的数据，列表不带店名无法审阅；</li>
 *   <li>{@code checkinCountToday / checkinCountTotal} —— <b>平台侧唯一自有的
 *       归因信源</b>。这是与门店对账、判断"这个合作到底有没有用"的依据，
 *       也是运营判断该不该续期的唯一硬数字；</li>
 *   <li>{@code shareCount / openCount}（2026-09-16，V28，docs/agents/49-venue-activities.md
 *       §8）—— 活动的传播链：SHARE = 分享意图数，OPEN = 分享卡片被点开数。
 *       与打卡数合起来构成完整漏斗：<b>传播 → 打开 → 到店</b>。三个数字的**差值**
 *       才是信息：传了没人看（文案/卡片图无效）、看了没到店（权益不够或时段不匹配）。</li>
 * </ul>
 * <p>
 * ⚠️ 口径提醒：分享数与打开数都取自 {@code qwt_venue_shares}（**分析型事件日志**），
 * 受 60s 频控影响、且小程序无"分享成功"回调（SHARE 按"分享意图"记录）——
 * 它们是**趋势量级**，不是精确计数。拿去跟门店讲的时候要讲趋势（"这周比上周多"），
 * 不要讲成承诺值（"我给你带了 37 个人"）。
 */
public record AdminVenueActivityResponse(
        Long id,
        Long venueId,
        String venueName,
        String title,
        ActivityBenefitKind benefitKind,
        String benefitKindDisplay,
        String badgeLabel,
        String benefitSummary,
        ActivityRedemptionMode redemptionMode,
        String redemptionModeDisplay,
        String redemptionHint,
        String platformAddon,
        ActivityOuterSchedule outerType,
        String outerTypeDisplay,
        LocalDate startDate,
        LocalDate endDate,
        List<Integer> weekdays,
        List<ActivityWindow> windows,
        ActivityStatus status,
        String statusDisplay,
        int sortWeight,
        long checkinCountToday,
        long checkinCountTotal,
        long shareCount,
        long openCount,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt
) {
}
