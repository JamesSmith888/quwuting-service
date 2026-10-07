package org.quwuting.quwutingservice.venuecrowd.stat;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdBaseline;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 常态人气：窗口口径（前 N 个营业日、不含今晚）、一人一票、按样本量分档的措辞、今晚 vs 常态的比较。
 * 文案是服务端权威契约，这里逐字断言。
 */
class CrowdBaselineBuilderTest {

    /** 「当前时刻」：10-07 12:00（营业日 10-07）。窗口 = 10-07 05:00 之前的前 N 个营业日。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 12, 0);

    private static long seq = 1000;

    private static CrowdVote vote(long userId, int level, LocalDateTime at) {
        return new CrowdVote(userId, level, 1.0, at, seq++);
    }

    /** n 个不同的人，各在 daysAgo 天前的 21:00 报同一档 */
    private static List<CrowdVote> people(int firstUserId, int n, int level, int daysAgo) {
        List<CrowdVote> votes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            votes.add(vote(firstUserId + i, level, NOW.toLocalDate().minusDays(daysAgo).atTime(21, 0)));
        }
        return votes;
    }

    private static CrowdBaseline.Window window(CrowdBaseline b, String key) {
        return b.windows().stream().filter(w -> w.key().equals(key)).findFirst().orElseThrow();
    }

    private static CrowdVerdict verdictOf(int n, int level) {
        List<CrowdVote> votes = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            votes.add(new CrowdVote(i, level, 1.0, NOW.minusHours(1), i));
        }
        return CrowdConsensus.evaluate(votes);
    }

    @Test
    void windowsAreSevenAndThirtyDaysInOrder() {
        CrowdBaseline b = CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), List.of());
        assertEquals(List.of("D7", "D30"), b.windows().stream().map(CrowdBaseline.Window::key).toList());
        assertEquals(List.of("近7天", "近30天"), b.windows().stream().map(CrowdBaseline.Window::title).toList());
        assertEquals(CrowdBaselineBuilder.NOTE_TEXT, b.noteText());
    }

    @Test
    void noVotesInvitesInsteadOfShowingANumber() {
        CrowdBaseline b = CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), List.of());
        CrowdBaseline.Window w7 = window(b, "D7");
        assertEquals("NONE", w7.sampleTier());
        assertNull(w7.valueText());
        assertNull(w7.rangeText());
        assertEquals("近7天还没有舞友报过，去过的话报一下", w7.inviteText());
        assertEquals("近30天还没有舞友报过，去过的话报一下", window(b, "D30").inviteText());
        assertNull(b.deviationText());
    }

    @Test
    void oneVoterIsListedAsRawValueNotCalledANormalLevel() {
        CrowdBaseline b = CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), people(1, 1, 3, 2));
        CrowdBaseline.Window w = window(b, "D7");
        assertEquals("SPARSE", w.sampleTier());
        assertEquals("约50", w.valueText());
        assertEquals("仅 1 位舞友报过 · 样本太少，仅供参考", w.subText());
        assertNull(w.rangeText());
        assertNull(w.inviteText());
    }

    @Test
    void twoVotersAreListedAsRawValuesDeduplicated() {
        List<CrowdVote> votes = new ArrayList<>(people(1, 1, 2, 2));
        votes.addAll(people(2, 1, 7, 3));
        CrowdBaseline.Window w = window(CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), votes), "D7");
        assertEquals("SPARSE", w.sampleTier());
        assertEquals("约30、约200", w.valueText());
        assertEquals("2 位舞友报过 · 样本太少，仅供参考", w.subText());

        List<CrowdVote> same = new ArrayList<>(people(1, 2, 5, 2));
        CrowdBaseline.Window w2 = window(CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), same), "D7");
        assertEquals("约100", w2.valueText(), "两人同档不写「约100、约100」");
    }

    @Test
    void threeToFourVotersGetMedianAndFullRange() {
        List<CrowdVote> votes = new ArrayList<>(people(1, 1, 2, 2));
        votes.addAll(people(2, 1, 4, 2));
        votes.addAll(people(3, 1, 7, 2));
        CrowdBaseline.Window w = window(CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), votes), "D7");
        assertEquals("LIMITED", w.sampleTier());
        assertEquals("约80", w.valueText());
        assertEquals("最低到最高 约30 ~ 约200", w.rangeText());
        assertEquals("3 位舞友 · 样本少，仅供参考", w.subText());
    }

    @Test
    void fiveOrMoreVotersGetMedianAndInterquartileRange() {
        List<CrowdVote> votes = new ArrayList<>();
        int[] levels = {1, 3, 3, 4, 8};
        for (int i = 0; i < levels.length; i++) {
            votes.addAll(people(10 + i, 1, levels[i], 2));
        }
        CrowdBaseline.Window w = window(CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), votes), "D7");
        assertEquals("SOLID", w.sampleTier());
        assertEquals("约50", w.valueText());
        assertEquals("中间一半上报在 约50 ~ 约80", w.rangeText());
        assertEquals("5 位舞友", w.subText());
    }

    @Test
    void identicalReportsHaveNoRangeToShow() {
        CrowdBaseline.Window w = window(
                CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), people(1, 6, 5, 2)), "D7");
        assertEquals("SOLID", w.sampleTier());
        assertEquals("约100", w.valueText());
        assertNull(w.rangeText());
    }

    @Test
    void eachWindowOnlySeesItsOwnBusinessDays() {
        // 10 天前的 3 个人：7 天窗口看不到，30 天窗口看得到
        CrowdBaseline b = CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), people(1, 3, 5, 10));
        assertEquals(0, window(b, "D7").voterCount());
        assertEquals(3, window(b, "D30").voterCount());
    }

    @Test
    void tonightsBusinessDayIsNeverPartOfTheBaseline() {
        // 10-07 05:00 之后（含）属于「今晚所在营业日」，基线回答「平时」，不能被今晚污染
        List<CrowdVote> votes = new ArrayList<>();
        votes.add(vote(1, 8, LocalDateTime.of(2026, 10, 7, 9, 0)));
        votes.add(vote(2, 8, LocalDateTime.of(2026, 10, 7, 5, 0)));
        votes.add(vote(3, 3, LocalDateTime.of(2026, 10, 7, 4, 59)));   // 属于 10-06 营业日 ⇒ 计入
        CrowdBaseline b = CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), votes);
        assertEquals(1, window(b, "D7").voterCount());
        assertEquals("约50", window(b, "D7").valueText());
    }

    @Test
    void windowStartIsInclusiveAtTheBusinessDayBoundary() {
        // D7 窗口起点 = 今天营业日 − 7 天 的 05:00 = 09-30 05:00
        List<CrowdVote> votes = List.of(
                vote(1, 5, LocalDateTime.of(2026, 9, 30, 5, 0)),       // 恰在起点 ⇒ 计入
                vote(2, 5, LocalDateTime.of(2026, 9, 30, 4, 59)));     // 属于 09-29 营业日 ⇒ D7 不计
        CrowdBaseline b = CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), votes);
        assertEquals(1, window(b, "D7").voterCount());
        assertEquals(2, window(b, "D30").voterCount());
    }

    @Test
    void aPersonWhoReportsEveryNightCountsOnce() {
        List<CrowdVote> votes = new ArrayList<>();
        for (int d = 1; d <= 6; d++) {
            votes.add(vote(2, 1, NOW.toLocalDate().minusDays(d).atTime(22, 0)));
        }
        votes.addAll(people(10, 2, 6, 3));
        CrowdBaseline.Window w = window(CrowdBaselineBuilder.build(NOW, CrowdVerdict.empty(), votes), "D7");
        assertEquals(3, w.voterCount(), "6 条最低档只是一个人的一票");
        assertEquals("LIMITED", w.sampleTier());
        assertEquals("约150", w.valueText());
    }

    // ── 今晚 vs 常态 ─────────────────────────────────────────────────────────

    private static List<CrowdVote> baselineOf(int n, int level) {
        return people(100, n, level, 2);
    }

    @Test
    void deviationSaysHotterWhenTonightIsTwoOrMoreLevelsAboveTheBaseline() {
        CrowdBaseline b = CrowdBaselineBuilder.build(NOW, verdictOf(2, 7), baselineOf(3, 4));
        assertEquals("今晚比近30天常态更热闹（今晚约200，常态约80）", b.deviationText());
    }

    @Test
    void deviationSaysColderWhenTonightIsTwoOrMoreLevelsBelow() {
        CrowdBaseline b = CrowdBaselineBuilder.build(NOW, verdictOf(2, 2), baselineOf(3, 5));
        assertEquals("今晚比近30天常态更冷清（今晚约30，常态约100）", b.deviationText());
    }

    @Test
    void oneLevelOfDifferenceIsWithinEstimationErrorAndSaysSimilar() {
        CrowdBaseline b = CrowdBaselineBuilder.build(NOW, verdictOf(2, 5), baselineOf(3, 4));
        assertEquals("今晚和近30天常态差不多", b.deviationText());
    }

    @Test
    void aLoneTonightReportIsNeverAmplifiedIntoAComparison() {
        assertNull(CrowdBaselineBuilder.build(NOW, verdictOf(1, 8), baselineOf(5, 2)).deviationText());
    }

    @Test
    void noBaselineMeansNothingToCompareAgainst() {
        assertNull(CrowdBaselineBuilder.build(NOW, verdictOf(3, 8), baselineOf(2, 2)).deviationText());
        assertNull(CrowdBaselineBuilder.build(NOW, verdictOf(3, 8), List.of()).deviationText());
    }

    @Test
    void comparisonPrefersTheLongestWindowThatHasEnoughPeople() {
        // 7 天窗口 3 人全报约80；30 天窗口再并入 10 天前 4 个报 0-20 的人（共 7 人，中位被拉到 0-20）。
        // 若参照 7 天窗口：今晚约80 vs 常态约80 ⇒「差不多」；参照 30 天窗口（更稳定）：高 3 档 ⇒「更热闹」。
        List<CrowdVote> votes = new ArrayList<>(people(100, 3, 4, 2));
        votes.addAll(people(200, 4, 1, 10));
        CrowdBaseline b = CrowdBaselineBuilder.build(NOW, verdictOf(2, 4), votes);
        assertEquals("今晚比近30天常态更热闹（今晚约80，常态0-20）", b.deviationText());
    }
}
