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
 */
public enum ReactionCode {
    HOT("🔥", "人气旺"),
    GOOD_VIBE("💃", "氛围好"),
    GOOD_MUSIC("🎵", "音乐棒"),
    RECOMMEND("👍", "值得推荐"),
    FAIR_PRICE("🍺", "消费合理"),
    YOUNG_PARTNER("👧", "年轻舞伴多"),
    OLD_PARTNER("👴", "舞伴年龄偏成熟"),
    CLEAN("✨", "干净整洁"),
    GOOD_SERVICE("💁", "服务贴心"),
    NORMAL("😐", "普通"),
    CROWDED("👥", "人多拥挤"),
    WAITING("⏳", "排队太久"),
    QUIET("🪑", "人气冷清"),
    HIGH_COST("💰", "消费较高"),
    BAD_ENV("😕", "环境一般"),
    SERVICE_ISSUE("😡", "服务问题");

    private final String emoji;
    private final String label;

    ReactionCode(String emoji, String label) {
        this.emoji = emoji;
        this.label = label;
    }

    public String getEmoji() {
        return emoji;
    }

    public String getLabel() {
        return label;
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
