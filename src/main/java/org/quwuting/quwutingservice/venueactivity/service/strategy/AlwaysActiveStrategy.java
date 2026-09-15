package org.quwuting.quwutingservice.venueactivity.service.strategy;

import org.quwuting.quwutingservice.venueactivity.entity.VenueActivity;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityOuterSchedule;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 长期有效：无起止日期，永不自然结束（只能运营手动下线）。
 * <p>
 * 不参与 30s 到期的自动下线（仓库层按 outerType 排除）。
 */
@Component
public class AlwaysActiveStrategy implements ActivityScheduleStrategy {

    @Override
    public ActivityOuterSchedule type() {
        return ActivityOuterSchedule.ALWAYS;
    }

    @Override
    public boolean containsDate(VenueActivity activity, LocalDate date) {
        return true;
    }

    @Override
    public Optional<LocalDateTime> expiryBoundary(VenueActivity activity) {
        return Optional.empty();
    }
}
