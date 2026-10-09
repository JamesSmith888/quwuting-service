package org.quwuting.quwutingservice.spend;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.spend.dto.SpendCompanionItem;
import org.quwuting.quwutingservice.spend.enums.SpendCompanionRelation;
import org.quwuting.quwutingservice.spend.enums.WireEnums;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 账目「一同计时的人」快照的结构单点（2026-10-09，V47）：规整 / 序列化 / 读侧解析。
 * 纯静态、零 Spring 依赖，可单测（同 {@code TimerShareRules} 的分层）。
 *
 * <h3>总原则：同行者是元数据，永远不能拒掉一笔账</h3>
 * 账本同步按「逐条校验、非法条目进 rejectedIds」工作，而被拒的条目客户端会留队重发——一个
 * 因为昵称里带了奇怪字符 / 头像 URL 过长而被整条拒掉的<b>账目</b>，意味着钱的记录上不了云。
 * 所以本类所有入口都是<b>宽容 + 丢弃</b>而不是校验 + 拒绝：非法项丢弃、超长截断、放不下就从尾部
 * 丢人；任何输入都产出可落库的结果，从不抛。这是 {@link SpendEntryLimits} 毒丸教训的同族应用。
 *
 * <h3>为什么服务端仍要规整（而不是客户端传什么存什么）</h3>
 * 这些字符串来自客户端，经账目所有者本人读回（user-scoped，不对外分发），不构成对第三方的 UGC
 * 展示；但库里的东西必须有形状保证——长度有界、关系是枚举值、不含控制字符——否则下游每个读者都得
 * 自己防御一遍。
 */
@Slf4j
public final class SpendCompanions {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<SpendCompanionItem>> LIST_TYPE = new TypeReference<>() {
    };

    private SpendCompanions() {
    }

    /**
     * 规整请求里的同行者：丢非法项（关系不可识别 / 整项为 null）、规整昵称头像、截到人数上限。
     * 入参 null 表示「客户端没带」，由调用方决定是否保留旧值——本方法对 null 返回 null 以保留这个区分。
     *
     * @return 规整后的列表（空列表 = 明确置空）；入参 null → null
     */
    public static List<SpendCompanionItem> normalize(List<SpendCompanionItem> raw) {
        if (raw == null) {
            return null;
        }
        List<SpendCompanionItem> out = new ArrayList<>(Math.min(raw.size(), SpendEntryLimits.COMPANION_MAX_COUNT));
        for (SpendCompanionItem item : raw) {
            if (out.size() >= SpendEntryLimits.COMPANION_MAX_COUNT) {
                break;
            }
            if (item == null) {
                continue;
            }
            SpendCompanionRelation relation = WireEnums.parse(SpendCompanionRelation.class, item.relation());
            if (relation == null) {
                continue; // 关系是唯一的结构性字段：认不出就整项丢，不猜
            }
            out.add(new SpendCompanionItem(cleanNickname(item.nickname()), cleanAvatarUrl(item.avatarUrl()),
                    relation.name()));
        }
        return out;
    }

    /**
     * 序列化为落库 JSON；空 → null（列 NULL = 无同行者）。放不下
     * （&gt; {@link SpendEntryLimits#COMPANIONS_JSON_MAX_LENGTH}）时从尾部丢人直到放得下——
     * 极端载荷（满屏引号转义膨胀）也产出可落库结果，从不抛。
     */
    public static String serialize(List<SpendCompanionItem> normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        List<SpendCompanionItem> fit = new ArrayList<>(normalized);
        while (!fit.isEmpty()) {
            String json = MAPPER.writeValueAsString(fit);
            if (json.length() <= SpendEntryLimits.COMPANIONS_JSON_MAX_LENGTH) {
                return json;
            }
            fit.remove(fit.size() - 1);
        }
        return null;
    }

    /**
     * 读侧解析。<b>宽容</b>：坏 JSON / 空 → 空列表并打 WARN（一条坏数据不该放大成 500，也不该让整页账目拉取失败），
     * 读出来的每一项再过一遍 {@link #normalize}（手改库产生的越界值也不下发）。恒非 null。
     */
    public static List<SpendCompanionItem> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<SpendCompanionItem> parsed = MAPPER.readValue(json, LIST_TYPE);
            List<SpendCompanionItem> normalized = normalize(parsed);
            return normalized == null ? List.of() : normalized;
        } catch (RuntimeException e) {
            log.warn("[spend] companions_json unparsable, treated as empty: {}", e.getMessage());
            return List.of();
        }
    }

    /** 昵称：去首尾空白与控制字符；空 → null（客户端兜底占位）；超长按字符截断（不拆代理对） */
    private static String cleanNickname(String raw) {
        String cleaned = stripControl(raw);
        if (cleaned == null) {
            return null;
        }
        int max = SpendEntryLimits.COMPANION_NICKNAME_MAX_LENGTH;
        if (cleaned.length() > max) {
            int end = max;
            if (Character.isHighSurrogate(cleaned.charAt(end - 1))) {
                end--; // 别把一个 emoji 劈成半个
            }
            cleaned = cleaned.substring(0, end);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * 头像 URL：只收 https 且不超长；其余 → null（客户端以昵称首字占位）。
     * 超长不截断——截断后的 URL 必然加载失败，不如明确置空。http / 其它 scheme 不收：小程序 image
     * 本身要求 https 域名白名单，库里存一个永远显示不出来的 URL 没有意义。
     */
    private static String cleanAvatarUrl(String raw) {
        String cleaned = stripControl(raw);
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > SpendEntryLimits.COMPANION_AVATAR_URL_MAX_LENGTH
                || !cleaned.regionMatches(true, 0, "https://", 0, "https://".length())) {
            return null;
        }
        return cleaned;
    }

    private static String stripControl(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
