package org.quwuting.quwutingservice.common.text;

/**
 * 用户自由文本的统一清洗工具（入库前的最后防线）。
 * <p>
 * 职责（需求：用户上报的文本留言防注入）：
 * <ul>
 *   <li><b>控制字符剥离</b>：去除 C0/C1 控制字符（\u0000-\u001F、\u007F-\u009F），
 *       保留换行（\n）——控制字符可干扰日志、下游渲染与协议层，是文本注入攻击的常见载体</li>
 *   <li><b>首尾空白</b>：trim</li>
 *   <li><b>长度截断</b>：超长截断到上限（与 DTO @Size 校验双保险——DTO 校验拦非法请求，
 *       此处兜底防御绕过校验的直接落库路径）</li>
 * </ul>
 * 防注入体系的分层约定（全链路）：
 * <ul>
 *   <li><b>SQL 注入</b>：JPA 参数化查询天然免疫（本项目全部查询走 JPA/JPQL/命名参数，无字符串拼接 SQL）</li>
 *   <li><b>XSS</b>：小程序端全部经 {@code <text>} 文本节点渲染（天然转义）；管理端页面同为
 *       小程序原生页面。约定：任何未来新增的 web/富文本消费端必须对文本做 HTML 转义或仅用文本节点渲染</li>
 *   <li><b>协议/日志污染</b>：本工具剥离控制字符，保证入库文本不携带可干扰下游的字节</li>
 * </ul>
 * 本工具为无状态单例，线程安全；禁止在工具内做业务规则（长度上限由调用方按字段语义传入）。
 */
public final class TextSanitizer {

    /** 项目统一的用户自由文本长度上限（与各 DTO @Size(max=500) 对齐，双保险） */
    public static final int MAX_TEXT_LENGTH = 500;

    private static final int[] C0_RANGE = {0x0000, 0x001F};
    private static final int[] C1_RANGE = {0x007F, 0x009F};
    private static final char LF = '\n';

    private TextSanitizer() {}

    /**
     * 清洗用户文本：null → 空串；剥离除换行外的控制字符；trim；按 {@link #MAX_TEXT_LENGTH} 截断。
     */
    public static String sanitize(String input) {
        return sanitize(input, MAX_TEXT_LENGTH);
    }

    /**
     * 清洗用户文本（指定长度上限）。
     * <p>
     * 实现说明：单次 StringBuilder 扫描完成控制字符剥离 + 截断（O(n)），
     * 避免 replaceAll 正则的多遍扫描与 GC 压力（上报文本长度有界，性能非热点，但
     * 单遍实现是既简单又正确的标准形态）。
     *
     * @param maxLength 截断上限（&gt;= 0；为 0 时结果为全空串）
     */
    public static String sanitize(String input, int maxLength) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length() && sb.length() < maxLength; i++) {
            char c = input.charAt(i);
            if (c == LF || !isControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** C0（0x00-0x1F，含 \n 由调用方显式放行）与 C1（0x7F-0x9F）控制字符判定 */
    private static boolean isControl(char c) {
        return (c >= C0_RANGE[0] && c <= C0_RANGE[1]) || (c >= C1_RANGE[0] && c <= C1_RANGE[1]);
    }
}
