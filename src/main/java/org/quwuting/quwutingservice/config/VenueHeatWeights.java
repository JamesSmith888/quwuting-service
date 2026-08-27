package org.quwuting.quwutingservice.config;

/**
 * 热度公式权重常量（**唯一事实源，2026-08-10 V2 权重收敛重构**）。
 * <p>
 * 根因（长期维护负担）：热度公式在<b>三处镜像</b>——VenueHeatService.computeHeat
 * （Java 常量）、VenueRepository.HEAT_SCORE（JPQL 排序片段）、VenueRepository
 * .findHotVenueIds（native SQL 热门判定）——历史上权重散落三处硬编码数字，
 * 调整权重漏同步一处即口径漂移（AGENTS.md「场所热度」明文约束）。
 * <p>
 * 收敛方案：全部非配置化权重收敛到本类常量，三处镜像经字符串拼接引用
 * （HEAT_SCORE（2026-08-27 拆出行为口径 HEAT_BEHAVIOR）/ findHotVenueIds 是 SQL 字符串，
 * 直接拼常量值）——一处定义、三处引用。积分权重是<b>运营可调参数</b>（V2 校准机制），
 * 不在此处，走 {@link PointsProperties#heatWeight()}（JPQL 参数 :pointsWeight 注入）。
 */
public final class VenueHeatWeights {

    private VenueHeatWeights() {}

    /** 浏览量权重（近30天 PV） */
    public static final long VIEW = 1;

    /** 收藏总数权重 */
    public static final long FAVORITE = 10;

    /** 近30天新增收藏权重 */
    public static final long NEW_FAVORITE = 15;

    /** 动态总数权重 */
    public static final long POST = 5;

    /** 近30天评分数权重 */
    public static final long RATING = 8;

    /** 近30天正向 Reaction 权重（仅 Polarity.POSITIVE；负向不计入公式） */
    public static final long REACTION = 3;

    /** 满意度偏移权重（(分-6)×20） */
    public static final long SATISFACTION = 20;

    /** 满意度中性基准（1-10 分制及格线）：高于 6 加分、低于 6 扣分 */
    public static final double SATISFACTION_NEUTRAL = 6.0;
}
