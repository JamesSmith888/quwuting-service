package org.quwuting.quwutingservice.venueactivity.service.strategy;

import org.quwuting.quwutingservice.venueactivity.entity.VenueActivity;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityOuterSchedule;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * 指定日期区间（闭区间，{@code start_date ~ end_date}）。
 * <p>
 * 「单日」是本策略在 {@code start = end} 时的退化情形，不是独立策略——
 * 判据：枚举值必须对应不同<b>算法</b>，不能只对应不同<b>输入方式</b>。
 * <p>
 * 边界口径（两处都容易写错，且错了都是"活动莫名失效"这类静默故障）：
 * <ul>
 *   <li>{@code end_date} <b>当天仍然有效</b>——所以自动下线的判据是
 *       {@code endDate < today} 而不是 {@code <=}；</li>
 *   <li>缺失日期视为"不限定"而非"无效"：运营只填了开始日期就应该是
 *       "从该日起长期"，而不是活动一夜之间消失（配置不完整时取<b>宽松</b>侧，
 *       与计费域"默认姿态取少算"同一取向：宁可多展示一次，不可静默丢掉）。</li>
 * </ul>
 */
@Component
public class DateRangeStrategy implements ActivityScheduleStrategy {

    @Override
    public ActivityOuterSchedule type() {
        return ActivityOuterSchedule.DATE_RANGE;
    }

    @Override
    public boolean containsDate(VenueActivity activity, LocalDate date) {
        LocalDate start = activity.getStartDate();
        LocalDate end = activity.getEndDate();
        if (start != null && date.isBefore(start)) {
            return false;
        }
        if (end != null && date.isAfter(end)) {
            return false;
        }
        return true;
    }

    @Override
    public Optional<LocalDateTime> expiryBoundary(VenueActivity activity) {
        LocalDate end = activity.getEndDate();
        if (end == null) {
            return Optional.empty();
        }
        return Optional.of(end.atTime(LocalTime.MAX));
    }
}
