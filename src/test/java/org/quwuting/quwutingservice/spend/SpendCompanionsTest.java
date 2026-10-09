package org.quwuting.quwutingservice.spend;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.spend.dto.SpendCompanionItem;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账目「一同计时的人」快照的结构单测（2026-10-09，V47）。
 * <p>
 * 钉住的是总原则：<b>同行者是元数据，永远不能拒掉一笔账</b>——任何输入都产出可落库的结果，从不抛；
 * 以及两个语义：null = 「客户端没带」（保留旧值）与空数组 = 「明确置空」必须可区分。
 */
class SpendCompanionsTest {

    private static SpendCompanionItem host(String nickname, String avatarUrl) {
        return new SpendCompanionItem(nickname, avatarUrl, "HOST");
    }

    @Test
    void nullStaysNullSoCallersCanTellNotSentFromExplicitlyEmpty() {
        assertNull(SpendCompanions.normalize(null), "null = 客户端没带 → 保留库里已有值");
        assertEquals(List.of(), SpendCompanions.normalize(List.of()), "空数组 = 明确置空");
        assertNull(SpendCompanions.serialize(List.of()), "空 → 列 NULL");
        assertNull(SpendCompanions.serialize(null));
    }

    @Test
    void relationIsCaseInsensitiveAndUnknownRelationDropsTheItemNotTheEntry() {
        List<SpendCompanionItem> out = SpendCompanions.normalize(List.of(
                new SpendCompanionItem("a", null, " joiner "),
                new SpendCompanionItem("b", null, "FRIEND"),
                new SpendCompanionItem("c", null, null),
                host("d", null)));

        assertEquals(2, out.size(), "关系认不出的项整项丢弃（关系是唯一的结构性字段，不猜）");
        assertEquals("JOINER", out.get(0).relation(), "宽容大小写与首尾空白，落库用规范枚举名");
        assertEquals("HOST", out.get(1).relation());
    }

    @Test
    void nullItemsAreSkippedAndCountIsCapped() {
        List<SpendCompanionItem> raw = new ArrayList<>();
        raw.add(null);
        for (int i = 0; i < SpendEntryLimits.COMPANION_MAX_COUNT + 3; i++) {
            raw.add(host("n" + i, null));
        }

        List<SpendCompanionItem> out = SpendCompanions.normalize(raw);

        assertEquals(SpendEntryLimits.COMPANION_MAX_COUNT, out.size());
        assertEquals("n0", out.get(0).nickname(), "先到先得：保留靠前的人，丢弃超出上限的尾部");
    }

    @Test
    void nicknameIsTrimmedStrippedOfControlCharsAndTruncatedWithoutSplittingAnEmoji() {
        String emoji = "😀"; // 一个代理对（2 个 char）
        String longName = "x".repeat(SpendEntryLimits.COMPANION_NICKNAME_MAX_LENGTH - 1) + emoji;

        List<SpendCompanionItem> out = SpendCompanions.normalize(List.of(
                host("  小\n李\t ", null),
                host(longName, null),
                host("   ", null)));

        assertEquals("小李", out.get(0).nickname(), "控制字符直接剔除、首尾空白去掉");
        String truncated = out.get(1).nickname();
        assertEquals(SpendEntryLimits.COMPANION_NICKNAME_MAX_LENGTH - 1, truncated.length(),
                "上限处正好落在代理对中间 → 整个 emoji 退掉，不留半个");
        assertTrue(!Character.isHighSurrogate(truncated.charAt(truncated.length() - 1)));
        assertNull(out.get(2).nickname(), "全空白 → null（客户端兜底「微信用户」）");
    }

    @Test
    void avatarUrlMustBeHttpsAndWithinTheLimitElseItBecomesNull() {
        String tooLong = "https://cdn.example/" + "a".repeat(SpendEntryLimits.COMPANION_AVATAR_URL_MAX_LENGTH);

        List<SpendCompanionItem> out = SpendCompanions.normalize(List.of(
                host("a", "https://cdn.example/a.png"),
                host("b", "HTTPS://cdn.example/b.png"),
                host("c", "http://cdn.example/c.png"),
                host("d", "javascript:alert(1)"),
                host("e", tooLong),
                host("f", "  ")));

        assertEquals("https://cdn.example/a.png", out.get(0).avatarUrl());
        assertEquals("HTTPS://cdn.example/b.png", out.get(1).avatarUrl(), "scheme 大小写不敏感");
        assertNull(out.get(2).avatarUrl(), "http 不收（小程序 image 要求 https 域名）");
        assertNull(out.get(3).avatarUrl());
        assertNull(out.get(4).avatarUrl(), "超长不截断——截断后的 URL 必然加载失败，明确置空");
        assertNull(out.get(5).avatarUrl());
        assertEquals("e", out.get(4).nickname(), "头像被置空不影响同一项的昵称与这笔账");
    }

    @Test
    void serializeRoundTripsThroughParse() {
        List<SpendCompanionItem> in = SpendCompanions.normalize(List.of(
                host("王姐", "https://cdn.example/w.png"),
                new SpendCompanionItem(null, null, "JOINER")));

        List<SpendCompanionItem> back = SpendCompanions.parse(SpendCompanions.serialize(in));

        assertEquals(in, back);
    }

    @Test
    void serializeNeverExceedsTheColumnAndNeverThrowsEvenForQuoteHeavyPayloads() {
        // 引号转义膨胀：每个昵称 64 个引号 → JSON 里变成 128 个字符；6 人 + 满长头像远超 4096
        String quotes = "\"".repeat(SpendEntryLimits.COMPANION_NICKNAME_MAX_LENGTH);
        String url = "https://cdn.example/" + "u".repeat(SpendEntryLimits.COMPANION_AVATAR_URL_MAX_LENGTH - 20);
        List<SpendCompanionItem> raw = new ArrayList<>();
        for (int i = 0; i < SpendEntryLimits.COMPANION_MAX_COUNT; i++) {
            raw.add(host(quotes, url));
        }

        String json = SpendCompanions.serialize(SpendCompanions.normalize(raw));

        assertNotNull(json);
        assertTrue(json.length() <= SpendEntryLimits.COMPANIONS_JSON_MAX_LENGTH,
                "放不下就从尾部丢人——元数据绝不让落库异常回滚整批账目（SpendEntryLimits 毒丸教训）");
        assertTrue(SpendCompanions.parse(json).size() >= 1, "至少保住靠前的一位");
    }

    @Test
    void parseIsLenientAboutCorruptJsonAndRenormalizesWhatItReads() {
        assertEquals(List.of(), SpendCompanions.parse(null));
        assertEquals(List.of(), SpendCompanions.parse("   "));
        assertEquals(List.of(), SpendCompanions.parse("{not json"), "坏数据不放大成 500，也不拖垮整页账目拉取");
        assertEquals(List.of(), SpendCompanions.parse("{\"nickname\":\"x\"}"), "形状不对（对象而非数组）同样当作空");

        List<SpendCompanionItem> parsed = SpendCompanions.parse(
                "[{\"nickname\":\"ok\",\"avatarUrl\":null,\"relation\":\"host\"},"
                        + "{\"nickname\":\"bad\",\"avatarUrl\":null,\"relation\":\"???\"}]");
        assertEquals(1, parsed.size(), "手改库产生的非法项在读侧同样被丢弃，不下发");
        assertEquals("HOST", parsed.get(0).relation());
    }
}
