package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** token 与 scene 的格式契约（V42）：格式校验必须早于任何查库 / 外呼，所以每种畸形输入都要被拒。 */
class TimerShareTokensTest {

    @Test
    void generatedTokensAreWellFormedAndAlphanumeric() {
        for (int i = 0; i < 2000; i++) {
            String token = TimerShareTokens.generate();
            assertEquals(TimerSharePolicy.TOKEN_LENGTH, token.length());
            assertTrue(TimerShareTokens.isWellFormed(token), token);
        }
    }

    @Test
    void generatedTokensDoNotCollideInABulkSample() {
        // 62^10 空间里 5000 个样本撞号的概率 ~1e-11；撞了说明随机源坏了
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            assertTrue(seen.add(TimerShareTokens.generate()));
        }
    }

    @Test
    void generatedTokensUseTheWholeAlphabet() {
        // 大样本里三类字符都应出现，防止字母表被意外截断（例如只剩 hex）
        boolean digit = false;
        boolean upper = false;
        boolean lower = false;
        for (int i = 0; i < 500; i++) {
            for (char c : TimerShareTokens.generate().toCharArray()) {
                digit |= Character.isDigit(c);
                upper |= Character.isUpperCase(c);
                lower |= Character.isLowerCase(c);
            }
        }
        assertTrue(digit && upper && lower);
    }

    @Test
    void malformedTokensAreRejected() {
        assertFalse(TimerShareTokens.isWellFormed(null));
        assertFalse(TimerShareTokens.isWellFormed(""));
        assertFalse(TimerShareTokens.isWellFormed("short"));
        assertFalse(TimerShareTokens.isWellFormed("AbC123xyz01"), "多一位");
        assertFalse(TimerShareTokens.isWellFormed("AbC123xy0"), "少一位");
        assertFalse(TimerShareTokens.isWellFormed("AbC123xy_0"), "下划线不在字母表");
        assertFalse(TimerShareTokens.isWellFormed("AbC123xy 0"), "空格");
        assertFalse(TimerShareTokens.isWellFormed("AbC123xy0\n"), "结尾换行（正则 $ 的经典绕过）");
        assertFalse(TimerShareTokens.isWellFormed("AbC123xy='"), "SQL 片段");
        assertFalse(TimerShareTokens.isWellFormed("../../etc0"), "路径片段");
        assertFalse(TimerShareTokens.isWellFormed("AbC123xy中"), "非 ASCII");
    }

    @Test
    void sceneIsKeyEqualsTokenAndFitsWechatLimit() {
        String token = TimerShareTokens.generate();
        String scene = TimerShareTokens.scene(token);
        assertEquals("t=" + token, scene);
        assertTrue(scene.length() <= 32, "微信 getwxacodeunlimit scene 上限 32 字符");
    }
}
