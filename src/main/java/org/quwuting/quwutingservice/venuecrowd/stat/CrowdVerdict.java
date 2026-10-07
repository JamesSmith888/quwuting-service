package org.quwuting.quwutingservice.venuecrowd.stat;

import java.util.List;

/**
 * 一次统计的结论（2026-10-07，{@link CrowdConsensus#evaluate} 的唯一产出）。
 * <p>
 * 「中位数、四分位、是否一致、谁算一致」<b>都从这一个对象读</b>：详情页置信度分层、确认积分发放、
 * 折叠头摘要、常态人气、管理端「说法不一」标记全部消费它——任何人不得绕过它自己再算一遍。
 *
 * @param voterCount      独立投票人数（一人一票之后）
 * @param medianLevel     （加权）下中位档；0 = 无人
 * @param q1Level         下四分位档
 * @param q3Level         上四分位档
 * @param minLevel        最低档
 * @param maxLevel        最高档
 * @param agreementShare  落在中位数 ±{@link CrowdPolicy#AGREEMENT_TOLERANCE_LEVELS} 档内的权重占比（0~1）
 * @param weighted        是否启用了可信度权重（n ≥ {@link CrowdPolicy#WEIGHTED_MIN_VOTERS}）
 * @param sampleTier      样本量分档
 * @param sortedLevels    全部票的档位（升序，SPARSE 展示原值用）
 * @param agreeingVotes   与中位数一致的票（确认积分的受奖对象；按档位升序、同档按 userId）
 */
public record CrowdVerdict(
        int voterCount,
        int medianLevel,
        int q1Level,
        int q3Level,
        int minLevel,
        int maxLevel,
        double agreementShare,
        boolean weighted,
        SampleTier sampleTier,
        List<Integer> sortedLevels,
        List<CrowdVote> agreeingVotes
) {

    /** 无人投票的空结论。 */
    public static CrowdVerdict empty() {
        return new CrowdVerdict(0, 0, 0, 0, 0, 0, 0.0, false, SampleTier.NONE, List.of(), List.of());
    }

    public boolean isEmpty() {
        return voterCount == 0;
    }

    /**
     * 确认态：独立人数 ≥ {@link CrowdPolicy#CONFIRM_MIN_VOTERS} 且一致占比 ≥ {@link CrowdPolicy#CONFIRM_SHARE}。
     * 判定<b>只有一份实现</b>（{@link CrowdConsensus#isConfirmed}），这里仅是便于阅读的委托。
     */
    public boolean confirmed() {
        return CrowdConsensus.isConfirmed(this);
    }

    /** 说法不一：≥2 人且一致占比不足（唯一实现同上）。 */
    public boolean conflicting() {
        return CrowdConsensus.isConflicting(this);
    }
}
