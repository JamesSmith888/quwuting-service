package org.quwuting.quwutingservice.venuereaction;

/**
 * Reaction 字典（后台维护，不允许用户自由创建——避免色情/攻击/广告/竞对刷评价）。
 * <p>
 * 替代原"标签点赞"的轻量用户反馈：Telegram Reaction 式的表情化标签，一次点击 = 一次态度表达。
 * 正负向 Reaction 共存但不做"点赞/倒赞"二元对立（不提供 👍👎 成对选项，如"这店好/这店差"），
 * 而是用具体、中性的描述让用户自行判断（如 👴 舞伴年龄偏成熟，而不是"倒赞：舞伴年龄大"）——
 * 避免攻击性评价引发商家纠纷。单个语义具体的正向表达（如 👍 值得推荐、🍺 消费合理）允许，
 * 见 AGENTS.md「Reaction 快速反馈系统」章节。
 * <p>
 * emoji/label 是本字典的唯一来源（无管理后台 UI），与 {@link org.quwuting.quwutingservice.taginteraction.RatingDimensions}
 * 同模式：后续新增/调整 Reaction 只需修改此枚举，前端通过 stats 接口返回的 emoji/label 自动同步
 * （前端 Picker 静态字典 {@code constants/reactions.ts} 需同步镜像，见前端 AGENTS.md）。
 * <p>
 * 2026-08 扩版（表情越多越好）：在初版 9 项基础上按舞厅体验维度扩充至 16 项，
 * 正向（人气/氛围/音乐/推荐/消费/舞伴/环境/服务）→ 中性（普通）→ 负向（拥挤/排队/冷清/高消费/环境/服务）
 * 的展示序。条目顺序影响前端 Picker 网格展示序，修改时两端同步。
 * <p>
 * <b>极性（2026-08 确立）</b>：热度公式只统计 POSITIVE 项（正向反馈才是"热度"），NEGATIVE 项
 * 不计入公式、单独计数下发供详情页展示负面信号（见 VenueHeatService）——修复"被吐槽服务问题
 * 的店热度反而更高"的语义硬伤。极性是热度公式的唯一事实源；前端 Picker 展示全量 Reaction 不受影响。
 */
public enum ReactionCode {
    HOT("🔥", "人气旺", Polarity.POSITIVE),
    GOOD_VIBE("💃", "氛围好", Polarity.POSITIVE),
    GOOD_MUSIC("🎵", "音乐棒", Polarity.POSITIVE),
    RECOMMEND("👍", "值得推荐", Polarity.POSITIVE),
    FAIR_PRICE("🍺", "消费合理", Polarity.POSITIVE),
    YOUNG_PARTNER("👧", "年轻舞伴多", Polarity.POSITIVE),
    OLD_PARTNER("👴", "舞伴年龄偏成熟", Polarity.POSITIVE),
    CLEAN("✨", "干净整洁", Polarity.POSITIVE),
    GOOD_SERVICE("💁", "服务贴心", Polarity.POSITIVE),
    NORMAL("😐", "普通", Polarity.NEUTRAL),
    CROWDED("👥", "人多拥挤", Polarity.NEGATIVE),
    WAITING("⏳", "排队太久", Polarity.NEGATIVE),
    QUIET("🪑", "人气冷清", Polarity.NEGATIVE),
    HIGH_COST("💰", "消费较高", Polarity.NEGATIVE),
    BAD_ENV("😕", "环境一般", Polarity.NEGATIVE),
    SERVICE_ISSUE("😡", "服务问题", Polarity.NEGATIVE);

    /** Reaction 极性：热度公式只计入 POSITIVE；NEGATIVE 单独计数展示、不参与公式 */
    public enum Polarity {
        POSITIVE, NEUTRAL, NEGATIVE
    }

    private final String emoji;
    private final String label;
    private final Polarity polarity;

    ReactionCode(String emoji, String label, Polarity polarity) {
        this.emoji = emoji;
        this.label = label;
        this.polarity = polarity;
    }

    public String getEmoji() {
        return emoji;
    }

    public String getLabel() {
        return label;
    }

    public Polarity getPolarity() {
        return polarity;
    }

    /** 该 code 是否为正向反馈（热度公式计入项） */
    public static boolean isPositive(String code) {
        ReactionCode rc = valueOfSafe(code);
        return rc != null && rc.polarity == Polarity.POSITIVE;
    }

    /** 该 code 是否为负向反馈（热度公式不计入，单独展示） */
    public static boolean isNegative(String code) {
        ReactionCode rc = valueOfSafe(code);
        return rc != null && rc.polarity == Polarity.NEGATIVE;
    }

    private static ReactionCode valueOfSafe(String code) {
        if (code == null) return null;
        for (ReactionCode value : values()) {
            if (value.name().equals(code)) return value;
        }
        return null;
    }

    /** 校验字符串是否为合法的 Reaction 代码，避免 valueOf 抛出未受控异常 */
    public static boolean isValid(String code) {
        if (code == null) return false;
        for (ReactionCode value : values()) {
            if (value.name().equals(code)) return true;
        }
        return false;
    }
}
