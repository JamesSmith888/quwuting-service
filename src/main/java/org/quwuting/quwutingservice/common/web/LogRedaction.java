package org.quwuting.quwutingservice.common.web;

import java.util.Locale;
import java.util.Set;

/**
 * 请求日志脱敏（2026-10-01，规则见 docs/agents/13-code-standards.md「日志与隐私」）。
 * <p>
 * 根因：请求耗时日志按 INFO 原样记录 query string，带坐标的列表 / 附近门店 / 逆地理请求
 * 因此把<b>用户精确经纬度</b>与同一 rid 下的 uid 一起写进日志——与「用户坐标不出端、
 * 不落库」的隐私红线（52 号文档）直接冲突，而日志恰恰是保留最久、访问控制最弱的存储。
 * <p>
 * 策略（参数名大小写不敏感，按「参数是什么」而不是「哪个接口」分类，新接口自动继承）：
 * <ul>
 *   <li>坐标类：保留 2 位小数（≈1km，排障足够定位城市/片区，不足以定位到人）；</li>
 *   <li>凭据类：整体替换为 {@code ***}。</li>
 * </ul>
 */
public final class LogRedaction {

    private static final Set<String> COORDINATE_PARAMS = Set.of("lat", "lng", "latitude", "longitude");
    private static final Set<String> SECRET_PARAMS = Set.of(
            "token", "access_token", "code", "password", "secret", "session_key", "ticket");
    private static final String MASK = "***";
    private static final int COORDINATE_DECIMALS = 2;

    private LogRedaction() {
    }

    /** 脱敏 query string（null / 空原样返回）；参数顺序与未命中参数原样保留 */
    public static String redactQuery(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        String[] pairs = query.split("&", -1);
        StringBuilder out = new StringBuilder(query.length());
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                out.append('&');
            }
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.append(pair);
                continue;
            }
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            String key = name.toLowerCase(Locale.ROOT);
            out.append(name).append('=');
            if (SECRET_PARAMS.contains(key)) {
                out.append(MASK);
            } else if (COORDINATE_PARAMS.contains(key)) {
                out.append(coarsenCoordinate(value));
            } else {
                out.append(value);
            }
        }
        return out.toString();
    }

    private static String coarsenCoordinate(String value) {
        int dot = value.indexOf('.');
        if (dot < 0 || value.length() - dot - 1 <= COORDINATE_DECIMALS) {
            return value;
        }
        return value.substring(0, dot + 1 + COORDINATE_DECIMALS);
    }
}
