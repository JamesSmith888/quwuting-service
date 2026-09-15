package org.quwuting.quwutingservice.venueactivity.dto;

import org.quwuting.quwutingservice.venueactivity.enums.ActivityState;

import java.time.LocalDateTime;

/**
 * 活动当前态派生结果（不落库，随查询即时算出）。
 *
 * @param state            当前态（前端只按它做展示分支，禁自行重算）
 * @param nextChangeAt     **下一次状态变化**的绝对时刻——前端的"还有 25 分钟 /
 *                         今日 18:00 开始"就是拿它与本地时间做一次减法。
 *                         用绝对时刻而非"剩余秒数"：剩余秒数依赖服务端"此刻"的
 *                         缓存新鲜度，一旦响应被缓存就不再正确；绝对时刻不会。
 *                         为 null = 不会自然变化（长期有效且无窗口）。
 * @param activeWindowText 命中的那个时段文案（如 "13:00-13:45"）；未命中为 null
 */
public record ActivityStateView(
        ActivityState state,
        LocalDateTime nextChangeAt,
        String activeWindowText
) {

    public boolean isActive() {
        return state == ActivityState.ACTIVE;
    }
}
