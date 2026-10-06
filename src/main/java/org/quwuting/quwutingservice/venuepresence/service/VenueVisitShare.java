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
 * @param visitEvents30d 近 30 天分摊到访<b>次数</b>（(人,店,自然日) 二元组去重 × 同一 1/k 分摊，
 *                       故恒 ≥ visitUsers30d：每位用户至少贡献 1 次）；
 *                       <b>与人数同源同分摊</b>（共用同一趟归因循环，见
 *                       {@code VenuePresenceService#visitSharesForRanking}）——
 *                       不同源会出现"5 位舞友分享 0 次"这类自相矛盾的行。
 *                       仅展示，不进公式（与人数是同一份命中集的两个粒度，同时进公式 = 同一信号算两票）。
 * @param groupSize     同址组规模（含本店，≥1）——口径说明用，不参与计算
 * @param shareInOperation {@code true} = 未被同址分摊（文案可断言"到这家店"）；
 *                       {@code false} = 被同址在营店按 1/k 分摊（**文案必须改「附近」语义**——
 *                       20m 定位精度分不清是哪家店，断言"这家店"是未证实的陈述，V40）。
 */
public record VenueVisitShare(
        BigDecimal visitUsers30d,
        BigDecimal visitUsers7d,
        BigDecimal visitEvents30d,
        int groupSize,
        boolean shareInOperation) {
}
