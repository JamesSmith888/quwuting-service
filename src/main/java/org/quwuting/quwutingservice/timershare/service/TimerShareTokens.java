package org.quwuting.quwutingservice.timershare.service;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 分享 token 与小程序码 scene 的<b>单点</b>（2026-10-07，V42）。
 * <p>
 * token 是 bearer 凭据——知道它就能加入——所以生成必须用 {@link SecureRandom}（不是
 * {@code java.util.Random}），格式校验必须在<b>任何</b>查库 / 外呼微信之前做（随便拼一个串就能
 * 触发 DB 查询或微信接口调用，是最便宜的放大攻击）。
 * <p>
 * scene 的解析（{@code t=<token>}）由前端 {@code pages/timer-join} 负责，两端格式由跨仓门禁
 * {@code check:timer-share} 比对 {@link TimerSharePolicy#SCENE_KEY} 与 {@link TimerSharePolicy#TOKEN_LENGTH}。
 */
public final class TimerShareTokens {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private static final Pattern WELL_FORMED =
            Pattern.compile("^[0-9A-Za-z]{" + TimerSharePolicy.TOKEN_LENGTH + "}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private TimerShareTokens() {
    }

    /** 生成一个新 token（{@code SecureRandom.nextInt(bound)} 是无偏的，不存在取模偏差） */
    public static String generate() {
        char[] chars = new char[TimerSharePolicy.TOKEN_LENGTH];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }

    /** 格式是否合法（长度 + 字符集）。null 一律不合法 */
    public static boolean isWellFormed(String token) {
        return token != null && WELL_FORMED.matcher(token).matches();
    }

    /** 小程序码 scene：{@code t=<token>}（调用方须已校验 token 格式） */
    public static String scene(String token) {
        return TimerSharePolicy.SCENE_KEY + "=" + token;
    }
}
