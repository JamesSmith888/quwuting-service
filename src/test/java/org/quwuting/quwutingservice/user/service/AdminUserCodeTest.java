package org.quwuting.quwutingservice.user.service;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.user.entity.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserCode} 纯函数单测（不开 Spring 上下文，2026-09-29）。
 * <p>
 * 盯的是「代号」这套辨认体系的四条硬契约——它们错了不会崩溃，只会<b>静默认错人</b>：
 * <ol>
 *   <li>格式稳定（补零到 5 位、超宽不截断）：代号会被写进工单与沟通记录，
 *       一旦随 id 位数变化而变长/变短，历史指代就对不上；</li>
 *   <li>搜索解析与展示<b>互为逆运算</b>：列表展示 {@code U#00472}，用户照着搜必须命中
 *       同一个 id（含大小写、有无 #、前导零三种写法）；</li>
 *   <li><b>纯数字不当代号</b>：否则「搜昵称 123」会变成「找 id=123 的用户」，
 *       把昵称搜索静默劫持；</li>
 *   <li>默认昵称判定与注册写入同值：判定漏了一种（比如空白串），
 *       默认态用户就会被当成有自定义昵称，列表主标题显示成「微信用户」——
 *       正是本次要消除的那一片无辨认力行。</li>
 * </ol>
 */
class AdminUserCodeTest {

    @Test
    void formatPadsToFixedWidthAndNeverTruncates() {
        assertEquals("U#00001", UserCode.format(1L));
        assertEquals("U#00472", UserCode.format(472L));
        assertEquals("U#99999", UserCode.format(99999L));
        // 超过宽度不截断——不能让已发出的老代号与新的对不上
        assertEquals("U#100000", UserCode.format(100000L));
    }

    @Test
    void parseAcceptsEverySpellingOfTheDisplayedCode() {
        assertEquals(472L, UserCode.parse("U#00472"));
        assertEquals(472L, UserCode.parse("u#00472"));
        assertEquals(472L, UserCode.parse("U#472"));
        assertEquals(472L, UserCode.parse("U472"));
        assertEquals(472L, UserCode.parse("  U#00472  "));
        assertEquals(100000L, UserCode.parse("U#100000"));
    }

    @Test
    void parseRejectsNonCodeKeywordsSoNicknameSearchStillWorks() {
        assertNull(UserCode.parse("472"), "纯数字必须留给昵称模糊搜索，不能被劫持成 id 查找");
        assertNull(UserCode.parse("微信用户"));
        assertNull(UserCode.parse("U#"));
        assertNull(UserCode.parse(""));
        assertNull(UserCode.parse(null));
        assertNull(UserCode.parse("X#472"));
    }

    @Test
    void parseIsInverseOfFormat() {
        for (long id : new long[]{1L, 9L, 472L, 99999L, 100000L, 1234567L}) {
            assertEquals(id, UserCode.parse(UserCode.format(id)),
                    "展示出来的代号必须能被原样搜回同一个用户");
        }
    }

    @Test
    void defaultNicknameJudgementCoversEveryUnsetShape() {
        assertTrue(UserCode.isDefaultNickname("微信用户"));
        assertTrue(UserCode.isDefaultNickname("  微信用户  "), "带空白也要判为默认态");
        assertTrue(UserCode.isDefaultNickname(null));
        assertTrue(UserCode.isDefaultNickname(""));
        assertTrue(UserCode.isDefaultNickname("   "));
        assertFalse(UserCode.isDefaultNickname("老张"));
    }

    @Test
    void customNicknameFlagMatchesTheEntity() {
        User anonymous = new User();
        anonymous.setNickname(UserCode.DEFAULT_NICKNAME);
        assertFalse(UserCode.isCustomNickname(anonymous));

        User named = new User();
        named.setNickname("老张");
        assertTrue(UserCode.isCustomNickname(named));

        User blank = new User();
        assertFalse(UserCode.isCustomNickname(blank));
    }
}
