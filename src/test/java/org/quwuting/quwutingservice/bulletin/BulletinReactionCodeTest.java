package org.quwuting.quwutingservice.bulletin;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.emoji.EmojiCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快讯表态字典一致性静态校验（2026-09-10 建立，2026-10-08 随「频道表态扩展层」改写；
 * 零依赖：不连库、不起 Spring）。
 * <p>
 * <b>为什么需要它</b>：{@link BulletinReactionCode} = 共享目录 {@link EmojiCatalog}
 * + 本域扩展层 {@link BulletinChannelEmoji}。两层之间、两端之间都有<b>纯文本层面的耦合</b>，
 * 编译与 HQL 语法检查都发现不了：目录项被删/改名 ⇒ {@code emojiOf/labelOf} 静默返回 null；
 * 目录日后收回某个表情 ⇒ 与扩展层重复、Picker 出「双胞胎」；新条目 code 手误 ⇒ 前后端
 * 对不上、表态写入被 1007 拒。每一条都只能等用户肉眼发现，故逐条锁成断言。
 * <p>
 * 跨仓（前端镜像 {@code BULLETIN_CHANNEL_EMOJIS} 逐项一致）由前端门禁
 * {@code npm run check:bulletin-reactions} 承担——本测试守后端侧，两边规则同一份
 * （47 号文档 §6.2）。
 */
class BulletinReactionCodeTest {

    /** 用户口径「至少再加一倍」的机器化表达（2026-10-08 时目录 100 项） */
    private static final int MIN_DICTIONARY_SIZE = 200;

    /** {@code qwt_bulletin_reactions.reaction_code varchar(30)}（V19）——ZWJ 序列的 code 最长，逼近这个宽度 */
    private static final int REACTION_CODE_COLUMN_WIDTH = 30;

    @Test
    void everyCodeResolvesToEmojiAndLabel() {
        for (String code : BulletinReactionCode.allCodes()) {
            assertTrue(EmojiCatalog.isValid(code) || BulletinChannelEmoji.find(code) != null,
                    "快讯表态 code 既不在共享目录也不在频道扩展层：" + code
                            + "——接口会下发 emoji=null / label=null");
            assertNotNull(BulletinReactionCode.emojiOf(code), "emoji 缺失: " + code);
            assertNotNull(BulletinReactionCode.labelOf(code), "label 缺失: " + code);
            assertFalse(BulletinReactionCode.emojiOf(code).isEmpty(), "emoji 为空串: " + code);
            assertFalse(BulletinReactionCode.labelOf(code).isEmpty(), "label 为空串: " + code);
        }
    }

    @Test
    void dictionaryHasNoDuplicateCode() {
        List<String> codes = BulletinReactionCode.allCodes();
        Set<String> unique = new HashSet<>(codes);
        assertEquals(codes.size(), unique.size(),
                "快讯表态字典存在重复 code——重复项会让 Picker 出现「双胞胎」格，"
                        + "且并列排序的字典序兜底失效");
    }

    @Test
    void dictionaryIsCatalogThenChannelLayer() {
        List<String> expected = new ArrayList<>(EmojiCatalog.allCodes());
        expected.addAll(BulletinChannelEmoji.allCodes());
        assertEquals(expected, BulletinReactionCode.allCodes(),
                "快讯表态字典 = 共享目录全量（声明序）+ 频道扩展层（声明序）；"
                        + "前端 constants/bulletin-reactions.ts 同口径派生");
    }

    @Test
    void channelLayerIsDisjointFromCatalog() {
        Set<String> catalogEmojis = new HashSet<>();
        for (EmojiCatalog entry : EmojiCatalog.values()) {
            catalogEmojis.add(normalize(entry.getEmoji()));
        }
        for (BulletinChannelEmoji entry : BulletinChannelEmoji.values()) {
            assertFalse(EmojiCatalog.isValid(entry.name()),
                    "频道扩展层 code 已存在于共享目录：" + entry.name()
                            + "——目录收回了这个表情，请把它从 BulletinChannelEmoji（及前端镜像）删掉");
            assertFalse(catalogEmojis.contains(normalize(entry.getEmoji())),
                    "频道扩展层 emoji 与共享目录重复：" + entry.getEmoji() + "（" + entry.name() + "）");
        }
        Set<String> channelEmojis = new HashSet<>();
        for (BulletinChannelEmoji entry : BulletinChannelEmoji.values()) {
            assertTrue(channelEmojis.add(normalize(entry.getEmoji())),
                    "频道扩展层内 emoji 重复：" + entry.getEmoji());
        }
    }

    @Test
    void channelCodeIsDerivedFromCodePoints() {
        for (BulletinChannelEmoji entry : BulletinChannelEmoji.values()) {
            assertEquals(deriveCode(entry.getEmoji()), entry.name(),
                    "code 必须由 emoji 码位确定性派生（EMOJI_<HEX>[_<HEX>…]，去掉 FE0F）：" + entry.getEmoji());
            assertFalse(entry.getLabel().isBlank(), "label 为空：" + entry.name());
            assertFalse(entry.getDescription().isBlank(), "description 为空：" + entry.name());
        }
    }

    /**
     * 2026-10-08 二改：原 {@code channelLayerHonoursComplianceAndRenderFloor}（合规排除 + Emoji 12.0 下限）已删——
     * 用户裁定快讯放开；排除判据借自门店域、渲染下限把设备能力缺口做成了字典准入（根因见
     * {@link BulletinChannelEmoji} 类注释），渲染能力改由前端逐台设备探测。「快讯字典 ⊇ TG 频道默认
     * reaction」由前端门禁 {@code check:bulletin-reactions} 持参照表断言（它同时校验两端逐项一致）。
     * 这里守的是本仓独有的物理约束：code 要放得进库列。
     */
    @Test
    void everyCodeFitsReactionColumn() {
        for (String code : BulletinReactionCode.allCodes()) {
            assertTrue(code.length() <= REACTION_CODE_COLUMN_WIDTH,
                    "code 超出 reaction_code 列宽 " + REACTION_CODE_COLUMN_WIDTH + "：" + code
                            + "（" + code.length() + " 字符）——写入会被截断或报错，先加迁移再收这个表情");
        }
    }

    @Test
    void dictionaryMeetsMinimumSize() {
        int size = BulletinReactionCode.allCodes().size();
        assertTrue(size >= MIN_DICTIONARY_SIZE,
                "快讯表态字典规模 " + size + " < " + MIN_DICTIONARY_SIZE
                        + "——别的域收缩共享目录会静默拉低快讯集合（2026-10-08 用户第四次反馈「太少」的成因），"
                        + "请在频道扩展层补齐，而不是改小这个下限");
    }

    @Test
    void invalidCodeIsRejectedWithoutThrowing() {
        assertFalse(BulletinReactionCode.isValid(null), "null 应判非法（不得走 valueOf 抛异常路径）");
        assertFalse(BulletinReactionCode.isValid(""), "空串应判非法");
        // 门店域业务 code 不得被快讯域接受（两域字典刻意不同，防「机车/收费偏高」类
        // 门店属性黑话从快讯接口写进来）
        assertFalse(BulletinReactionCode.isValid("HOT"), "门店业务 code 不属于快讯字典");
        assertTrue(BulletinReactionCode.isValid("EMOJI_1F600"), "共享目录内的 code 应被快讯字典接受");
        assertTrue(BulletinReactionCode.isValid("EMOJI_1F433"), "频道扩展层的 code 应被快讯字典接受");
        assertEquals("🐳", BulletinReactionCode.emojiOf("EMOJI_1F433"), "扩展层 emoji 走扩展层取值");
    }

    /** 比较用的规范形：去掉 FE0F 变体选择符（❤️ 与 ❤ 视为同一个表情）；按码位过滤，不写转义序列 */
    private static String normalize(String emoji) {
        StringBuilder sb = new StringBuilder();
        emoji.codePoints().filter(cp -> cp != 0xFE0F).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static String deriveCode(String emoji) {
        return "EMOJI_" + emoji.codePoints()
                .filter(cp -> cp != 0xFE0F)
                .mapToObj(cp -> Integer.toHexString(cp).toUpperCase())
                .collect(Collectors.joining("_"));
    }
}
