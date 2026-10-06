package org.quwuting.quwutingservice.venuepresence.service;

import java.math.BigDecimal;

/**
 * 单店「排序口径」到访份额（2026-10-06，V38；文档 = docs/agents/52-venue-presence.md「到访进排序」）。
 * <p>
 * <b>与 admin 展示口径 {@link VenueVisitSummary} 是三处有意分叉，勿"顺手统一"</b>：
 * <ol>
 *   <li><b>排除内部账号</b>：展示口径不排除（回答"发生了什么"），排序口径排除
 *       （回答"有多火"）——到访是低基数信号，2026-10-06 现网 51 条 ping 中 ADMIN 一人占 51%；</li>
 *   <li><b>分摊而非共享</b>：同址组内 ≥2 家门店都在营时，每位用户按 1/k 分给 k 家在营店
 *       ⇒ 本记录的人数是<b>小数</b>；共享计数进排序 = 同楼各家各吃一份整楼人流
 *       （比同等人流的独栋店多一倍，52 号 §1.1 第 1 条）；</li>
 *   <li><b>不在营门店不产出</b>：门店状态 ∉ {OPEN, CLOSED} ⇒ 不进入本映射（排序记 0）。
 *       展示口径保留该证据作为门店状态复核线索（"停业店持续有到访" ⇒ 建议复核，48 号域价值）。</li>
 * </ol>
 *
 * @param visitUsers30d 近 30 天分摊到访人数（归因 × 1/k），排序公式唯一输入
 * @param visitUsers7d  近 7 天分摊到访人数（同口径）；仅展示，不进公式
 * @param groupSize     同址组规模（含本店，≥1）——口径说明用，不参与计算
 */
public record VenueVisitShare(
        BigDecimal visitUsers30d,
        BigDecimal visitUsers7d,
        int groupSize) {
}
