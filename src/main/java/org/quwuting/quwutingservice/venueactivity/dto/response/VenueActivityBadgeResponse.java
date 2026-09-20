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
 * <b>列表页那条通知行怎么读（2026-09-16 二期口径；chip 已废除）</b>：前端的
 * {@code activityNoticeText} 统一渲染「{@code badgeLabel} · 状态或时间」——**两态同款**，
 * 差异全在文字：{@code ACTIVE} → "买一送一 · 进行中"；其余三态 → "买一送一 · 13:00 起"。
 * 所以 {@code badgeLabel} 与 {@code state}/{@code nextChangeAt} 必须**同时下发**：
 * 前者回答"能拿什么"、后者回答"什么时候来"（旧 chip 只有一格宽，只能二选一）。
 * 时间文案仍由前端按 {@code nextChangeAt} 做一次减法，派生权威在后端一处。
 * <p>
 * <b>2026-09-20：同一 venueId 下发的是一条数组</b>（按展示优先级排好，索引 0 最先轮播）
 * ——那一行已支持多条轮播，所以"一个 chip 的位置"这个旧前提不再成立。
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
