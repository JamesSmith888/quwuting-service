package org.quwuting.quwutingservice.venueactivity.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 活动生效时段条目（变长结构化列表 → JSON 字符串列，与
 * {@code qwt_venues.business_hours} 同一模式，见 {@code BusinessHoursEntry}）。
 * <p>
 * <b>跨夜契约（本域最容易被忽略的一处）</b>：{@code close < open} 表示结束于
 * 次日凌晨（如 23:30 - 00:30）。舞厅营业普遍开到凌晨 02:00，一旦活动窗口跨过零点，
 * 天真的 {@code !now.isBefore(open) && !now.isAfter(close)} 会**恒为假** ⇒
 * 活动永不亮。所有命中判定一律走 {@link #contains(LocalTime)}，
 * 禁止任何地方自己写时间比较。
 * <p>
 * name 可空（如"下午场"/"晚场"），仅作展示前缀；空则只呈现起止时间。
 */
public record ActivityWindow(

        String name,

        @JsonFormat(pattern = "HH:mm")
        LocalTime open,

        @JsonFormat(pattern = "HH:mm")
        LocalTime close
) {

    /** 是否跨过零点（结束于次日凌晨） */
    public boolean crossesMidnight() {
        return close.isBefore(open);
    }

    /**
     * 该时刻是否落在本时段内（含边界）。跨夜时段按"或"判定：
     * 凌晨段（>= open）与 清晨段（<= close）各自成立。
     */
    public boolean contains(LocalTime time) {
        if (time == null) {
            return false;
        }
        if (crossesMidnight()) {
            return !time.isBefore(open) || !time.isAfter(close);
        }
        return !time.isBefore(open) && !time.isAfter(close);
    }

    /** 在指定营业日上的开始时刻 */
    public LocalDateTime startAt(LocalDate date) {
        return date.atTime(open);
    }

    /**
     * 当前时刻落在本时段内时，本时段的**结束时刻**。
     * <p>
     * ⚠️ 跨夜时段不能用"startDate + 1 天"一把梭：23:30-00:30 这个窗口在
     * 00:10 时命中的是**今天凌晨**那一段，结束于今天 00:30；而在 23:45 时
     * 命中的是**今晚**那一段，结束于次日 00:30。判据 = 当前时刻是否已过 open
     * ——过了 ⇒ 命中的是前半段（结束在次日），没过 ⇒ 命中的是后半段（结束在今天）。
     * <p>
     * 这个分支只影响"还有 xx 分钟"的读数，写错不会崩、只会显示一个荒谬的倒计时，
     * 属于最难被发现的一类 bug——所以集中在这里，禁止其他位置自行推导。
     */
    public LocalDateTime endFor(LocalDate date, LocalTime now) {
        if (crossesMidnight() && now != null && !now.isBefore(open)) {
            return date.plusDays(1).atTime(close);
        }
        return date.atTime(close);
    }

    /** 展示文案（如 "13:00-13:45"，有名称时前置名称） */
    public String displayText() {
        String range = format(open) + "-" + format(close);
        if (name == null || name.isBlank()) {
            return range;
        }
        return name.trim() + " " + range;
    }

    private static String format(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }
}
