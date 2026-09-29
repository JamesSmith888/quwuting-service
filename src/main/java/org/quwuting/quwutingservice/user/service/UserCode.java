package org.quwuting.quwutingservice.user.service;

import org.quwuting.quwutingservice.user.entity.User;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户代号（{@code U#00472}）与「默认昵称」判定的<b>唯一权威</b>（2026-09-29）。
 * <p>
 * <b>为什么需要代号</b>：平台 98% 的用户从未改过昵称（生产实测 873 个存活用户里
 * 857 个昵称停在注册默认值），昵称因此零辨认力——管理端列表一行行「微信用户」
 * 无法区分谁是谁。代号由 {@code id} 派生、<b>终身不变</b>，是运营在沟通/工单/
 * 排查中指代一个账号的唯一稳定凭据。它<b>零迁移</b>：不落库，纯派生。
 * <p>
 * <b>为什么要抽成类而不是散在 AdminUserService 里的 private 方法</b>：代号同时被
 * 列表展示、详情展示、搜索解析三处消费，默认昵称常量还被注册链路写入
 * （{@code AuthService}）——散落必然漂移（改了注册默认值却忘了改判定值，老用户
 * 的默认昵称就会被误判成自定义昵称）。集中到这里后：
 * <ul>
 *   <li>注册写入用 {@link #DEFAULT_NICKNAME}——<b>改默认值只改这一处</b>；</li>
 *   <li>列表/详情展示用 {@link #format}；搜索用 {@link #parse}；</li>
 *   <li>纯函数、无依赖 ⇒ {@code AdminUserCodeTest} 可以不开 Spring 上下文直接测。</li>
 * </ul>
 * <p>
 * <b>边界</b>：代号是<b>内部辨认手段</b>，只在管理端（requireAdmin）下发，
 * 与「不建公开用户主页」「openId 绝不下发」的审核红线一致——它不含任何敏感信息，
 * 只是 id 的可念出形式。
 */
public final class UserCode {

    private UserCode() {
    }

    /**
     * 注册默认昵称：<b>唯一写入源 = {@code AuthService} 新用户入库那一行</b>，
     * 判定源 = {@link #isDefaultNickname}。两处必须同值，故常量放这里。
     */
    public static final String DEFAULT_NICKNAME = "微信用户";

    /** 代号前缀（列表展示 / 搜索输入 / 口述沟通同一形式） */
    public static final String PREFIX = "U#";

    /** 代号宽度（补零到 5 位——对齐当前用户量级，肉眼易读） */
    private static final int WIDTH = 5;

    /**
     * 搜索正则：{@code U#00472} / {@code u#472} / {@code U472} 均可；
     * <b>必须以 U 开头</b>——纯数字仍按昵称模糊搜索，避免「搜昵称 123」
     * 被误判成「找 id=123 的用户」。前导零可选（00472 与 472 等价）。
     */
    private static final Pattern PATTERN = Pattern.compile("^[Uu]#?0*(\\d{1,18})$");

    /**
     * 用户 id → 代号（{@code U#00472}）。
     * id 超过宽度时<b>不截断</b>（自然变长，不割裂已发出的老代号）。
     */
    public static String format(Long userId) {
        if (userId == null) {
            return "";
        }
        String raw = Long.toString(userId);
        if (raw.length() >= WIDTH) {
            return PREFIX + raw;
        }
        return PREFIX + "0".repeat(WIDTH - raw.length()) + raw;
    }

    /** 解析代号 keyword（{@code U#00472} → 472）；非代号形式返回 null */
    public static Long parse(String keyword) {
        if (keyword == null) {
            return null;
        }
        Matcher matcher = PATTERN.matcher(keyword.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            long id = Long.parseLong(matcher.group(1));
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null; // 超长数字串（>18 位已被正则挡住，这里是双保险）
        }
    }

    /** 昵称是否仍是注册默认值（空 / 空白 / 等于 {@link #DEFAULT_NICKNAME}） */
    public static boolean isDefaultNickname(String nickname) {
        return nickname == null || nickname.isBlank() || DEFAULT_NICKNAME.equals(nickname.trim());
    }

    /** 昵称是否为用户自己起的（{@link #isDefaultNickname} 的取反，语义化命名） */
    public static boolean isCustomNickname(User user) {
        return !isDefaultNickname(user == null ? null : user.getNickname());
    }
}
