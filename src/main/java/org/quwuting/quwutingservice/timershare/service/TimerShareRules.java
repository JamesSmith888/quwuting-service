package org.quwuting.quwutingservice.timershare.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.timershare.dto.request.CreateTimerShareRequest;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareRuleView;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 分享快照里<b>计价规则</b>的结构单点（2026-10-07，V42）：白名单校验 / 序列化 / 读侧解析。
 * 纯静态、零 Spring 依赖，可单测（同 {@code MediaAttachments} 的分层）。
 *
 * <h3>为什么服务端要重新序列化，而不是把客户端传来的 JSON 原样落库</h3>
 * 规则来自另一个用户的手机，经服务端转交给第三个用户的手机——这是一条<b>用户 → 用户</b>的通道。
 * 原样落库 = 服务端替客户端背书了一段任意结构：多出来的字段（如规则名、备注）会被原样下发并显示，
 * 而规则名是用户输入的自由文本，公开展示给他人即 UGC 发布（个人主体小程序的类目红线）。
 * 这里把请求解析成强类型、逐项校验、只取 {@code durationMinutes / price / mode} 三个数值字段
 * 重新序列化，多余字段在解析那一刻就被丢掉，库里和线上只可能出现数字。
 * <p>
 * 校验的是<b>数值合理性</b>（有限、范围内），不是业务正确性——价格是否「贵得离谱」不归服务端判断；
 * 范围护栏只防脏数据与溢出（接收方会拿这些数去做除法与取整）。
 */
@Slf4j
public final class TimerShareRules {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 折算式的线上取值；档位式用 null 表示（缺省，不落盘——同前端 PriceTier.mode 的约定） */
    private static final String MODE_LINEAR = "linear";
    private static final String MODE_STEP = "step";

    private TimerShareRules() {
    }

    /**
     * 校验并规整请求里的规则。
     *
     * @throws IllegalArgumentException 任何一项不合法（调用方转成 1041）；消息只用于日志，不回给客户端
     */
    public static TimerShareRuleView normalize(CreateTimerShareRequest.RuleInput input) {
        if (input == null || input.tiers() == null || input.tiers().isEmpty()) {
            throw new IllegalArgumentException("rule.tiers 为空");
        }
        if (input.tiers().size() > TimerSharePolicy.RULE_MAX_TIERS) {
            throw new IllegalArgumentException("rule.tiers 超过 " + TimerSharePolicy.RULE_MAX_TIERS + " 档");
        }
        List<TimerShareRuleView.Tier> tiers = new ArrayList<>(input.tiers().size());
        for (CreateTimerShareRequest.TierInput t : input.tiers()) {
            if (t == null) {
                throw new IllegalArgumentException("rule.tiers 含空项");
            }
            double minutes = requireFinite(t.durationMinutes(), "durationMinutes");
            double price = requireFinite(t.price(), "price");
            if (minutes <= 0 || minutes > TimerSharePolicy.TIER_MAX_MINUTES) {
                throw new IllegalArgumentException("durationMinutes 越界: " + minutes);
            }
            if (price < 0 || price > TimerSharePolicy.TIER_MAX_PRICE) {
                throw new IllegalArgumentException("price 越界: " + price);
            }
            tiers.add(new TimerShareRuleView.Tier(minutes, price, normalizeMode(t.mode())));
        }
        return new TimerShareRuleView(List.copyOf(tiers));
    }

    /** 序列化为落库 JSON；超过列宽则抛（正常规则 ≤8 档远小于上限，超限只可能是构造出来的载荷） */
    public static String serialize(TimerShareRuleView rule) {
        String json = MAPPER.writeValueAsString(rule);
        if (json.length() > TimerSharePolicy.RULE_JSON_MAX_CHARS) {
            throw new IllegalArgumentException("rule_json 超过 " + TimerSharePolicy.RULE_JSON_MAX_CHARS + " 字符");
        }
        return json;
    }

    /**
     * 读侧解析。<b>严格</b>：失败返回 null 并打 ERROR（而不是抛）——加入路径据此回 NOT_FOUND，
     * 一条坏数据不该放大成 500；但它只可能来自手改库，必须留下可追的日志。
     */
    public static TimerShareRuleView parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            TimerShareRuleView rule = MAPPER.readValue(json, TimerShareRuleView.class);
            if (rule == null || rule.tiers() == null || rule.tiers().isEmpty()) {
                log.error("[timer-share] rule_json parsed but empty: {}", json);
                return null;
            }
            return rule;
        } catch (RuntimeException e) {
            log.error("[timer-share] rule_json unparsable: {} ({})", json, e.getMessage());
            return null;
        }
    }

    private static double requireFinite(Double value, String field) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            throw new IllegalArgumentException(field + " 缺失或非有限数");
        }
        return value;
    }

    /** null / "step" → null（档位式缺省）；"linear" → "linear"；其它值一律拒绝（不猜默认，WireEnums 同款判据） */
    private static String normalizeMode(String mode) {
        if (mode == null) {
            return null;
        }
        String m = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (m.isEmpty() || MODE_STEP.equals(m)) {
            return null;
        }
        if (MODE_LINEAR.equals(m)) {
            return MODE_LINEAR;
        }
        throw new IllegalArgumentException("mode 非法: " + mode);
    }
}
