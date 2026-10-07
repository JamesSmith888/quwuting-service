package org.quwuting.quwutingservice.venuecrowd.stat;

/**
 * 样本量分档（2026-10-07）：决定展示层<b>怎么诚实地表达</b>，不决定数据算不算数。
 * <ul>
 *   <li>{@link #NONE}：0 人——不出数字，改成邀请；</li>
 *   <li>{@link #SPARSE}：1~2 人——只列原值，不叫「常态」；</li>
 *   <li>{@link #LIMITED}：3~4 人——中位数 + 最低~最高，标「样本少」；</li>
 *   <li>{@link #SOLID}：≥5 人——中位数 + 四分位区间（中间 50%）。</li>
 * </ul>
 * 阈值见 {@link CrowdPolicy}。
 */
public enum SampleTier {
    NONE,
    SPARSE,
    LIMITED,
    SOLID;

    public static SampleTier of(int voterCount) {
        if (voterCount <= 0) {
            return NONE;
        }
        if (voterCount <= CrowdPolicy.SPARSE_MAX_VOTERS) {
            return SPARSE;
        }
        if (voterCount <= CrowdPolicy.LIMITED_MAX_VOTERS) {
            return LIMITED;
        }
        return SOLID;
    }
}
