package org.quwuting.quwutingservice.venuereaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Reaction 字典（后台维护，不允许用户自由创建——避免色情/攻击/广告/竞对刷评价）。
 * <p>
 * 替代原"标签点赞"的轻量用户反馈：Telegram Reaction 式的表情化标签，一次点击 = 一次态度表达。
 * 正负向 Reaction 共存但不做"点赞/倒赞"二元对立（不提供 👍👎 成对选项，如"这店好/这店差"），
 * 而是用具体、中性的描述让用户自行判断（如 💋 成熟舞伴多，而不是"倒赞：舞伴年龄大"）——
 * 避免攻击性评价引发商家纠纷。单个语义具体的正向表达（如 👍 值得推荐、✌ 性价比高）允许，
 * 见 AGENTS.md「Reaction 快速反馈系统」章节。
 * <p>
 * emoji/label 是本字典的唯一来源（无管理后台 UI），与 {@link org.quwuting.quwutingservice.taginteraction.RatingDimensions}
 * 同模式：后续新增/调整 Reaction 只需修改此枚举，前端通过 stats 接口返回的 emoji/label 自动同步
 * （前端 Picker 静态字典 {@code constants/reactions.ts} 需同步镜像，见前端 AGENTS.md）。
 * <p>
 * 2026-08 扩版（表情越多越好）：在初版 9 项基础上按舞厅体验维度扩充至 16 项，
 * 正向（人气/氛围/音乐/推荐/消费/舞伴/环境/服务）→ 中性（普通）→ 负向（拥挤/排队/冷清/高消费/环境/服务）
 * 的展示序。
 * <p>
 * <b>2026-08-08 视觉升级（用户驱动，根因分析先行）</b>：现有 OpenMoji 表情在视觉
 * 同质化严重——16 项均为单色矢量图标，列表卡片 chip 与 Picker 弹窗缺乏品牌辨识度。
 * 新增 4 个 code（VIBRANT_PARTNER / SWEET_PARTNER / MATURE_PARTNER / VALUE）作为
 * 头部与新维度补充，并将现有 5 个 code（HOT / RECOMMEND / QUIET / NORMAL / 后续
 * 替换清单）的 src 切到统一风格的 Q 版卡通（128x128 RGBA，圆形遮罩透明背景）。
 * 新 4 个 code 中 VIBRANT / SWEET / MATURE 三个新维度**风格化替代**原 YOUNG_PARTNER /
 * OLD_PARTNER 维度——以"风格+年龄"组合（⭐/🌸/💋）取代"年龄标签+年龄标签"（👧/👴），
 * ① 去掉具体年龄数字（避免未成年人风险），② 配合前端去掉"15岁"等具体年龄文案。
 * 字典总数 16 → 18。
 * <p>
 * <b>2026-08-12 字典瘦身（18 → 14，用户驱动）</b>：
 * ① 删除 GOOD_VIBE / GOOD_MUSIC / NORMAL / CROWDED——"氛围/音乐"与"人气旺"重叠且
 * 用户无感（来舞厅的动机是舞伴不是音乐）；NORMAL 是零信息默认态；CROWDED 是 HOT
 * （人多=加分）的负面镜像，同一事实正负互搏（见前端 AGENTS.md「Reaction 快速反馈系统」）。
 * ② VALUE 语义纠偏：✌ 原按"性价比高"（20元/曲）作为 POSITIVE 收录——实为圈内黑话
 * 「剪刀手」（10 元场有舞伴临时加价至 20 元时比 V 手势），是负面标签。为不得罪人，
 * 不明示"剪刀手"字样（emoji 保留 ✌ 作圈内暗号），code 改名 PRICE_HIKE、极性改
 * NEGATIVE（退出热度公式，进负面信号单独计数）、label 改「舞伴加价」。
 * 历史数据迁移见 V15__reaction_dictionary_trim.sql。
 * <p>
 * <b>2026-08-12 晚 第二轮瘦身（14 → 10，用户驱动）</b>：
 * 删除 FAIR_PRICE（🍺 消费合理）/ WAITING（⏳ 排队太久）/ HIGH_COST（💰 消费较高）/
 * CLEAN（✨ 干净整洁）——价格/排队/清洁维度用户实际使用率低，字典收敛到
 * "人气/舞伴风格/服务/环境"等核心信号（来舞厅的动机是舞伴，辅助维度从简）。
 * 与第一轮不同：**历史数据物理清理**（V16__reaction_prune_codes.sql，DELETE
 * qwt_venue_reactions 中这 4 个 code 的记录，只删表情数据不动其他表）——
 * 避免前端长期携带"字典外 code 过滤"兼容逻辑（第二轮起不再保留孤儿 code）。
 * 已删 4 code 第一轮（GOOD_VIBE/GOOD_MUSIC/NORMAL/CROWDED）历史数据仍按 V15
 * 策略保留（前端过滤），两轮策略差异见 V16 migration 注释。
 * <p>
 * 字段约束：
 * <ul>
 *   <li>{@code reactionCode} 字段为 varchar(30)（见 V1 baseline），code 名长度均 &lt; 20，</li>
 *   <li>emoji 字符与 src（前端字典）一一对应——emoji 是后端契约也是图片加载失败时的兜底，</li>
 *   <li>极性是热度公式的唯一事实源（POSITIVE 计入公式，NEGATIVE 单独计数），前端展示全量。</li>
 * </ul>
 * <p>
 * 极性（2026-08 确立）：热度公式只统计 POSITIVE 项（正向反馈才是"热度"），NEGATIVE 项
 * 不计入公式、单独计数下发供详情页展示负面信号（见 VenueHeatService）——修复"被吐槽服务问题
 * 的店热度反而更高"的语义硬伤。极性是热度公式的唯一事实源；前端 Picker 展示全量 Reaction 不受影响。
 */
public enum ReactionCode {
    HOT("🔥", "人气旺", Polarity.POSITIVE),
    RECOMMEND("👍", "值得推荐", Polarity.POSITIVE),
    /**
     * 2026-08-12 由 VALUE（"✌ 性价比高"）改名纠偏：✌ 实为圈内黑话「剪刀手」——
     * 10 元场有舞伴临时加价至 20 元时比 V 手势，是负面标签而非性价比。为不得罪人
     * 不明示"剪刀手"字样（emoji 保留 ✌ 作圈内暗号，label 落中性行为描述「舞伴加价」），
     * 极性 POSITIVE → NEGATIVE（退出热度公式、进负面信号单独计数），历史数据
     * 经 V15 migration 重映射（VALUE → PRICE_HIKE）。
     */
    PRICE_HIKE("✌", "舞伴加价", Polarity.NEGATIVE),
    /**
     * 2026-08-08 新增（替代 YOUNG_PARTNER）：18+有活力的舞伴类型。
     * 风格化词汇替代"15岁"等具体年龄——Q 版虚拟头像 + 18+ 描述规避未成年人风险。
     */
    VIBRANT_PARTNER("⭐", "舞伴有活力", Polarity.POSITIVE),
    /**
     * 2026-08-08 新增（替代 YOUNG_PARTNER）：18+甜美风舞伴类型。
     * 风格化词汇替代"15岁"等具体年龄。
     */
    SWEET_PARTNER("🌸", "舞伴甜美风", Polarity.POSITIVE),
    /**
     * 2026-08-08 新增（替代 OLD_PARTNER）：35+成熟风舞伴类型。
     */
    MATURE_PARTNER("💋", "舞伴成熟风", Polarity.POSITIVE),
    GOOD_SERVICE("💁", "服务贴心", Polarity.POSITIVE),
    QUIET("🪑", "人气冷清", Polarity.NEGATIVE),
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

    /**
     * 正向反馈 code 名列表（仅 Polarity.POSITIVE，热度公式计入项）。
     * <p>
     * 极性的唯一事实源：热度计算（VenueHeatService / 列表排序 SQL 镜像 / 趋势聚合）
     * 全部经本方法取列表，禁止各调用方自行遍历枚举再各自 filter——否则新增/调整极性
     * 时遗漏某一处即产生口径漂移（2026-08-08 确立，与「公式文案后端下发」同族规则：
     * 权重/极性这类跨模块语义必须收敛到单一源头）。
     */
    public static List<String> positiveCodeNames() {
        return polarityCodeNames(Polarity.POSITIVE);
    }

    /** 负向反馈 code 名列表（仅 Polarity.NEGATIVE，热度公式不计入、单独计数展示） */
    public static List<String> negativeCodeNames() {
        return polarityCodeNames(Polarity.NEGATIVE);
    }

    private static List<String> polarityCodeNames(Polarity target) {
        List<String> names = new ArrayList<>();
        for (ReactionCode value : values()) {
            if (value.polarity == target) {
                names.add(value.name());
            }
        }
        return names;
    }
}
