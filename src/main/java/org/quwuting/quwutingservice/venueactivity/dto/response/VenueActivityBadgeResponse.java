package org.quwuting.quwutingservice.venueactivity.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityState;

import java.time.LocalDateTime;

/**
 * 门店**列表页**活动标记（口径见小程序仓 `quwuting/docs/agents/49-venue-activities.md §5.2`
 * ——即本仓文档开头指向的那份展示层权威；⚠️ 本仓同编号文档的 §5 是状态机，不要引串）。
 * <p>
 * <b>下发条件 = 活动在当前日期范围内</b>（有效期覆盖今天，或今天尚未结束），
 * 而不是"此刻正好命中时段"。理由与门店营业状态徽标完全同源：
 * 「营业中 / 未到营业时间→X HH:mm 开门」——**状态要在，只是语气降级**。
 * 只在命中时才出现，会让列表页在一天中的大部分时间里对"这家店有活动"完全失声，
 * 而用户真正需要的是"现在没有的话，什么时候有"。
 * <p>
 * <b>两种语气的分工（一个状态只回答一个问题）</b>：
 * <ul>
 *   <li>{@code ACTIVE} → 前端渲染 {@code badgeLabel}（"买一送一"）—— 回答<b>能拿到什么</b>；</li>
 *   <li>其余三态 → 前端按 {@code nextChangeAt} 渲染时间（"13:00 起" / "明日 13:00" /
 *       "9月25日 起"）—— 回答<b>什么时候来</b>。</li>
 * </ul>
 * 前端不重算时间文案，只做 {@code nextChangeAt} 的一次减法（派生权威在后端，同域内一致）。
 *
 * @param state         当前态（NOT_STARTED / UPCOMING_TODAY / ACTIVE / ENDED_TODAY）
 * @param stateDisplay  状态展示名（无障碍朗读与兜底文案）
 * @param nextChangeAt  下一次状态变化的绝对时刻；null = 不会自然变化（前端回退 stateDisplay）
 */
public record VenueActivityBadgeResponse(
        Long activityId,
        String badgeLabel,
        ActivityState state,
        String stateDisplay,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime nextChangeAt
) {

    public boolean isActive() {
        return state == ActivityState.ACTIVE;
    }
}
