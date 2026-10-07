package org.quwuting.quwutingservice.venuecrowd.stat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdTier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热度统计口径的表驱动测试（2026-10-07）。
 * <p>
 * 这里的每一行都是一条<b>规则的可执行说明</b>：不变量写在测试里，而不是靠注释「必须与某处一致」维系。
 * 对抗用例覆盖：单人高权重、两人合谋、跨午夜双投、偶数样本、全同值、双峰、顺序无关。
 */
class CrowdConsensusTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 7, 23, 0);

    private static CrowdVote v(long userId, int level) {
        return new CrowdVote(userId, level, 1.0, T0, userId);
    }

    private static CrowdVote v(long userId, int level, double trustWeight) {
        return new CrowdVote(userId, level, trustWeight, T0, userId);
    }

    private static CrowdVote at(long userId, int level, LocalDateTime at, long sourceId) {
        return new CrowdVote(userId, level, 1.0, at, sourceId);
    }

    // ── 主表：档位分布 → 统计结论 ─────────────────────────────────────────────

    /** 一行 = 一个场景：票集 → 期望的 (n, 中位, Q1, Q3, 一致占比, 是否加权, 置信度分层) */
    record Case(String name, List<CrowdVote> votes, int n, int median, int q1, int q3,
                double share, boolean weighted, CrowdTier tier) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                new Case("空票集", List.of(), 0, 0, 0, 0, 0.0, false, CrowdTier.EMPTY),
                new Case("单人", List.of(v(1, 5)), 1, 5, 5, 5, 1.0, false, CrowdTier.UNVERIFIED),
                new Case("单人资深（权重≥2.0 升级）", List.of(v(1, 5, 3.0)), 1, 5, 5, 5, 1.0, false,
                        CrowdTier.UNVERIFIED_VETERAN),
                new Case("两人同档", List.of(v(1, 5), v(2, 5)), 2, 5, 5, 5, 1.0, false, CrowdTier.UNVERIFIED),
                new Case("两人相邻档：诚实的人报相邻档 ≠ 说法不一（旧众数口径会判冲突）",
                        List.of(v(1, 5), v(2, 6)), 2, 5, 5, 6, 1.0, false, CrowdTier.UNVERIFIED),
                new Case("两人相隔三档：说法不一", List.of(v(1, 3), v(2, 6)), 2, 3, 3, 6, 0.5, false, CrowdTier.CONFLICT),
                new Case("三人全同档：确认", List.of(v(1, 4), v(2, 4), v(3, 4)), 3, 4, 4, 4, 1.0, false, CrowdTier.CONFIRMED),
                new Case("三人 3/4/8：两人相邻一致、一个离群 ⇒ 确认（中位数本身就排除了最高）",
                        List.of(v(1, 3), v(2, 4), v(3, 8)), 3, 4, 3, 8, 2.0 / 3, false, CrowdTier.CONFIRMED),
                new Case("三人 1/4/8：各执一词 ⇒ 说法不一", List.of(v(1, 1), v(2, 4), v(3, 8)), 3, 4, 1, 8,
                        1.0 / 3, false, CrowdTier.CONFLICT),
                new Case("偶数样本 n=4（2,3,6,7）：中位取较低档，且分成两堆 ⇒ 说法不一",
                        List.of(v(1, 2), v(2, 3), v(3, 6), v(4, 7)), 4, 3, 2, 6, 0.5, false, CrowdTier.CONFLICT),
                new Case("n=5 一致占比恰为 0.6（3/5）：阈值闭区间，确认",
                        List.of(v(1, 3), v(2, 3), v(3, 3), v(4, 8), v(5, 8)), 5, 3, 3, 8, 0.6, true, CrowdTier.CONFIRMED),
                new Case("n=5 奇数样本四分位（1,3,3,4,8）",
                        List.of(v(1, 1), v(2, 3), v(3, 3), v(4, 4), v(5, 8)), 5, 3, 3, 4, 0.6, true, CrowdTier.CONFIRMED),
                new Case("全同值 n=6：Q1=Q3=中位", sameLevel(6, 5), 6, 5, 5, 5, 1.0, true, CrowdTier.CONFIRMED),
                new Case("双峰 n=6（三个最低 + 三个最高）：说法不一，中位取较低",
                        List.of(v(1, 1), v(2, 1), v(3, 1), v(4, 8), v(5, 8), v(6, 8)), 6, 1, 1, 8, 0.5, true, CrowdTier.CONFLICT),
                new Case("均匀铺开 n=8（1..8）：四分位 2/6，一致占比 3/8 ⇒ 说法不一",
                        List.of(v(1, 1), v(2, 2), v(3, 3), v(4, 4), v(5, 5), v(6, 6), v(7, 7), v(8, 8)),
                        8, 4, 2, 6, 3.0 / 8, true, CrowdTier.CONFLICT)
        ).map(c -> Arguments.of(c));
    }

    private static List<CrowdVote> sameLevel(int n, int level) {
        List<CrowdVote> votes = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            votes.add(v(i, level));
        }
        return votes;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void evaluateMatchesTable(Case c) {
        CrowdVerdict verdict = CrowdConsensus.evaluate(c.votes());
        assertEquals(c.n(), verdict.voterCount(), "voterCount");
        assertEquals(c.median(), verdict.medianLevel(), "median");
        assertEquals(c.q1(), verdict.q1Level(), "q1");
        assertEquals(c.q3(), verdict.q3Level(), "q3");
        assertEquals(c.share(), verdict.agreementShare(), 1e-9, "agreementShare");
        assertEquals(c.weighted(), verdict.weighted(), "weighted");
        assertEquals(c.tier(), CrowdConsensus.tierOf(verdict), "tier");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void resultIsIndependentOfInputOrder(Case c) {
        List<CrowdVote> shuffled = new ArrayList<>(c.votes());
        Collections.shuffle(shuffled, new Random(42));
        assertEquals(CrowdConsensus.evaluate(c.votes()).medianLevel(),
                CrowdConsensus.evaluate(shuffled).medianLevel());
        assertEquals(CrowdConsensus.evaluate(c.votes()).agreementShare(),
                CrowdConsensus.evaluate(shuffled).agreementShare(), 1e-9);
    }

    // ── 一人一票 ─────────────────────────────────────────────────────────────

    @Test
    void crossMidnightDoubleVoteCollapsesToTheLatestOne() {
        // 同一个人同一夜：23:50 报约100、00:10 改报约50 —— 旧口径（自然日唯一键）会把两行都算进 6h 窗口
        LocalDateTime before = LocalDateTime.of(2026, 10, 7, 23, 50);
        LocalDateTime after = LocalDateTime.of(2026, 10, 8, 0, 10);
        List<CrowdVote> rows = List.of(at(7, 5, before, 1), at(7, 3, after, 2), at(8, 3, after, 3), at(9, 3, after, 4));

        List<CrowdVote> onePerVoter = CrowdConsensus.latestPerVoter(rows);

        assertEquals(3, onePerVoter.size());
        CrowdVote mine = onePerVoter.stream().filter(x -> x.userId() == 7).findFirst().orElseThrow();
        assertEquals(3, mine.level(), "取最新一张");
        assertEquals(2, mine.sourceId(), "代表行 id 跟随被选中的那张（确认积分幂等键）");
        assertEquals(3, CrowdConsensus.evaluate(onePerVoter).voterCount(), "独立人数按人算，不是按行");
    }

    @Test
    void latestPerVoterBreaksTimeTiesBySourceId() {
        List<CrowdVote> rows = List.of(at(1, 2, T0, 10), at(1, 6, T0, 11));
        assertEquals(6, CrowdConsensus.latestPerVoter(rows).get(0).level());
    }

    @Test
    void evaluateRefusesDuplicateVotersInsteadOfSilentlyDeduping() {
        // 静默去重会把「忘了折票」的 bug 藏起来；调用方编程错误必须响
        assertThrows(IllegalArgumentException.class,
                () -> CrowdConsensus.evaluate(List.of(v(1, 3), v(1, 5))));
    }

    @Test
    void typicalPerVoterUsesEachPersonsLowerMedian() {
        // 一个每晚都报的人只算一票，且代表他的「通常」而不是某一晚
        List<CrowdVote> rows = List.of(
                v(1, 2), v(1, 8), v(1, 5),          // 奇数：取中间 5
                v(2, 3), v(2, 6),                   // 偶数：取较低 3
                v(3, 7));
        List<CrowdVote> typical = CrowdConsensus.typicalPerVoter(rows);
        assertEquals(3, typical.size());
        assertEquals(5, typical.stream().filter(x -> x.userId() == 1).findFirst().orElseThrow().level());
        assertEquals(3, typical.stream().filter(x -> x.userId() == 2).findFirst().orElseThrow().level());
        assertEquals(7, typical.stream().filter(x -> x.userId() == 3).findFirst().orElseThrow().level());
    }

    @Test
    void aHeavyReporterCannotDominateTheBaseline() {
        // 现网 #2 一人 23 条、另有 3 人各 1 条：一人一票之后他只占 1/4
        List<CrowdVote> rows = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            rows.add(new CrowdVote(2, 1, 1.0, T0.minusDays(i), 100 + i));
        }
        rows.add(v(10, 6));
        rows.add(v(11, 6));
        rows.add(v(12, 7));
        CrowdVerdict verdict = CrowdConsensus.evaluate(CrowdConsensus.typicalPerVoter(rows));
        assertEquals(4, verdict.voterCount());
        assertEquals(6, verdict.medianLevel(), "23 条最低档只是一个人的一票");
    }

    // ── 权重：小样本等权；n≥5 才启用且封顶 ────────────────────────────────────

    @Test
    void weightsAreIgnoredBelowFiveVoters() {
        // n=4：一个 4.5 倍权重的账号报 8，三个普通账号报 2 —— 等权 ⇒ 中位 2
        CrowdVerdict verdict = CrowdConsensus.evaluate(List.of(v(1, 8, 4.5), v(2, 2), v(3, 2), v(4, 2)));
        assertFalse(verdict.weighted());
        assertEquals(2, verdict.medianLevel());
    }

    @Test
    void aSingleHighWeightAccountCannotFlipTheResultAtFiveVoters() {
        // n=5：一个权重 4.5 的账号报 8，四个普通账号报 2。
        // 不封顶：总权重 8.5，四个 2 的累计 4 < 4.25 ⇒ 中位被拉到 8（单人决定结果）。
        // 封顶 2.0：总权重 6，四个 2 的累计 4 ≥ 3 ⇒ 中位 2。
        CrowdVerdict verdict = CrowdConsensus.evaluate(
                List.of(v(1, 8, 4.5), v(2, 2), v(3, 2), v(4, 2), v(5, 2)));
        assertTrue(verdict.weighted());
        assertEquals(2, verdict.medianLevel());
        assertEquals(4.0 / 6.0, verdict.agreementShare(), 1e-9);
    }

    @Test
    void twoVeteranColludersCanOutvoteThreeNormalsAtFiveVoters_documentedBoundary() {
        // 已知边界（写进测试是为了让它被看见、被有意识地维护，而不是被当成「不可能」）：
        // 权重封顶只能保证「任何一个人不能单独决定」（≤1/3），不能阻止两个资深账号合谋（2+2 vs 1+1+1）。
        // 防线不在公式里，而在权重门槛本身（5 次被采纳的上报 + 10 天打卡）与管理端「说法不一 / 高频修改」可见性。
        CrowdVerdict verdict = CrowdConsensus.evaluate(
                List.of(v(1, 8, 4.5), v(2, 8, 4.5), v(3, 2), v(4, 2), v(5, 2)));
        assertEquals(8, verdict.medianLevel());
    }

    @Test
    void effectiveWeightIsClampedToTheConfiguredBand() {
        assertEquals(CrowdPolicy.STAT_WEIGHT_CAP, CrowdConsensus.effectiveWeight(4.5));
        assertEquals(CrowdPolicy.STAT_WEIGHT_FLOOR, CrowdConsensus.effectiveWeight(0.0));
        assertEquals(1.5, CrowdConsensus.effectiveWeight(1.5));
    }

    // ── 一致性 / 确认 / 受奖对象（同一个 verdict 读出来）──────────────────────

    @Test
    void agreeingVotesAreExactlyTheVotersWithinToleranceOfTheMedian() {
        CrowdVerdict verdict = CrowdConsensus.evaluate(List.of(v(1, 3), v(2, 4), v(3, 8)));
        assertTrue(verdict.confirmed());
        assertEquals(List.of(1L, 2L), verdict.agreeingVotes().stream().map(CrowdVote::userId).toList(),
                "受奖对象 = 中位数 ±1 档内的人，离群者（8）不在其中");
    }

    @Test
    void toleranceBoundaryIsInclusiveAtOneLevel() {
        // 中位 4：3、5 一致；2、6 不一致
        CrowdVerdict verdict = CrowdConsensus.evaluate(
                List.of(v(1, 2), v(2, 3), v(3, 4), v(4, 5), v(5, 6)));
        assertEquals(4, verdict.medianLevel());
        assertEquals(List.of(2L, 3L, 4L), verdict.agreeingVotes().stream().map(CrowdVote::userId).toList());
    }

    @Test
    void verdictHelpersDelegateToTheSingleDecisionFunction() {
        for (Object[] row : new Object[][]{
                {List.of(v(1, 3), v(2, 3), v(3, 8), v(4, 8), v(5, 8))},
                {List.of(v(1, 1), v(2, 4), v(3, 8))},
                {List.of(v(1, 4), v(2, 4), v(3, 4))}}) {
            @SuppressWarnings("unchecked")
            CrowdVerdict verdict = CrowdConsensus.evaluate((List<CrowdVote>) row[0]);
            assertEquals(CrowdConsensus.isConfirmed(verdict), verdict.confirmed());
            assertEquals(CrowdConsensus.isConflicting(verdict), verdict.conflicting());
        }
    }

    @Test
    void sampleTierBoundariesFollowThePolicy() {
        assertEquals(SampleTier.NONE, SampleTier.of(0));
        assertEquals(SampleTier.SPARSE, SampleTier.of(1));
        assertEquals(SampleTier.SPARSE, SampleTier.of(CrowdPolicy.SPARSE_MAX_VOTERS));
        assertEquals(SampleTier.LIMITED, SampleTier.of(CrowdPolicy.SPARSE_MAX_VOTERS + 1));
        assertEquals(SampleTier.LIMITED, SampleTier.of(CrowdPolicy.LIMITED_MAX_VOTERS));
        assertEquals(SampleTier.SOLID, SampleTier.of(CrowdPolicy.LIMITED_MAX_VOTERS + 1));
    }
}
