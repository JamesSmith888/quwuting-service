package org.quwuting.quwutingservice.venuecrowd.stat;

import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdBaseline;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdFemaleLevel;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 常态人气构造器（2026-10-07，纯函数）：把「前 N 个营业日的上报」折成按样本量诚实表达的窗口文案，
 * 并给出「今晚 vs 常态」的比较结论。
 *
 * <h3>口径</h3>
 * <ul>
 *   <li><b>窗口</b> = 前 N 个营业日 [今天营业日 − N, 今天营业日)，<b>不含今晚所在营业日</b>——
 *       基线回答「平时」，今晚回答「此刻」，两者不能相互污染；</li>
 *   <li><b>一人一票</b> = 每人折成其在该窗口内全部上报的下中位档（{@link CrowdConsensus#typicalPerVoter}）：
 *       一个每晚都报的人只算一票，且代表他的「通常」；</li>
 *   <li><b>按样本量分档说话</b>（{@link SampleTier}）：样本不足时给精确数字 = 假精度；</li>
 *   <li><b>比较</b>只在两边样本都够时下结论（{@link CrowdPolicy#DEVIATION_MIN_TONIGHT_VOTERS} /
 *       {@link CrowdPolicy#DEVIATION_MIN_BASELINE_VOTERS}），且偏差 ≥ {@link CrowdPolicy#DEVIATION_MIN_LEVELS}
 *       档才说热闹 / 冷清（1 档在估计误差内）。</li>
 * </ul>
 * 只做女舞伴主信号；男客维度不进基线（首版刻意不做，见 53 号文档「非目标」）。
 */
public final class CrowdBaselineBuilder {

    /** 口径说明小字（含午场：约三成上报发生在 12-18 点，首版不按午 / 晚场拆分）。 */
    static final String NOTE_TEXT = "按舞友上报的中位数计算，含午场；样本少仅供参考";

    private CrowdBaselineBuilder() {
    }

    /**
     * @param now       当前时刻（服务层统一取一次）
     * @param tonight   今晚窗口的结论（可为空结论）
     * @param pastVotes 候选历史票（不要求已过滤窗口；本方法按营业日窗口自行截取；已剔除认领人）
     */
    public static CrowdBaseline build(LocalDateTime now, CrowdVerdict tonight, List<CrowdVote> pastVotes) {
        LocalDate today = BusinessDay.of(now);
        LocalDateTime end = BusinessDay.startOf(today);

        List<CrowdBaseline.Window> windows = new ArrayList<>();
        List<CrowdVerdict> verdicts = new ArrayList<>();
        for (int days : CrowdPolicy.BASELINE_WINDOW_DAYS) {
            LocalDateTime start = BusinessDay.startOf(today.minusDays(days));
            List<CrowdVote> inWindow = pastVotes.stream()
                    .filter(v -> !v.at().isBefore(start) && v.at().isBefore(end))
                    .toList();
            CrowdVerdict verdict = CrowdConsensus.evaluate(CrowdConsensus.typicalPerVoter(inWindow));
            verdicts.add(verdict);
            windows.add(toWindow(days, verdict));
        }
        return new CrowdBaseline(windows, deviationText(tonight, verdicts), NOTE_TEXT);
    }

    // ── 窗口文案 ─────────────────────────────────────────────────────────────

    private static CrowdBaseline.Window toWindow(int days, CrowdVerdict verdict) {
        String title = "近" + days + "天";
        String key = "D" + days;
        int n = verdict.voterCount();
        return switch (verdict.sampleTier()) {
            case NONE -> new CrowdBaseline.Window(key, title, SampleTier.NONE.name(), 0,
                    null, null, null, title + "还没有舞友报过，去过的话报一下");
            case SPARSE -> new CrowdBaseline.Window(key, title, SampleTier.SPARSE.name(), n,
                    rawValues(verdict), null,
                    (n == 1 ? "仅 1 位舞友报过" : n + " 位舞友报过") + " · 样本太少，仅供参考", null);
            case LIMITED -> new CrowdBaseline.Window(key, title, SampleTier.LIMITED.name(), n,
                    nameOf(verdict.medianLevel()),
                    verdict.minLevel() == verdict.maxLevel() ? null
                            : "最低到最高 " + nameOf(verdict.minLevel()) + " ~ " + nameOf(verdict.maxLevel()),
                    n + " 位舞友 · 样本少，仅供参考", null);
            case SOLID -> new CrowdBaseline.Window(key, title, SampleTier.SOLID.name(), n,
                    nameOf(verdict.medianLevel()),
                    verdict.q1Level() == verdict.q3Level() ? null
                            : "中间一半上报在 " + nameOf(verdict.q1Level()) + " ~ " + nameOf(verdict.q3Level()),
                    n + " 位舞友", null);
        };
    }

    /** SPARSE 展示原值：去重保序，「约30、约200」。 */
    private static String rawValues(CrowdVerdict verdict) {
        Set<String> distinct = new LinkedHashSet<>();
        for (int level : verdict.sortedLevels()) {
            distinct.add(nameOf(level));
        }
        return distinct.stream().collect(Collectors.joining("、"));
    }

    // ── 今晚 vs 常态 ─────────────────────────────────────────────────────────

    /** 取样本足够的最长窗口作参照；两边样本都够才下结论。 */
    private static String deviationText(CrowdVerdict tonight, List<CrowdVerdict> windowVerdicts) {
        if (tonight.voterCount() < CrowdPolicy.DEVIATION_MIN_TONIGHT_VOTERS) {
            return null;
        }
        for (int i = windowVerdicts.size() - 1; i >= 0; i--) {
            CrowdVerdict reference = windowVerdicts.get(i);
            if (reference.voterCount() < CrowdPolicy.DEVIATION_MIN_BASELINE_VOTERS) {
                continue;
            }
            String label = "近" + CrowdPolicy.BASELINE_WINDOW_DAYS.get(i) + "天常态";
            int diff = tonight.medianLevel() - reference.medianLevel();
            if (diff >= CrowdPolicy.DEVIATION_MIN_LEVELS) {
                return "今晚比" + label + "更热闹（今晚" + nameOf(tonight.medianLevel())
                        + "，常态" + nameOf(reference.medianLevel()) + "）";
            }
            if (-diff >= CrowdPolicy.DEVIATION_MIN_LEVELS) {
                return "今晚比" + label + "更冷清（今晚" + nameOf(tonight.medianLevel())
                        + "，常态" + nameOf(reference.medianLevel()) + "）";
            }
            return "今晚和" + label + "差不多";
        }
        return null;
    }

    private static String nameOf(int level) {
        return CrowdFemaleLevel.of(level).getDisplayName();
    }
}
