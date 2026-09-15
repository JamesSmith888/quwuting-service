package org.quwuting.quwutingservice.venueactivity.service.strategy;

import org.quwuting.quwutingservice.venueactivity.entity.VenueActivity;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityOuterSchedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 外层调度策略 —— 活动**有效期**的唯一抽象。
 * <p>
 * 这是本域唯一使用策略模式的地方（判据见 {@link ActivityOuterSchedule}）：
 * 新增一种有效期形态（每月固定日 / 仅节假日 / 仅开业当天…）= <b>加一个实现类</b>，
 * 用户端与数据库零改动。{@code ActivityScheduleStrategies} 会在启动期校验
 * "每个枚举值都有实现"，漏实现直接启动失败——好过运行期 NPE。
 * <p>
 * ⛔ 实现类**只回答时间问题**，不得触碰状态机（{@code status} 的强转归
 * {@code VenueActivityService#processScheduledTransitions}）与展示文案。
 */
public interface ActivityScheduleStrategy {

    /** 本策略对应的枚举值（注册表按它建索引） */
    ActivityOuterSchedule type();

    /**
     * 指定日期是否落在外层有效期内（闭区间）。
     * <p>
     * ⚠️ 只判**外层**。当天有没有生效时段、此刻是否命中，一律交给
     * {@link org.quwuting.quwutingservice.venueactivity.support.ActivityWindows}
     * 与 {@code ActivityStateResolver}——两层职责不得互相渗透。
     */
    boolean containsDate(VenueActivity activity, LocalDate date);

    /**
     * 有效的自然终点（用于 ACTIVE 态的 {@code nextChangeAt} 兜底：无生效窗口时
     * 活动会一直亮到有效期结束）。长期有效返回 {@link Optional#empty()}。
     */
    Optional<LocalDateTime> expiryBoundary(VenueActivity activity);
}
