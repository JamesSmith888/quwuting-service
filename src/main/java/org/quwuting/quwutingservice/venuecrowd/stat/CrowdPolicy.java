package org.quwuting.quwutingservice.venuecrowd.stat;

import java.util.List;

/**
 * 门店热度统计口径的<b>唯一事实源</b>（2026-10-07，docs/agents/53-venue-crowd-stats.md）。
 * <p>
 * 本类只放<b>口径常量</b>，不放逻辑；统计逻辑在 {@link CrowdConsensus}（纯函数）、
 * 日期归属在 {@link BusinessDay}。其它类<b>禁止</b>再声明同义常量或内联魔法数
 * （门禁 {@code CrowdDomainSingleSourceTest} 逐文件扫描）。
 *
 * <h3>为什么要有这个类（根因）</h3>
 * 2026-09-03 的口径常量散落在 {@code CrowdReportService} 的 {@code static final} 里，
 * 「确认态」被实现了两遍（{@code resolveTier} 与 {@code WindowSnapshot.confirmed()}），
 * 靠一句注释「判定口径必须与 resolveTier 一致」维系——注释不是契约，改一处漏一处即静默漂移。
 * 现在：常量一处声明、判定一个函数，调用方只能引用。
 *
 * <h3>每个数字的来由（改之前先读）</h3>
 * 生产实测（2026-10-07）：有效上报 37 条 / 12 人 / 40 天；<b>任意 6h 窗口内独立人数历史最大 = 2</b>；
 * 03:00~11:00 零上报。所以阈值按「样本极小」设计：宁可诚实地说「样本少」，也不在 n≤2 时假装统计显著。
 */
public final class CrowdPolicy {

    private CrowdPolicy() {
    }

    // ── 时间口径 ────────────────────────────────────────────────────────────

    /** 「今晚」滚动窗口（小时）：人数是「此刻」的信号，窗口外的数据撤下（历史页仍可回看）。 */
    public static final int TONIGHT_WINDOW_HOURS = 6;

    /**
     * 营业日分界（点）：05:00 之前的时刻归属前一个营业日。
     * 依据：营业时段多为 13:00~次日 0-2 点；生产 03:00~11:00 零上报 ⇒ 分界落在死区，
     * 不会把同一场夜劈成两个营业日。改这个值 = 改「一人一夜一票」的唯一键语义，需同步迁移。
     */
    public static final int BUSINESS_DAY_START_HOUR = 5;

    /** 「夜间」起点（点）：仅用于摘要措辞「昨晚 / 昨天」的分流，不参与任何统计判定。 */
    public static final int NIGHT_FROM_HOUR = 18;

    // ── 一致性与确认 ────────────────────────────────────────────────────────

    /**
     * 「与中位数一致」的容差（档）：相邻档算一致。
     * 依据：8 档有序量表、相邻档约差 1.5 倍——诚实的人报相邻档是常态（旧口径要求精确同档，
     * 会把「约80 / 约100」判成说法不一）。
     */
    public static final int AGREEMENT_TOLERANCE_LEVELS = 1;

    /** 一致性占比阈值：容差内权重占比 ≥ 该值视为「一致」，否则「说法不一」。 */
    public static final double CONFIRM_SHARE = 0.6;

    /** 确认态最小独立人数：少于该人数一律中性降级（不出「多人报过」，不发确认积分）。 */
    public static final int CONFIRM_MIN_VOTERS = 3;

    // ── 可信度权重 ──────────────────────────────────────────────────────────

    /**
     * 启用可信度权重的最小独立人数：n 小于该值一律等权。
     * 依据：n≤4 时权重最高 4.5 倍，3 人时单人可占 69%——小样本下权重不是「加权」而是「指定」。
     */
    public static final int WEIGHTED_MIN_VOTERS = 5;

    /**
     * 统计用权重上限：高于此值的可信度权重按此值计。
     * 取值 = {@link #VETERAN_WEIGHT}（资深及以上不再区分）；n≥{@link #WEIGHTED_MIN_VOTERS} 时
     * 单人权重占比上界 = cap / (cap + n − 1) ≤ 2/6 = 1/3，任何一个人都不可能单独决定结果
     * （不变量由 {@code CrowdPolicyInvariantTest} 守）。
     */
    public static final double STAT_WEIGHT_CAP = 2.0;

    /** 统计用权重下限（可信度权重本身 ≥1.0；防御性下限，杜绝 0/负权重）。 */
    public static final double STAT_WEIGHT_FLOOR = 1.0;

    /** 「资深」徽标阈值（可信度权重 ≥ 该值；N==1 时升级为「资深舞友报告」）。 */
    public static final double VETERAN_WEIGHT = 2.0;

    /** 「常客」徽标阈值（1.2 ≤ 权重 &lt; 2.0；有打卡/少量采纳但未达资深）。 */
    public static final double REGULAR_WEIGHT = 1.2;

    // ── 样本量分档（决定「怎么诚实地说」，不决定「算不算数」）──────────────────

    /** SPARSE 上界（含）：1~2 人只列原值，不叫「常态」。 */
    public static final int SPARSE_MAX_VOTERS = 2;

    /** LIMITED 上界（含）：3~4 人给中位数 + 最低~最高，标「样本少」；≥5 人才给四分位区间。 */
    public static final int LIMITED_MAX_VOTERS = 4;

    // ── 常态人气（基线）──────────────────────────────────────────────────────

    /** 基线窗口（天，<b>前 N 个营业日、不含今晚所在营业日</b>），顺序即展示顺序。 */
    public static final List<Integer> BASELINE_WINDOW_DAYS = List.of(7, 30);

    /** 折叠头摘要的回看天数：窗口内没有数据时，往前找最近一个有上报的营业日。 */
    public static final int HEADLINE_LOOKBACK_DAYS = 7;

    /** 「比常态热闹/冷清」最小偏差（档）：1 档在估计误差内不下结论，≥2 档（约 2 倍）才说。 */
    public static final int DEVIATION_MIN_LEVELS = 2;

    /** 比较时「今晚」至少需要的独立人数（防一条孤证被放大成评价）。 */
    public static final int DEVIATION_MIN_TONIGHT_VOTERS = 2;

    /** 比较时「常态」至少需要的独立人数（低于此值没有「常态」可言）。 */
    public static final int DEVIATION_MIN_BASELINE_VOTERS = 3;

    // ── 列表公共面 ──────────────────────────────────────────────────────────

    /** 列表角标「N人报过」的最小独立人数（公共面克制：&lt;3 人不上列表，防误伤与商家刷量）。 */
    public static final int BADGE_MIN_VOTERS = 3;
}
