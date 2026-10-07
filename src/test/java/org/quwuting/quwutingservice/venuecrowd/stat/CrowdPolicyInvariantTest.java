package org.quwuting.quwutingservice.venuecrowd.stat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口径常量之间的<b>相互约束</b>（2026-10-07）。
 * <p>
 * 单看每个数字都合理，但它们之间有耦合——改一个而忘了另一个，系统不会报错，只会静默变得不自洽。
 * 这些耦合过去只存在于作者脑子里（或某条注释里）；现在落成断言，改数字的人会被当场拦下并读到原因。
 */
class CrowdPolicyInvariantTest {

    @Test
    void noSingleVoterCanDecideAloneOnceWeightsAreEnabled() {
        // n = WEIGHTED_MIN_VOTERS 时，一个封顶权重的人对其余 n-1 个权重 1.0 的人：占比上界 cap / (cap + n − 1)
        double maxShare = CrowdPolicy.STAT_WEIGHT_CAP
                / (CrowdPolicy.STAT_WEIGHT_CAP + CrowdPolicy.WEIGHTED_MIN_VOTERS - 1);
        assertTrue(maxShare <= 1.0 / 3 + 1e-9,
                "权重启用时单人占比必须 ≤ 1/3，当前 " + maxShare + "——调大 cap 或调小 WEIGHTED_MIN_VOTERS 会让一个人单独决定结果");
    }

    @Test
    void capEqualsTheVeteranThresholdSoVeteransAndAboveAreNotDistinguished() {
        assertEquals(CrowdPolicy.VETERAN_WEIGHT, CrowdPolicy.STAT_WEIGHT_CAP);
        assertTrue(CrowdPolicy.REGULAR_WEIGHT < CrowdPolicy.VETERAN_WEIGHT);
        assertTrue(CrowdPolicy.STAT_WEIGHT_FLOOR >= 1.0, "可信度权重本身 ≥1.0，下限低于 1 会让新号票比默认还轻");
    }

    @Test
    void confirmationNeedsMoreThanASparseSample() {
        // 「多人报过」至少要落在 LIMITED（3~4 人）或以上；否则 2 人也能确认、发积分
        assertEquals(CrowdPolicy.SPARSE_MAX_VOTERS + 1, CrowdPolicy.CONFIRM_MIN_VOTERS);
        assertTrue(CrowdPolicy.LIMITED_MAX_VOTERS < CrowdPolicy.WEIGHTED_MIN_VOTERS,
                "权重必须只在 SOLID 样本上启用（LIMITED 及以下等权）");
        assertEquals(CrowdPolicy.LIMITED_MAX_VOTERS + 1, CrowdPolicy.WEIGHTED_MIN_VOTERS);
    }

    @Test
    void comparisonThresholdsNeverExceedWhatCountsAsASample() {
        assertTrue(CrowdPolicy.DEVIATION_MIN_BASELINE_VOTERS >= CrowdPolicy.CONFIRM_MIN_VOTERS,
                "「常态」至少要有 LIMITED 以上样本，否则是拿孤证当常态");
        assertTrue(CrowdPolicy.DEVIATION_MIN_TONIGHT_VOTERS >= 2, "一条孤证不能被放大成「比常态热闹」");
        assertTrue(CrowdPolicy.DEVIATION_MIN_LEVELS >= 2, "1 档在估计误差内（相邻档约差 1.5 倍）");
        assertTrue(CrowdPolicy.DEVIATION_MIN_LEVELS > CrowdPolicy.AGREEMENT_TOLERANCE_LEVELS,
                "比较所需偏差必须大于一致性容差，否则「算一致」的两个档位又会被说成「更热闹」");
    }

    @Test
    void baselineWindowsAreStrictlyAscendingAndCoverTheHeadlineLookback() {
        List<Integer> days = CrowdPolicy.BASELINE_WINDOW_DAYS;
        assertTrue(days.size() >= 1);
        for (int i = 0; i < days.size(); i++) {
            assertTrue(days.get(i) > 0);
            if (i > 0) {
                assertTrue(days.get(i) > days.get(i - 1), "窗口必须严格递增（比较逻辑从最长窗口往回找）");
            }
        }
        assertTrue(CrowdPolicy.HEADLINE_LOOKBACK_DAYS <= days.get(days.size() - 1));
    }

    @Test
    void businessDayCutoffSitsBeforeTheEveningAndIsAnHour() {
        assertTrue(CrowdPolicy.BUSINESS_DAY_START_HOUR > 0 && CrowdPolicy.BUSINESS_DAY_START_HOUR < CrowdPolicy.NIGHT_FROM_HOUR);
    }
}
