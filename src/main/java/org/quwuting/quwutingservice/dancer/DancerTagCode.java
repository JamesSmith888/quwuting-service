package org.quwuting.quwutingservice.dancer;

import org.quwuting.quwutingservice.emoji.EmojiCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 舞伴标签字典（后台维护，不允许用户自由创建——与 ReactionCode / RatingDimensions 同模式）。
 * <p>
 * 标签来源 = 用户<b>认可行为</b>：认可舞伴时点按一枚表情 chip（2026-08-15 单票换票模型，
 * 每日至多一枚，语义与 venue Reaction 的每日一票一致），舞伴主页认可 chip 计数 =
 * 全部认可记录携带标签的聚合。
 * <p>
 * <b>legacy 7 项全部为正向信号（刻意设计）</b>：舞伴是真实个人，负向标签（如"态度差"）
 * 属于对个人的公开负面评价，存在诽谤/骚扰风险且难以核验——负面体验应走场所 feedback
 * 等既有通道，不在个人主页开放。legacy 只表达"认可的理由"，与产品定位（用户认可、
 * 非打赏/排行）一致。
 * <p>
 * <b>2026-08-24 常见表情全放开（用户决策，详见前端 AGENTS.md「舞伴认可」）</b>：
 * 本枚举 = <b>legacy 业务认可 code（7 项，保留全部历史数据与文案）+ 常见表情目录适配</b>。
 * 常见表情单一事实源 = {@link EmojiCatalog}，本枚举适配器（{@link #allCodes()} /
 * {@link #isValid} / {@link #emojiOf} / {@link #labelOf}）在 legacy 之上叠加目录。
 * 舞伴域约束（用户拍板「舞伴仅正向+中性」）：目录项仅收录
 * {@link EmojiCatalog.Polarity#POSITIVE} 与 {@link EmojiCatalog.Polarity#NEUTRAL}——
 * NEGATIVE 表情（😡👎💔 等）<b>不进入舞伴字典</b>（保留"不公开负面评价真人"的合规底线，
 * 负面体验走门店反馈等既有通道）。域内去重同门店：目录项 emoji（去除 VS16 后）与
 * legacy 撞车则剔除（legacy 🩰😊🎉🌟🤝😄⏰ 占用的 emoji 不再重复提供普通版）。
 * <p>
 * emoji/label 是本字典的唯一来源（无管理后台 UI），后续新增/调整只需修改
 * {@link EmojiCatalog} 或本枚举 legacy，前端通过接口返回的 emoji/label 自动同步
 * （前端静态字典 constants/dancer-tags.ts 需同步镜像，见前端 AGENTS.md「舞伴生态体系」章节）。
 */
public enum DancerTagCode {
    DANCE("🩰", "舞姿优秀"),
    EASY_TALK("😊", "容易交流"),
    GOOD_VIBE("🎉", "氛围感强"),
    BEGINNER_FRIENDLY("🌟", "新手友好"),
    PATIENT("🤝", "耐心带舞"),
    FUNNY("😄", "风趣幽默"),
    PUNCTUAL("⏰", "守时靠谱");

    private final String emoji;
    private final String label;

    DancerTagCode(String emoji, String label) {
        this.emoji = emoji;
        this.label = label;
    }

    public String getEmoji() {
        return emoji;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 舞伴域全部合法 code（声明序：legacy 7 在前 + 目录去重且非 NEGATIVE 在后）。
     * <b>唯一事实源</b>：isValid / 标签聚合全部经本方法取集合，禁止调用方自行遍历
     * values() 或遍历 EmojiCatalog 再各自 filter（2026-08-24 确立，同门店 allCodes 规则）。
     */
    public static List<String> allCodes() {
        List<String> codes = new ArrayList<>(values().length + EmojiCatalog.values().length);
        for (DancerTagCode value : values()) {
            codes.add(value.name());
        }
        codes.addAll(catalogCodes());
        return codes;
    }

    /** 校验字符串是否为合法的舞伴标签代码（legacy 或目录且未去重剔除），避免 valueOf 抛未受控异常 */
    public static boolean isValid(String tag) {
        if (tag == null) return false;
        for (DancerTagCode value : values()) {
            if (value.name().equals(tag)) return true;
        }
        return catalogCodes().contains(tag);
    }

    /** 该 code 的展示 emoji（legacy 或目录；非法 code 返回 null，调用方须先 isValid） */
    public static String emojiOf(String tag) {
        DancerTagCode code = valueOfSafe(tag);
        if (code != null) return code.getEmoji();
        return EmojiCatalog.emojiOf(tag);
    }

    /** 该 code 的 label（legacy 业务文案或目录 CLDR 中文名；非法 code 返回 null） */
    public static String labelOf(String tag) {
        DancerTagCode code = valueOfSafe(tag);
        if (code != null) return code.getLabel();
        return EmojiCatalog.labelOf(tag);
    }

    private static DancerTagCode valueOfSafe(String tag) {
        if (tag == null) return null;
        for (DancerTagCode value : values()) {
            if (value.name().equals(tag)) return value;
        }
        return null;
    }

    /**
     * 舞伴域目录 code 集合（缓存，惰性计算）：目录全部 code − NEGATIVE − 域内
     * emoji 去重剔除项（去重规则同门店：emoji 去除 VS16 后与 legacy 撞车即剔除）。
     */
    private static volatile List<String> catalogCodeCache = null;

    private static List<String> catalogCodes() {
        List<String> cached = catalogCodeCache;
        if (cached != null) return cached;
        Set<String> legacyEmojis = new HashSet<>();
        for (DancerTagCode value : values()) {
            legacyEmojis.add(stripVariationSelector(value.getEmoji()));
        }
        List<String> codes = new ArrayList<>();
        for (EmojiCatalog catalog : EmojiCatalog.values()) {
            if (catalog.getPolarity() == EmojiCatalog.Polarity.NEGATIVE) continue; // 舞伴仅正+中性
            if (legacyEmojis.contains(stripVariationSelector(catalog.getEmoji()))) continue; // 域内去重
            codes.add(catalog.name());
        }
        catalogCodeCache = List.copyOf(codes);
        return catalogCodeCache;
    }

    private static String stripVariationSelector(String emoji) {
        if (emoji == null || emoji.isEmpty()) return emoji;
        return emoji.replace("\uFE0F", "");
    }
}
