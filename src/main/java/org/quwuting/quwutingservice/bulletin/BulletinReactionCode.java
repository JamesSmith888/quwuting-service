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
 * <b>2026-09-19 全量放开（用户驱动：32 → 目录全量 100）</b>：集合曾"刻意收窄"（10 → 16 → 32，
 * 理由 = 快讯是信息流、表态行紧贴内容要求一瞥可辨），但收窄的代价由用户承担——门店列表
 * Picker 展开后 109 格（legacy 17 业务信号 + 目录去重 92），快讯只有 32 格，用户第三次
 * 反馈"表情太少"。<b>一瞥可辨是"默认可见子集"要解决的问题，不是"砍掉可选集合"的理由</b>：
 * TG 频道 reaction 用小集合是因为它根本没有展开入口，而快讯菜单已有「折叠 8 枚 / 展开全量」
 * 两级形态。故本域集合改为共享目录 {@link EmojiCatalog} <b>全量</b>（100 项，声明序 = 展示序），
 * 不再手工挑选 code——目录增删自动跟随，两端零漂移；快讯域无 legacy 业务信号（门店那 17 枚
 * 黑话/属性信号贴到一条停业快讯上语义不通），故本域上限即目录全集，与门店 109 同量级
 * （差的 9 枚全是门店专属业务信号，不属情感表达层，不补齐）。
 * <p>
 * <b>展示形态配套（前端）</b>：菜单折叠态 = 高频 8 枚显式集合（一瞥可辨由折叠态承担），
 * 展开态 100 枚改限高滚动（平铺 13 行会撑爆屏幕）——见 47 号文档 §6.2。
 * <p>
 * <b>一致性</b>：前端镜像 {@code miniprogram/constants/bulletin-reactions.ts}——2026-09-19
 * 起两端<b>同为目录全量</b>（前端 {@code EMOJI_CATALOG.map(code)} / 本类
 * {@code EmojiCatalog.allCodes()}），手工 code 列表这一同步点已消失，目录增删自动跟随。
 * emoji / label 直接取目录值（description 是前端展示文案，非后端契约）；由零依赖静态测试
 * {@code BulletinReactionCodeTest} 守住"与目录同集同序 + 每项 emoji/label 非空"
 * （目录项被删 → 两端同步收敛，不会下发 emoji=null 让前端渲染空白格）。
 */
public final class BulletinReactionCode {

    /**
     * 快讯表态集合 = 共享目录 {@link EmojiCatalog} <b>全量</b>（2026-09-19 起，100 项；
     * 声明序 = 展示序：表情与情感 → 人物 → 手势 → 爱心 → 庆祝/符号 → 花卉与酒水）。
     * <p>
     * 语义覆盖"这条情报对我的意义"：认可（赞/红心/各类心）、值得关注（火/闪亮/星星/
     * 宝石/王冠）、感谢告知（合十/拥抱/握手）、开业喜悦（彩带/纸屑/礼物/庆祝）、
     * 有趣好笑（笑哭/笑死/捂嘴笑/调皮脸）、喜爱（花痴/爱慕/飞吻/玫瑰）、轻松愉快
     * （微笑/露齿笑/举手欢呼/肌肉/墨镜脸）、意外/震惊（吃惊/震惊/头爆炸/吓死）、
     * 待核实/存疑（思考/挑眉）、尴尬/害羞（脸红/嘘声）、遗憾/难受（委屈/大哭/心碎/
     * 生病脸）、坏消息（嚎啕大哭/发怒/生气/踩）。快讯内容边界只写服务可得性，故负向
     * 情绪同样不指向具体店家（见 47 号文档内容边界红线）。
     * <p>
     * <b>不在此处手工增删 code</b>——要收窄请改回显式列表并同步前端
     * {@code constants/bulletin-reactions.ts} 与 47 号文档（历史教训：手工子集
     * 10 → 16 → 32 两次被反馈"太少"）。
     */
    private static final List<String> CODES = List.copyOf(EmojiCatalog.allCodes());

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
