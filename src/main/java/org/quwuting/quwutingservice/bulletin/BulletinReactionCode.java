package org.quwuting.quwutingservice.bulletin;

import org.quwuting.quwutingservice.emoji.EmojiCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * 行业快讯表态字典（2026-09-10，docs/agents/47-bulletins.md「快讯表态」）。
 * <p>
 * <b>为什么不是门店的 ReactionCode</b>：门店字典是 17 项<b>业务信号</b>（机车 / 龙女 /
 * 极品 / 收费偏高 / 场内禁烟…）加常见表情目录，语义全部锚在"这家店怎么样"上；快讯是
 * <b>行业情报</b>（哪家店什么时候开或关），把「场内禁烟」粘到一条停业快讯上语义不通。
 * 按 `docs/agents/08-reaction-system.md`「常见表情层与业务信号层分离」的既有分层，
 * 快讯表态属于<b>纯情感表达层</b>——故本字典是共享目录 {@link EmojiCatalog} 的一个
 * <b>域适配器</b>（同舞伴域 DancerTagCode 先例：只挑选、不复制条目、不自造 emoji），
 * 严禁把目录项抄成新的枚举值。
 * <p>
 * <b>为什么是"小集合"而不是全目录 100 项</b>：门店 Picker 有"展开全部"承载长列表；
 * 快讯是<b>信息流</b>，表态行紧贴内容、要求一瞥可辨（TG 频道的 reaction 也是固定小集合）。
 * 2026-09-11 用户"表情太少，多加常见表情" → 10 枚扩到 <b>16 枚</b>；同日用户再提
 * "还是太少" → 16 枚扩到 <b>32 枚</b>（正向 18 → 中性 8 → 负向 6；新增 😊 微笑 /
 * 😁 露齿笑 / 🥰 爱慕 / 😘 飞吻 / 🥳 庆祝 / 🙌 举手欢呼 / 💪 肌肉 / ✨ 闪亮 /
 * 😲 震惊 / 🤨 挑眉 / 😳 脸红 / 🤫 嘘声 / 😭 嚎啕大哭 / 😠 生气 / 😱 吓死 / 💔 心碎），
 * 32 恰为 Picker 4 列 × 8 行满格（无末行居中特例）。
 * <p>
 * <b>一致性</b>：前端镜像 {@code miniprogram/constants/bulletin-reactions.ts}——两端
 * 同步判据 = code / emoji / label 三元组逐项一致（description 是前端展示文案，非后端契约）；
 * 由零依赖静态测试 {@code BulletinReactionCodeTest} 守住"每个 code 必须存在于
 * EmojiCatalog 且 emoji/label 非空"（目录项被删 = 测试红，不是运行时 500）。
 */
public final class BulletinReactionCode {

    /**
     * 快讯表态集合（声明序 = Picker 展示序）：
     * <ul>
     *   <li>正向 18：👍 赞 / ❤️ 红心 / 🔥 火 / 🎉 派对彩带 / 👏 鼓掌 / 🙏 双手合十 /
     *       😂 笑哭 / 🤣 笑死 / 😍 花痴 / 🤩 星星眼 / 😊 微笑 / 😁 露齿笑 / 🥰 爱慕 /
     *       😘 飞吻 / 🥳 庆祝 / 🙌 举手欢呼 / 💪 肌肉 / ✨ 闪亮</li>
     *   <li>中性 8：😮 吃惊 / 🤔 思考 / 🤯 头爆炸 / 🥺 委屈 / 😲 震惊 / 🤨 挑眉 /
     *       😳 脸红 / 🤫 嘘声</li>
     *   <li>负向 6：😢 大哭 / 😡 发怒 / 😭 嚎啕大哭 / 😠 生气 / 😱 吓死 / 💔 心碎</li>
     * </ul>
     * 语义覆盖"这条情报对我的意义"：认可（赞/红心）、值得关注（火/鼓掌/星星眼/
     * 闪亮）、感谢告知（合十）、开业喜悦（彩带）、有趣好笑（笑哭/笑死）、喜爱（花痴/
     * 爱慕/飞吻）、轻松愉快（微笑/露齿笑/庆祝/举手欢呼/肌肉）、意外/震惊（吃惊/震惊/
     * 头爆炸/吓死）、待核实/存疑（思考/挑眉）、尴尬/害羞（脸红/嘘声）、遗憾（委屈）、
     * 坏消息（大哭/嚎啕大哭/发怒/生气/心碎）。快讯内容边界只写服务可得性，故负向情绪
     * 同样不指向具体店家（见 47 号文档内容边界红线）。
     */
    private static final List<String> CODES = List.of(
            "EMOJI_1F44D",
            "EMOJI_2764",
            "EMOJI_1F525",
            "EMOJI_1F389",
            "EMOJI_1F44F",
            "EMOJI_1F64F",
            "EMOJI_1F602",
            "EMOJI_1F923",
            "EMOJI_1F60D",
            "EMOJI_1F929",
            "EMOJI_1F60A",
            "EMOJI_1F601",
            "EMOJI_1F970",
            "EMOJI_1F618",
            "EMOJI_1F973",
            "EMOJI_1F64C",
            "EMOJI_1F4AA",
            "EMOJI_2728",
            "EMOJI_1F62E",
            "EMOJI_1F914",
            "EMOJI_1F92F",
            "EMOJI_1F97A",
            "EMOJI_1F632",
            "EMOJI_1F928",
            "EMOJI_1F633",
            "EMOJI_1F92B",
            "EMOJI_1F622",
            "EMOJI_1F621",
            "EMOJI_1F62D",
            "EMOJI_1F620",
            "EMOJI_1F631",
            "EMOJI_1F494"
    );

    private BulletinReactionCode() {
    }

    /** 全部合法 code（声明序 = Picker 展示序；不可变，返回副本防外部改写） */
    public static List<String> allCodes() {
        return new ArrayList<>(CODES);
    }

    /** 校验字符串是否为本域合法 code（toggle 写入前唯一入口；非法即 1007，不落库） */
    public static boolean isValid(String code) {
        return code != null && CODES.contains(code);
    }

    /** emoji 字符（后端下发契约；非法 code 返回 null，调用方须先 {@link #isValid}） */
    public static String emojiOf(String code) {
        return EmojiCatalog.emojiOf(code);
    }

    /** 中文短名（后端下发契约；非法 code 返回 null） */
    public static String labelOf(String code) {
        return EmojiCatalog.labelOf(code);
    }
}
