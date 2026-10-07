package org.quwuting.quwutingservice.venuecrowd.stat;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 营业日归属（05:00 分界）与「夜间」措辞区间。 */
class BusinessDayTest {

    private static LocalDateTime t(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute);
    }

    @Test
    void beforeTheCutoffBelongsToTheBusinessDayBefore() {
        assertEquals(LocalDate.of(2026, 10, 6), BusinessDay.of(t(7, 0, 10)), "00:10 仍是昨天那一夜");
        assertEquals(LocalDate.of(2026, 10, 6), BusinessDay.of(t(7, 4, 59)));
    }

    @Test
    void cutoffMomentStartsANewBusinessDay() {
        assertEquals(LocalDate.of(2026, 10, 7), BusinessDay.of(t(7, 5, 0)));
        assertEquals(LocalDate.of(2026, 10, 7), BusinessDay.of(t(7, 23, 59)));
    }

    @Test
    void midnightCrossingDoesNotSplitTheNight() {
        // 旧口径（自然日）：23:50 → 10-06，00:10 → 10-07，同一个人可投两票；营业日口径下是同一夜
        assertEquals(BusinessDay.of(t(6, 23, 50)), BusinessDay.of(t(7, 0, 10)));
    }

    @Test
    void startOfIsTheCutoffHourOfThatBusinessDate() {
        assertEquals(t(7, 5, 0), BusinessDay.startOf(LocalDate.of(2026, 10, 7)));
        // of 与 startOf 互为边界：startOf(d) 属于 d，startOf(d) 前一瞬属于 d-1
        LocalDateTime start = BusinessDay.startOf(LocalDate.of(2026, 10, 7));
        assertEquals(LocalDate.of(2026, 10, 7), BusinessDay.of(start));
        assertEquals(LocalDate.of(2026, 10, 6), BusinessDay.of(start.minusNanos(1)));
    }

    @Test
    void nightHourCoversEveningThroughBeforeTheCutoff() {
        assertFalse(BusinessDay.isNightHour(t(7, 12, 0)));
        assertFalse(BusinessDay.isNightHour(t(7, 17, 59)));
        assertTrue(BusinessDay.isNightHour(t(7, 18, 0)));
        assertTrue(BusinessDay.isNightHour(t(7, 23, 59)));
        assertTrue(BusinessDay.isNightHour(t(7, 0, 0)));
        assertTrue(BusinessDay.isNightHour(t(7, 4, 59)));
        assertFalse(BusinessDay.isNightHour(t(7, 5, 0)));
    }
}
