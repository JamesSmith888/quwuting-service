package org.quwuting.quwutingservice.venuecrowd.stat;

import org.quwuting.quwutingservice.venuecrowd.enums.CrowdTier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 热度统计的<b>唯一判定函数</b>（2026-10-07，纯函数、无 Spring/DB 依赖，docs/agents/53-venue-crowd-stats.md）。
 *
 * <h3>统计量为什么是「（加权）下中位数 + 四分位」而不是「加权众数」</h3>
 * 人数档位是 <b>8 档有序量表</b>（0-20 / 约30 / … / 约300+，相邻档约差 1.5 倍），不是分类标签：
 * <ul>
 *   <li>众数要求「精确同档」才算一致——诚实的人报相邻档（约80 / 约100）被判成「说法不一」；</li>
 *   <li>均值在顶档「约300+」无上限、档间不等距时没有意义，还会产出「6.4 档」式假精度；</li>
 *   <li>中位数本身就是「排除最高最低」的稳健做法，结果永远是一个真实存在的档位。</li>
 * </ul>
 * 旧口径（加权众数占比 ≥0.6）照搬自门店突发事件上报（<b>分类</b>信号：暂停 / 恢复），
 * 这是一次「别的领域的规则按类比搬进来、没按本领域的数据类型与样本量校验」的决策。
 *
 * <h3>三条不变量（由 {@code CrowdConsensusTest} 表驱动守住）</h3>
 * <ol>
 *   <li><b>一人一票</b>：进入 {@link #evaluate} 的票必须每人至多一张（{@link #latestPerVoter} /
 *       {@link #typicalPerVoter} 是把「一个人的多次上报」折成一张票的两种语义，调用方二选一）；</li>
 *   <li><b>小样本等权</b>：独立人数 &lt; {@link CrowdPolicy#WEIGHTED_MIN_VOTERS} 时忽略可信度权重；
 *       ≥ 时权重按 [{@link CrowdPolicy#STAT_WEIGHT_FLOOR}, {@link CrowdPolicy#STAT_WEIGHT_CAP}] 截断
 *       ⇒ 单人权重占比不超过 1/3；</li>
 *   <li><b>一个判定函数</b>：详情页置信度、确认积分受奖人、折叠头摘要、常态人气、管理端冲突标记
 *       全部读 {@link CrowdVerdict}，不得各自再实现一遍「是否一致」。</li>
 * </ol>
 */
public final class CrowdConsensus {

    /** 浮点比较容差（权重累加 / 占比阈值比较用；避免 0.6 vs 0.5999999999 这类假阴性）。 */
    private static final double EPS = 1e-9;

    private CrowdConsensus() {
    }

    // ── 把「一个人的多次上报」折成一张票 ─────────────────────────────────────

    /**
     * 当前态：每个投票人只保留<b>最新</b>的一张（同一时刻取 sourceId 大者）。
     * 用于「今晚」窗口——同一人在窗口内再次上报是修正自己的观察，以最后一次为准。
     */
    public static List<CrowdVote> latestPerVoter(Collection<CrowdVote> votes) {
        Map<Long, CrowdVote> latest = new LinkedHashMap<>();
        for (CrowdVote vote : votes) {
            latest.merge(vote.userId(), vote, CrowdConsensus::newer);
        }
        return new ArrayList<>(latest.values());
    }

    /**
     * 典型态：每个投票人折成<b>其自身全部上报的下中位档</b>（时刻取其最近一次、权重取其最大值）。
     * 用于「近 7 / 30 天常态」——一个每晚都报的人只算一票，且代表他的「通常」而不是某一晚。
     */
    public static List<CrowdVote> typicalPerVoter(Collection<CrowdVote> votes) {
        Map<Long, List<CrowdVote>> byVoter = new LinkedHashMap<>();
        for (CrowdVote vote : votes) {
            byVoter.computeIfAbsent(vote.userId(), k -> new ArrayList<>()).add(vote);
        }
        List<CrowdVote> result = new ArrayList<>(byVoter.size());
        for (Map.Entry<Long, List<CrowdVote>> entry : byVoter.entrySet()) {
            List<CrowdVote> own = new ArrayList<>(entry.getValue());
            own.sort(Comparator.comparingInt(CrowdVote::level)
                    .thenComparing(CrowdVote::at)
                    .thenComparingLong(CrowdVote::sourceId));
            CrowdVote representative = own.get((own.size() - 1) / 2);
            CrowdVote latest = own.stream().reduce(CrowdConsensus::newer).orElse(representative);
            double maxWeight = own.stream().mapToDouble(CrowdVote::trustWeight).max().orElse(1.0);
            result.add(new CrowdVote(entry.getKey(), representative.level(), maxWeight,
                    latest.at(), representative.sourceId()));
        }
        return result;
    }

    // ── 唯一判定函数 ─────────────────────────────────────────────────────────

    /**
     * 对「一人一票」的票集做统计。
     *
     * @param votes 每个投票人至多一张（违反抛 {@link IllegalArgumentException}——这是调用方编程错误，
     *              静默去重会把「忘了折票」的 bug 藏起来）
     */
    public static CrowdVerdict evaluate(Collection<CrowdVote> votes) {
        if (votes == null || votes.isEmpty()) {
            return CrowdVerdict.empty();
        }
        requireOneVotePerVoter(votes);
        List<CrowdVote> sorted = new ArrayList<>(votes);
        sorted.sort(Comparator.comparingInt(CrowdVote::level).thenComparingLong(CrowdVote::userId));

        int n = sorted.size();
        boolean weighted = n >= CrowdPolicy.WEIGHTED_MIN_VOTERS;
        double[] weights = new double[n];
        double total = 0.0;
        for (int i = 0; i < n; i++) {
            weights[i] = weighted ? effectiveWeight(sorted.get(i).trustWeight()) : 1.0;
            total += weights[i];
        }

        int median = quantile(sorted, weights, total, 0.5);
        int q1 = quantile(sorted, weights, total, 0.25);
        int q3 = quantile(sorted, weights, total, 0.75);

        double within = 0.0;
        List<CrowdVote> agreeing = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (Math.abs(sorted.get(i).level() - median) <= CrowdPolicy.AGREEMENT_TOLERANCE_LEVELS) {
                within += weights[i];
                agreeing.add(sorted.get(i));
            }
        }
        List<Integer> levels = sorted.stream().map(CrowdVote::level).toList();
        return new CrowdVerdict(n, median, q1, q3, sorted.get(0).level(), sorted.get(n - 1).level(),
                within / total, weighted, SampleTier.of(n), levels, List.copyOf(agreeing));
    }

    /**
     * 置信度分层（详情页胶囊文案的唯一来源）：
     * 空 → EMPTY；1 人 → UNVERIFIED（权重 ≥ 资深阈值升级 UNVERIFIED_VETERAN）；
     * 确认态 → CONFIRMED；说法不一 → CONFLICT；其余（2 人一致）→ UNVERIFIED。
     */
    public static CrowdTier tierOf(CrowdVerdict verdict) {
        if (verdict.isEmpty()) {
            return CrowdTier.EMPTY;
        }
        if (verdict.voterCount() == 1) {
            double soleWeight = verdict.agreeingVotes().get(0).trustWeight();
            return soleWeight >= CrowdPolicy.VETERAN_WEIGHT ? CrowdTier.UNVERIFIED_VETERAN : CrowdTier.UNVERIFIED;
        }
        if (isConfirmed(verdict)) {
            return CrowdTier.CONFIRMED;
        }
        if (isConflicting(verdict)) {
            return CrowdTier.CONFLICT;
        }
        return CrowdTier.UNVERIFIED;
    }

    /** 确认态（唯一实现，带浮点容差；{@link CrowdVerdict#confirmed()} 仅委托到这里）。 */
    public static boolean isConfirmed(CrowdVerdict verdict) {
        return verdict.voterCount() >= CrowdPolicy.CONFIRM_MIN_VOTERS
                && verdict.agreementShare() + EPS >= CrowdPolicy.CONFIRM_SHARE;
    }

    /** 说法不一（带浮点容差的唯一实现）。 */
    public static boolean isConflicting(CrowdVerdict verdict) {
        return verdict.voterCount() >= 2 && verdict.agreementShare() + EPS < CrowdPolicy.CONFIRM_SHARE;
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /** 统计用权重：截断到 [FLOOR, CAP]。 */
    static double effectiveWeight(double trustWeight) {
        return Math.max(CrowdPolicy.STAT_WEIGHT_FLOOR, Math.min(CrowdPolicy.STAT_WEIGHT_CAP, trustWeight));
    }

    /** 加权下分位：累计权重首次 ≥ p×总权重的那一档（等权时 = nearest-rank，偶数样本的中位数取较低档）。 */
    private static int quantile(List<CrowdVote> sorted, double[] weights, double total, double p) {
        double target = p * total;
        double cumulative = 0.0;
        for (int i = 0; i < sorted.size(); i++) {
            cumulative += weights[i];
            if (cumulative + EPS >= target) {
                return sorted.get(i).level();
            }
        }
        return sorted.get(sorted.size() - 1).level();
    }

    private static CrowdVote newer(CrowdVote a, CrowdVote b) {
        int byTime = a.at().compareTo(b.at());
        if (byTime != 0) {
            return byTime > 0 ? a : b;
        }
        return a.sourceId() >= b.sourceId() ? a : b;
    }

    private static void requireOneVotePerVoter(Collection<CrowdVote> votes) {
        Set<Long> seen = new HashSet<>();
        for (CrowdVote vote : votes) {
            if (!seen.add(vote.userId())) {
                throw new IllegalArgumentException(
                        "evaluate 需要一人一票，用户 " + vote.userId() + " 出现多次——先用 latestPerVoter / typicalPerVoter 折票");
            }
        }
    }
}
