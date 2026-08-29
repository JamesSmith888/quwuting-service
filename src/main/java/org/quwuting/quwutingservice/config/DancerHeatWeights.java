package org.quwuting.quwutingservice.config;

/**
 * 舞伴「排名热度」公式权重常量（**唯一事实源，2026-08-29 排序 v2 重构**）。
 * <p>
 * 根因（对齐 {@link VenueHeatWeights} 收敛前的同款缺陷）：舞伴列表 HOT 排序公式
 * 在 {@code DancerRepository#findPublicPage} SQL 内硬编码数字（认可 +0、新舞伴 +2、
 * 更新 +2），且排序信号从未与商业目标对齐审视——2026-08-29 全漏斗复盘发现
 * 「近7天认可数」（免费点赞）是主导信号，而与成交真正相关的「联系解锁数」
 * （用户烧积分的付费意向）完全不参与排序：懒懒Q 12 票仅 1 次解锁、3 周全站
 * 53 次解锁 0 成交。权重散落 SQL 无唯一事实源，调整口径必漏同步（门店域
 * 2026-08-10 同款根因，见 VenueHeatWeights 头注释）。
 * <p>
 * 收敛方案：权重与窗口常量全部收敛到本类，两处镜像经字符串拼接引用——
 * <ol>
 *   <li>{@code DancerRepository#findPublicPage}（native SQL 排序片段，
 *       INTENT/RECOGNITION 权重与窗口参数拼入）；</li>
 *   <li>{@code DancerStatsService}（Java 热度构成计算 + formulaText 文案，
 *       统计图「排名热度」卡展示口径 = 列表排序口径，对齐门店
 *       「排序与热度页统一」2026-08-08 先例）。</li>
 * </ol>
 * <p>
 * <b>排序信号原则（2026-08-29 确立，写入 docs/agents/09「列表排序」）</b>：
 * 每个排序信号必须回答「该信号是否预测用户目标行为（约到舞伴/成交）」——
 * 免费社交信号（认可）只能做平滑项，付费意向信号（联系解锁）才是主导项；
 * 新增排序信号时须先过该原则再进公式。
 */
public final class DancerHeatWeights {

    private DancerHeatWeights() {}

    // ── 主信号：付费意向（2026-08-29 排序 v2） ──
    /** 近7天联系方式解锁数权重（主导信号——烧积分的真实意向，与成交最相关） */
    public static final long UNLOCK_CONTACT = 3;
    /** 近7天认可数权重（平滑项——免费点赞与成交零相关，数据量小时提供区分度） */
    public static final long RECOGNITION = 1;

    // ── 新鲜度加成（沿用 2026-08-26 晚口径） ──
    /** 新舞伴加成（冷启动保护期，created_at 在保护期内 +2） */
    public static final long NEW_DANCER_BONUS = 2;
    /** 资料新鲜度加成（近3天更新过相册或联系方式任一 +2，「正在维护资料」活跃信号） */
    public static final long FRESH_UPDATE_BONUS = 2;

    // ── 窗口常量（排序 SQL 与统计热度构成共用，禁止各处硬编码天数） ──
    /** 意向/认可滚动窗口：近 7 天（排序主导窗口，与门店「默认近7天」口径一致） */
    public static final int INTENT_WINDOW_DAYS = 7;
    /** 收藏 tie-break 窗口：近 30 天（收藏零成本表达长期兴趣，口径中性） */
    public static final int FAV_WINDOW_DAYS = 30;
    /** 新舞伴保护期窗口：14 天 */
    public static final int NEW_DANCER_WINDOW_DAYS = 14;
    /** 资料新鲜度窗口：3 天（相册/联系方式） */
    public static final int FRESH_WINDOW_DAYS = 3;
}
