package org.quwuting.quwutingservice.bulletin;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.emoji.EmojiCatalog;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快讯表态字典一致性静态校验（2026-09-10，零依赖：不连库、不起 Spring）。
 * <p>
 * <b>为什么需要它</b>：{@link BulletinReactionCode} 是共享目录 {@link EmojiCatalog}
 * 的<b>域适配器</b>（只挑选、不复制条目）——这带来一个跨文件耦合：目录项被删除或改名时，
 * 快讯域仍持有那个 code 字面量，{@code emojiOf/labelOf} 会静默返回 {@code null}，
 * 接口下发 {@code emoji: null} 前端渲染成空白格，且<b>编译与 HQL 语法检查都发现不了</b>。
 * 同门店别名域「注释改了谓词没改」的教训（见 {@code VenueAliasMatchMirrorTest}）：
 * 纯文本层面的镜像引用必须有一条断言锁住，否则只能等用户肉眼发现。
 * <p>
 * <b>判据</b>：增删本域表态项时，本测试与前端字典
 * {@code miniprogram/constants/bulletin-reactions.ts} 的 code 列表必须同改；
 * 本测试守"后端侧不漂移"（目录存在性 + 完整性），前端侧守"picker 渲染不空白"
 * （字典缺失项被过滤降级）。
 */
class BulletinReactionCodeTest {

    @Test
    void everyCodeExistsInEmojiCatalog() {
        for (String code : BulletinReactionCode.allCodes()) {
            assertTrue(EmojiCatalog.isValid(code),
                    "快讯表态 code 不在共享 emoji 目录中（目录项被删/改名？）：" + code
                            + "——不是补一个孤儿 code 进目录，就是把它从 BulletinReactionCode 的集合里去掉，"
                            + "否则接口会下发 emoji=null / label=null");
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

    /**
     * 集合规模断言：既是产品契约（TG 式信息流的表态集合刻意收窄，不随目录膨胀——
     * 快讯的 Picker 没有「展开全部」承载长列表），也是"误删一项"的哨兵。
     * 2026-09-11 用户"表情太少" → 10 → 16 → 同日再提"还是太少" → 32（4 列 × 8 行满格）。
     * 变更集合时同步改本断言 + 前端字典 + 47 号文档，属预期维护成本。
     */
    @Test
    void dictionarySizeIsPinned() {
        assertEquals(32, BulletinReactionCode.allCodes().size(),
                "快讯表态字典为 32 项（4 列 × 8 行满格）；"
                        + "增删请同步前端 constants/bulletin-reactions.ts 与 docs/agents/47-bulletins.md");
    }

    @Test
    void invalidCodeIsRejectedWithoutThrowing() {
        assertFalse(BulletinReactionCode.isValid(null), "null 应判非法（不得走 valueOf 抛异常路径）");
        assertFalse(BulletinReactionCode.isValid(""), "空串应判非法");
        // 门店域业务 code 不得被快讯域接受（两域字典刻意不同，防「机车/收费偏高」类
        // 门店属性黑话从快讯接口写进来）
        assertFalse(BulletinReactionCode.isValid("HOT"), "门店业务 code 不属于快讯字典");
        assertFalse(BulletinReactionCode.isValid("EMOJI_1F600"), "目录里存在但不在快讯集合内的 code 应被拒");
    }
}
