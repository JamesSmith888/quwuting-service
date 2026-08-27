package org.quwuting.quwutingservice.user.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 连续打卡计算单元测试（2026-08-27 用户管理增强，AdminUserStatsService#computeStreak）。
 * <p>
 * 语义：锚点 = 今天或昨天（今天未打不打断连续——昨晚打卡、今晨未打的真实用户
 * 不应归零）；与锚点不相邻 = 连续已断（0）；dates 按日期倒序且 UNIQUE 无重复。
 * 覆盖：今天锚点 / 昨天锚点 / 断档 / 空集 / 早于昨天（连续已断）/ 未来日期异常。
 */
class AdminUserStatsServiceTest {

    private final LocalDate today = LocalDate.now();

    /** 构造倒序日期列表（从 anchor 往回连续 n 天） */
    private List<LocalDate> streakFrom(LocalDate anchor, int n) {
        java.util.ArrayList<LocalDate> dates = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            dates.add(anchor.minusDays(i));
        }
        return dates;
    }

    @Test
    void streakAnchorToday() {
        // 今天打了 + 往前 4 天 = 连续 5
        assertEquals(5, AdminUserStatsService.computeStreak(streakFrom(today, 5)));
    }

    @Test
    void streakAnchorYesterdayTodayNotCheckedIn() {
        // 今天未打（锚点昨天）+ 往前 3 天 = 连续 4（今天未打不打断连续）
        assertEquals(4, AdminUserStatsService.computeStreak(streakFrom(today.minusDays(1), 4)));
    }

    @Test
    void streakBrokenBeforeYesterday() {
        // 最近打卡在昨天之前（前天）= 连续已断
        assertEquals(0, AdminUserStatsService.computeStreak(streakFrom(today.minusDays(2), 3)));
    }

    @Test
    void streakSingleDay() {
        assertEquals(1, AdminUserStatsService.computeStreak(List.of(today)));
        assertEquals(1, AdminUserStatsService.computeStreak(List.of(today.minusDays(1))));
    }

    @Test
    void streakEmpty() {
        assertEquals(0, AdminUserStatsService.computeStreak(List.of()));
    }

    @Test
    void streakGapBreaks() {
        // 今天、昨天、但前天断 → 连续 2（断点停住）
        assertEquals(2, AdminUserStatsService.computeStreak(
                List.of(today, today.minusDays(1), today.minusDays(3), today.minusDays(4))));
    }

    @Test
    void streakFutureDateIsAnomaly() {
        // 未来日期（数据异常）→ 0 防御
        assertEquals(0, AdminUserStatsService.computeStreak(List.of(today.plusDays(1))));
    }
}
