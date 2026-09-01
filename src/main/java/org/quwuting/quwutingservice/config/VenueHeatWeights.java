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

    // ── 浏览贡献（2026-08-27 重构：线性 PV 计数 → 来源质量加权 + 时效衰减 + 对数压缩） ──
    // 背景：浏览量是行为热度中唯一的"被动信号"（详情页点开即计，门槛极低），线性计入时
    // 排序靠前 → 曝光多 → LIST 点入多 → 浏览涨 → 排名更前的马太闭环被无限放大（位置偏差
    // 反馈循环）。重构三件套（缺一不可，语义见 AGENTS.md「场所热度 · 浏览贡献重构」）：
    //   1) 来源质量加权：LIST（列表曝光驱动，位置偏差最大，闭环核心）降权 0.5；
    //      SEARCH（主动搜索进入，强意图）1.5；SHARE（分享卡片进入，口碑传播，
    //      最真实的"被推荐"信号）2.0；OTHER（收藏/深链等）维持 1.0 基准。
    //   2) 时效衰减：近 7 天浏览 ×2，7~30 天 ×1——热度更"当下"，减少"30 天前火过"
    //      对当前排名的拖影。
    //   3) 对数压缩 ln(1+x)：浏览量的边际信息含量递减（0→10 次浏览远比 1000→2000
    //      更能说明热度上升），ln 压缩后头部店浏览量不再以线性差距碾压长尾门店。
    // 最终浏览贡献 = round(ln(1 + Σ(source_weight × time_factor)))，30 天窗口。
    // 三处镜像统一引用（HEAT_BEHAVIOR JPQL / findHotVenueIds native / computeHeat Java）。
    /** 浏览来源权重：LIST（列表曝光驱动，位置偏差最大，马太闭环核心） */
    public static final double VIEW_SOURCE_LIST = 0.5;
    /** 浏览来源权重：OTHER（收藏/深链/页面栈恢复等，现状基准） */
    public static final double VIEW_SOURCE_OTHER = 1.0;
    /** 浏览来源权重：SEARCH（列表页搜索结果进入，主动查找，强意图） */
    public static final double VIEW_SOURCE_SEARCH = 1.5;
    /** 浏览来源权重：SHARE（分享卡片进入，口碑传播，最真实的被推荐信号） */
    public static final double VIEW_SOURCE_SHARE = 2.0;
    /** 浏览时效权重：近 7 天（含今日）浏览翻倍，7~30 天按 1 计（分段衰减） */
    public static final double VIEW_RECENCY_7D_MULTIPLIER = 2.0;

    /**
     * 收藏项权重（2026-09-01 口径收敛）：热度公式收藏项<b>唯一输入 = 近30天新增收藏</b>
     * （取消收藏软删后自动抵消——一次收藏→取消 = +15→−15 = 0，可对账）。
     * 收藏总数（favoriteCount）仅作页面展示（累计），不再参与热度公式。
     * 根因：旧口径「收藏总数×10 + 近30天新增×15」两列是集合包含关系（新增 ⊂ 总数），
     * 一次新收藏被重复计 10+15=25 分；且收藏总数是永不过期的存量项，历史收藏让老店
     * 永久占优，与浏览贡献 2026-08-27「砍存量、重时效」的重构方向背道而驰（见 AGENTS.md「场所热度」）。
     */
    public static final long NEW_FAVORITE = 15;

    /**
     * 动态权重（2026-09-01 口径收敛）：由「动态总数」改为「近30天新增动态」。
     * 根因：动态是 admin 直发的运营内容而非用户行为，动态总数（存量、永不过期）让
     * 内容积累多年的老店靠存量压住新店；改窗口后运营活跃度信号保留、存量马太消除。
     */
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
