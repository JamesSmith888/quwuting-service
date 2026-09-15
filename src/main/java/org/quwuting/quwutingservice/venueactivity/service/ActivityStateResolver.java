package org.quwuting.quwutingservice.venueactivity.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.venueactivity.dto.ActivityStateView;
import org.quwuting.quwutingservice.venueactivity.dto.ActivityWindow;
import org.quwuting.quwutingservice.venueactivity.entity.VenueActivity;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityState;
import org.quwuting.quwutingservice.venueactivity.service.strategy.ActivityScheduleStrategies;
import org.quwuting.quwutingservice.venueactivity.service.strategy.ActivityScheduleStrategy;
import org.quwuting.quwutingservice.venueactivity.support.ActivityWindows;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 活动当前态的**唯一派生点**（全项目仅此一处实现 {@link ActivityState} 判定）。
 * <p>
 * 为什么权威在后端而不是前端（照抄公告域 {@code AnnouncementService#isUnread}
 * 的既有纪律）：判定逻辑一旦两端各写一份必然漂移，而项目已经有跨端协议门禁的
 * 维护成本（{@code check:protocol}），不该再新增第二处需要同步的"双份真值"。
 * 前端的职责降为：按 {@code stateKey} 选样式 + 用 {@code nextChangeAt} 做减法。
 * <p>
 * 单点原则的具体含义：<b>任何地方都不许再写一遍时间比较</b>——门店详情页、
 * 门店列表页（批量打标）、管理端预览，一律调 {@link #resolve}。列表页的批量
 * 打标走 {@link #resolveAll}，与单条路径共用同一实现，杜绝"详情页说进行中、
 * 列表页说已结束"这类不一致。
 */
@Service
@RequiredArgsConstructor
public class ActivityStateResolver {

    /** 找不到下一个生效日时的搜索上限（星期掩码以 7 天为周期，7 天足够判定） */
    private static final int WEEKDAY_SEARCH_DAYS = 7;

    private final ActivityScheduleStrategies strategies;
    private final ActivityWindows activityWindows;

    public ActivityStateView resolve(VenueActivity activity, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        ActivityScheduleStrategy outer = strategies.of(activity.getOuterType());

        // ① 外层：有效期还没开始 / 已过期
        if (!outer.containsDate(activity, today)) {
            LocalDate start = activity.getStartDate();
            if (start != null && today.isBefore(start)) {
                // 预热期（如双节活动 9/25 开始，9/15 就该能看到并收藏）
                return new ActivityStateView(ActivityState.NOT_STARTED, start.atStartOfDay(), null);
            }
            // 已彻底过期：正常路径由 30s 调度强转 OFFLINE，此处仅防御兜底
            return new ActivityStateView(ActivityState.ENDED_TODAY, null, null);
        }

        // ② 内层：今天不是该活动的生效星期（如"仅每周三"）
        if (!ActivityWindows.matchesWeekday(activity.getWeekdayMask(), today)) {
            return endedToday(activity, today, outer);
        }

        List<ActivityWindow> windows = activityWindows.windowsOn(activity, today);

        // ③ 无生效窗口 = 有效期内全天生效
        if (windows.isEmpty()) {
            return new ActivityStateView(ActivityState.ACTIVE,
                    outer.expiryBoundary(activity).orElse(null), null);
        }

        LocalTime nowTime = now.toLocalTime();

        // ④ 命中某个生效时段（含跨夜）
        for (ActivityWindow window : windows) {
            if (window.contains(nowTime)) {
                return new ActivityStateView(ActivityState.ACTIVE,
                        window.endFor(today, nowTime), window.displayText());
            }
        }

        // ⑤ 今天还有未到的时段
        for (ActivityWindow window : windows) {
            if (window.open().isAfter(nowTime)) {
                return new ActivityStateView(ActivityState.UPCOMING_TODAY,
                        window.startAt(today), window.displayText());
            }
        }

        // ⑥ 今天的时段都过了
        return endedToday(activity, today, outer);
    }

    /**
     * 批量派生（门店列表页一次给几十家店打标，避免逐店重复解析策略与 JSON）。
     * 与 {@link #resolve} 共用实现，保证两个页面口径完全一致。
     */
    public java.util.Map<Long, ActivityStateView> resolveAll(List<VenueActivity> activities,
                                                            LocalDateTime now) {
        java.util.Map<Long, ActivityStateView> result = new java.util.HashMap<>();
        for (VenueActivity activity : activities) {
            result.put(activity.getId(), resolve(activity, now));
        }
        return result;
    }

    /**
     * 「今日已结束」态 —— 带上"下一个生效日的第一场"作为 {@code nextChangeAt}，
     * 让前端能说清"还有明天"而不是留一个死数字。
     * <p>
     * 用户看到"今日已结束"最需要的是<b>确认舞厅明天还开</b>，不是一句冷冰冰的结束语
     * （对应设计约定「唯一强调项之外不制造悬念」）。
     */
    private ActivityStateView endedToday(VenueActivity activity, LocalDate today,
                                        ActivityScheduleStrategy outer) {
        LocalDate next = nextActiveDate(activity, today.plusDays(1), outer);
        if (next == null) {
            return new ActivityStateView(ActivityState.ENDED_TODAY, null, null);
        }
        List<ActivityWindow> windows = activityWindows.windowsOn(activity, next);
        LocalDateTime nextAt = windows.isEmpty()
                ? next.atStartOfDay()
                : windows.get(0).startAt(next);
        return new ActivityStateView(ActivityState.ENDED_TODAY, nextAt, null);
    }

    /** 从 from 起找下一个既在有效期内、又落在星期掩码内的日期；找不到返回 null */
    private LocalDate nextActiveDate(VenueActivity activity, LocalDate from,
                                     ActivityScheduleStrategy outer) {
        LocalDate date = from;
        for (int i = 0; i < WEEKDAY_SEARCH_DAYS; i++, date = date.plusDays(1)) {
            // 有效期是连续区间：一旦某天越界，之后都不会命中
            if (!outer.containsDate(activity, date)) {
                return null;
            }
            if (ActivityWindows.matchesWeekday(activity.getWeekdayMask(), date)) {
                return date;
            }
        }
        return null;
    }
}
