package org.quwuting.quwutingservice.bulletin;

import java.util.regex.Pattern;

/**
 * 快讯内容摘要（2026-09-11 五稿，docs/agents/47-bulletins.md §7.7）。
 * <p>
 * <b>为什么需要它</b>：快讯域**没有标题字段**（用户拍板："快讯完全不需要标题"——
 * 正文即消息本体，气泡顶部不存在标题层）。但有两处"必须有个名字"的落点：
 * <ol>
 *   <li><b>分享卡片标题</b>（小程序菜单分享 / 详情页转发）：微信分享卡片必须有标题；</li>
 *   <li><b>共享表的 NOT NULL 兼容位</b>：快讯内容与公告<b>同表</b>
 *       （{@code qwt_announcements}，category='FLASH'），该表的 {@code title} 列
 *       是 {@code nullable = false} 且公告域必须使用——快讯写入时必须有值可落。</li>
 * </ol>
 * 本类即这两处的**唯一派生规则**：对 markdown 原文去掉标记后取前 {@value #MAX_CHARS} 字。
 * <p>
 * <b>它不是标题</b>（重要）：派生、只读、不参与编辑、不进任何写请求；接口把它命名为
 * {@code excerpt} 而非 {@code title}，就是为了不把"标题"这个概念带回来。
 * 读取侧**一律从 content 现算**（不读 {@code title} 列）——历史行里存的旧标题
 * 因此自然不可见，无需数据迁移。
 * <p>
 * <b>与前端的关系</b>：小程序分享标题另外按 {@code SHARE_TITLE_MAX_LEN = 30} 截断
 * （展示层保险，本类已保证 ≤ {@value #MAX_CHARS}）。
 */
public final class BulletinExcerpt {

    /** 摘要上限（字符数；共享列 varchar(100) 之下留足余量，分享卡片标题 30 字也够） */
    public static final int MAX_CHARS = 30;

    /** markdown 图片 {@code ![alt](url)}：整块丢弃（摘要里毫无信息量） */
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    /** markdown 链接 {@code [text](url)}：保留可见文字 */
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    /** 代码块 {@code ```...```}：整块丢弃 */
    private static final Pattern MD_CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");
    /** HTML / 内嵌 WXML 标签（含 {@code <video ...>}）：整块丢弃，只留文字 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    /** 行首 markdown 标记：标题 # / 引用 > / 无序列表 - * + / 有序列表 1. */
    private static final Pattern LINE_PREFIX = Pattern.compile("(?m)^\\s{0,3}(#{1,6}\\s*|>\\s?|[-*+]\\s+|\\d+\\.\\s+)");
    /** 行内强调与行内代码标记 */
    private static final Pattern INLINE_MARKS = Pattern.compile("[*_`~]");
    /** 连续空白折叠 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private BulletinExcerpt() {}

    /**
     * 派生内容摘要：去 markdown 标记 → 折叠空白 → 截断到 {@link #MAX_CHARS}（超出加省略号）。
     * 空/异常输入返回空串（调用方负责兜底，绝不返回 null）。
     */
    public static String of(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String s = content;
        s = MD_CODE_BLOCK.matcher(s).replaceAll(" ");
        s = MD_IMAGE.matcher(s).replaceAll(" ");
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = LINE_PREFIX.matcher(s).replaceAll("");
        s = INLINE_MARKS.matcher(s).replaceAll("");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        if (s.length() <= MAX_CHARS) {
            return s;
        }
        return s.substring(0, MAX_CHARS) + "…";
    }
}
