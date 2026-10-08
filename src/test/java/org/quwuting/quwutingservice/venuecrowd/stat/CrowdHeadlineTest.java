package org.quwuting.quwutingservice.venuecrowd.stat;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 折叠头摘要文案：措辞按营业日分流（2026-10-08 起与窗口长短解耦）——「今晚」只给当前营业日的票，其余如实带相对日期 + 时刻。 */
class CrowdHeadlineTest {

    private static LocalDateTime t(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute);
    }

    private static CrowdVote vote(long userId, int level, LocalDateTime at) {
        return new CrowdVote(userId, level, 1.0, at, userId * 100 + at.getDayOfMonth());
    }

    @Test
    void inWindowSaysTonightWithMedianAndVoterCount() {
        LocalDateTime now = t(10, 7, 23, 30);
        List<CrowdVote> votes = List.of(vote(1, 5, t(10, 7, 22, 0)), vote(2, 5, t(10, 7, 23, 0)), vote(3, 6, t(10, 7, 23, 20)));
        assertEquals(Optional.of("今晚 约100 · 3人"), CrowdHeadline.build(now, votes));
    }

    @Test
    void inWindowCountsPeopleNotRows() {
        // 同一人跨午夜重报两行：摘要里是 1 个人
        LocalDateTime now = t(10, 8, 0, 30);
        List<CrowdVote> votes = List.of(vote(1, 5, t(10, 7, 23, 50)), vote(1, 3, t(10, 8, 0, 10)));
        assertEquals(Optional.of("今晚 约50 · 1人"), CrowdHeadline.build(now, votes));
    }

    @Test
    void lastNightVotesAreNeverCalledTonightEvenWhenStillFresh() {
        // 2026-10-08：有效期放宽到 1 天后，清晨 / 白天的窗口内可能只剩昨晚的票——
        // 它仍新鲜（不置灰、进统计），但绝不许说成「今晚」（那是营业日措辞，不是「新不新鲜」）。
        LocalDateTime now = t(10, 7, 12, 0);
        List<CrowdVote> votes = List.of(vote(1, 3, t(10, 6, 23, 40)));
        assertEquals(Optional.of("昨晚 23:40 约50 · 1人"), CrowdHeadline.build(now, votes));
    }

    @Test
    void afterMidnightReportBelongsToTheNightBefore() {
        // 凌晨 01:30 的上报营业日是 10-06，白天 12:00 回看 ⇒ 仍是「昨晚」
        LocalDateTime now = t(10, 7, 12, 0);
        List<CrowdVote> votes = List.of(vote(1, 4, t(10, 7, 1, 30)));
        assertEquals(Optional.of("昨晚 01:30 约80 · 1人"), CrowdHeadline.build(now, votes));
    }

    @Test
    void daytimeReportOfYesterdayIsNotCalledAnEvening() {
        LocalDateTime now = t(10, 7, 12, 0);
        List<CrowdVote> votes = List.of(vote(1, 2, t(10, 6, 13, 0)));
        assertEquals(Optional.of("昨天 13:00 约30 · 1人"), CrowdHeadline.build(now, votes));
    }

    @Test
    void sameBusinessDayVotesStayTonightWhileInsideTheValidWindow() {
        // 14:00 的票到 23:30 相距 9.5h —— 有效期 1 天内仍算数（且同属当前营业日）⇒「今晚」。
        // （6h 窗口时代此场景给「今天 14:00」；窗口放宽后它不再"过期"，措辞随有效性升级。）
        LocalDateTime now = t(10, 7, 23, 30);
        List<CrowdVote> votes = List.of(vote(1, 3, t(10, 7, 14, 0)));
        assertEquals(Optional.of("今晚 约50 · 1人"), CrowdHeadline.build(now, votes));
    }

    @Test
    void tonightHeadlineCountsOnlyTheCurrentBusinessNight() {
        // 白天查：窗口内既有昨晚的票（仍新鲜）又有今晚的票 —— 「今晚」只统计当前营业日的票，
        // 昨晚那票的档位（8）不得混进今晚的中位数。
        LocalDateTime now = t(10, 8, 14, 0);
        List<CrowdVote> votes = List.of(
                vote(1, 8, t(10, 7, 21, 0)),
                vote(2, 3, t(10, 8, 11, 0)),
                vote(3, 3, t(10, 8, 13, 0)));
        assertEquals(Optional.of("今晚 约50 · 2人"), CrowdHeadline.build(now, votes));
    }

    @Test
    void olderThanYesterdayUsesTheCalendarDate() {
        LocalDateTime now = t(10, 7, 12, 0);
        List<CrowdVote> votes = List.of(vote(1, 6, t(10, 4, 21, 10)));
        assertEquals(Optional.of("10月4日 21:10 约150 · 1人"), CrowdHeadline.build(now, votes));
    }

    @Test
    void pickTheLatestNightAndSummarizeItsVotersOnly() {
        LocalDateTime now = t(10, 7, 12, 0);
        List<CrowdVote> votes = List.of(
                vote(1, 8, t(10, 4, 22, 0)),                       // 更早一夜，不参与
                vote(2, 3, t(10, 6, 21, 0)), vote(3, 3, t(10, 6, 23, 0)), vote(4, 4, t(10, 6, 23, 55)));
        assertEquals(Optional.of("昨晚 23:55 约50 · 3人"), CrowdHeadline.build(now, votes));
    }

    @Test
    void nothingWithinTheLookbackMeansNoHeadline() {
        LocalDateTime now = t(10, 20, 12, 0);
        // 回看 7 个营业日：10-13 05:00 之前的都不算（10-01 的上报已出范围）
        assertTrue(CrowdHeadline.build(now, List.of(vote(1, 5, t(10, 1, 22, 0)))).isEmpty());
        assertTrue(CrowdHeadline.build(now, List.of()).isEmpty());
    }

    @Test
    void recentIgnoresVotesInsideTheWindowBecauseTheCallerAlreadyHandledThem() {
        // tonight() 与 recent() 分工：窗口内有票时调用方只走 tonight；recent 单独调用也不应崩
        LocalDateTime now = t(10, 7, 23, 30);
        assertEquals(Optional.of("今天 23:00 约100 · 1人"),
                CrowdHeadline.recent(now, List.of(vote(1, 5, t(10, 7, 23, 0)))));
    }
}
