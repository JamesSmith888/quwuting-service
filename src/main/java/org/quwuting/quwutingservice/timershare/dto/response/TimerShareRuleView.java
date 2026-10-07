package org.quwuting.quwutingservice.timershare.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 计价规则的<b>线上形态</b>（2026-10-07，V42）：落库（rule_json）与下发（加入响应）共用同一个类型，
 * 因为库里存的就是服务端按白名单重新序列化的结果，读出来再原样下发没有第二份口径。
 * <p>
 * {@code mode} 为 null 表示档位式（缺省）——与前端 {@code PriceTier.mode} 的「缺省 = step，
 * 禁把缺省写成显式 'step' 落盘」约定同构，所以用 NON_NULL 省略而不是写出 null。
 */
public record TimerShareRuleView(List<Tier> tiers) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tier(double durationMinutes, double price, String mode) {
    }
}
