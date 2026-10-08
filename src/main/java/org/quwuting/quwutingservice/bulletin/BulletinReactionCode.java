package org.quwuting.quwutingservice.bulletin;

import org.quwuting.quwutingservice.emoji.EmojiCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 行业快讯表态字典（2026-09-10，docs/agents/47-bulletins.md「快讯表态」）。
 * <p>
 * <b>为什么不是门店的 ReactionCode</b>：门店字典是 17 项<b>业务信号</b>（机车 / 龙女 /
 * 极品 / 收费偏高 / 场内禁烟…）加常见表情目录，语义全部锚在"这家店怎么样"上；快讯是
 * <b>行业情报</b>（哪家店什么时候开或关），把「场内禁烟」粘到一条停业快讯上语义不通。
 * 按 `docs/agents/08-reaction-system.md`「常见表情层与业务信号层分离」的既有分层，
 * 快讯表态属于<b>纯情感表达层</b>——字典 = 共享目录 {@link EmojiCatalog}（各域通用层）
 * + 本域「频道表态扩展层」{@link BulletinChannelEmoji}（只属于快讯的词汇），与门店域
 * 「legacy 业务信号层 + 共享目录」同构：共享层只放各域都成立的部分，域独有的放域内。
 * 严禁把目录项抄成扩展层条目（两层不相交，测试断言）。
 * <p>
 * <b>演进</b>：10 → 16 → 32（手工子集，两次被反馈"太少"）→ 2026-09-19 目录全量 100 →
 * <b>2026-10-08 目录 + 频道扩展层（≥ 200）</b>。最后一步的根因见 {@link #CODES} 注释：
 * 「= 目录全量」让快讯集合的准入判据借用了门店域的去噪判据。
 * <p>
 * <b>展示形态配套（前端）</b>：菜单折叠态 = 高频 8 枚显式集合（一瞥可辨由折叠态承担），
 * 展开态全量限高滚动——见 47 号文档 §6.2。
 * <p>
 * <b>一致性</b>：前端镜像 {@code miniprogram/constants/bulletin-reactions.ts}——共享目录部分
 * 两端同为目录派生（零同步点）；扩展层两端各有一份声明（{@link BulletinChannelEmoji} ↔
 * 前端 {@code BULLETIN_CHANNEL_EMOJIS}），逐项同序同值由前端门禁
 * {@code npm run check:bulletin-reactions} 跨仓比对；本仓 {@code BulletinReactionCodeTest}
 * 守住"不相交 / code 由码位派生且放得进库列 / 总规模 ≥ 200 / emoji·label 非空"；「⊇ TG 频道默认 reaction」
 * 由前端门禁持参照表断言（2026-10-08 二改删掉了扩展层的合规排除与 Emoji 12.0 下限，见
 * {@link BulletinChannelEmoji} 类注释）。
 */
public final class BulletinReactionCode {

    /**
     * 快讯表态集合 = 共享目录 {@link EmojiCatalog} <b>全量</b>（各域通用的常见情绪层）
     * + 快讯域「频道表态扩展层」{@link BulletinChannelEmoji}（2026-10-08 起；声明序 = 展示序：
     * 先共享目录、后扩展层）。
     * <p>
     * <b>2026-10-08 为什么不再「= 目录全量」</b>：目录的准入判据属于门店域（「能否在舞厅场景
     * 想象出点击表达」，09-09 据此删掉动物 / 食物 / 天气 / 场景外符号），快讯直接取目录全量 ⇒
     * 快讯能用哪些表情由<b>别的域的去噪</b>决定，规模被静默收缩（用户第四次反馈「表情太少」，
     * 而 Telegram 频道最常见的 🐳🍓🌚🏆🎃 恰好都在被删之列）。现由快讯域自己的扩展层承担
     * 频道语境独有的词汇，准入判据见 {@link BulletinChannelEmoji} 类注释。
     * <p>
     * 语义覆盖"这条情报对我的意义"：认可（赞/红心/各类心）、值得关注（火/闪亮/星星/
     * 宝石/王冠/奖杯）、感谢告知（合十/拥抱/握手/鞠躬）、开业喜悦（彩带/纸屑/香槟/烟花）、
     * 有趣好笑（笑哭/笑死/捂嘴笑/调皮脸）、围观（双眼/吃瓜/爆米花）、意外/震惊（吃惊/震惊/
     * 头爆炸/吓死）、待核实/存疑（思考/挑眉/端详/问号）、遗憾/难受（委屈/大哭/心碎/失望）。
     * 快讯内容边界只写服务可得性，故负向情绪同样不指向具体店家（见 47 号文档内容边界红线）。
     * <p>
     * 两层不相交（{@code BulletinReactionCodeTest} 断言），这里按声明序直接拼接即可。
     */
    private static final List<String> CODES = composeCodes();

    /** {@link #isValid} 的 O(1) 判定集合（与 {@link #CODES} 同源） */
    private static final Set<String> CODE_SET = Set.copyOf(CODES);

    private BulletinReactionCode() {
    }

    private static List<String> composeCodes() {
        List<String> codes = new ArrayList<>(EmojiCatalog.allCodes());
        codes.addAll(BulletinChannelEmoji.allCodes());
        return List.copyOf(codes);
    }

    /** 全部合法 code（声明序 = Picker 展示序；不可变，返回副本防外部改写） */
    public static List<String> allCodes() {
        return new ArrayList<>(CODES);
    }

    /** 校验字符串是否为本域合法 code（toggle 写入前唯一入口；非法即 1007，不落库） */
    public static boolean isValid(String code) {
        return code != null && CODE_SET.contains(code);
    }

    /** emoji 字符（后端下发契约；非法 code 返回 null，调用方须先 {@link #isValid}） */
    public static String emojiOf(String code) {
        BulletinChannelEmoji channel = BulletinChannelEmoji.find(code);
        return channel != null ? channel.getEmoji() : EmojiCatalog.emojiOf(code);
    }

    /** 中文短名（后端下发契约；非法 code 返回 null） */
    public static String labelOf(String code) {
        BulletinChannelEmoji channel = BulletinChannelEmoji.find(code);
        return channel != null ? channel.getLabel() : EmojiCatalog.labelOf(code);
    }
}
