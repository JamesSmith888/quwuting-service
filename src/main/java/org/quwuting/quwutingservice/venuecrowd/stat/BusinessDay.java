package org.quwuting.quwutingservice.venuecrowd.stat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 营业日（2026-10-07）：热度上报「一人一夜一票」的日期归属，<b>整个 venuecrowd 包唯一的日期判定入口</b>。
 * <p>
 * 05:00（{@link CrowdPolicy#BUSINESS_DAY_START_HOUR}）之前的时刻归属前一个营业日：
 * 23:50 与次日 00:10 是<b>同一场夜</b>，只能投一票。
 *
 * <h3>根因</h3>
 * 旧的「每日一记」键是自然日（{@code report_date = LocalDate.now()}），而营业时段跨午夜
 * （实测约 1/3 上报发生在 23:00~01:00）⇒ 同一个人同一夜可以投两票，且两行同时落进 6h 窗口被重复计票。
 * 「一夜」是业务概念，自然日是日历概念——唯一键用错了坐标系。
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>本类只接受调用方传入的时间点（纯函数），<b>不读系统时钟</b>——时钟由服务层统一取一次，
 *       保证同一次请求内各处判定一致、单测可控；</li>
 *   <li>{@code qwt_venue_crowd_reports.report_date} 仍是<b>自然日</b>（行为统计口径的日列，
 *       见 {@code UserStatsSql}），不与本类混用；营业日落在新列 {@code business_date}。</li>
 * </ul>
 */
public final class BusinessDay {

    private BusinessDay() {
    }

    /** 时间点所属的营业日。 */
    public static LocalDate of(LocalDateTime at) {
        return at.minusHours(CrowdPolicy.BUSINESS_DAY_START_HOUR).toLocalDate();
    }

    /** 营业日的起点（含）：该营业日 05:00。 */
    public static LocalDateTime startOf(LocalDate businessDate) {
        return businessDate.atTime(CrowdPolicy.BUSINESS_DAY_START_HOUR, 0);
    }

    /** 时间点是否落在「夜间」措辞区间（18:00 ~ 次日营业日分界前）；仅用于文案分流。 */
    public static boolean isNightHour(LocalDateTime at) {
        int hour = at.getHour();
        return hour >= CrowdPolicy.NIGHT_FROM_HOUR || hour < CrowdPolicy.BUSINESS_DAY_START_HOUR;
    }
}
