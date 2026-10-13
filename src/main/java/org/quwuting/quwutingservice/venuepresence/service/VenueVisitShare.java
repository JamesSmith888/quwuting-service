package org.quwuting.quwutingservice.venuepresence.service;

import java.math.BigDecimal;

/**
 * 单店「排序口径」到访份额（2026-10-06，V38；文档 = docs/agents/52-venue-presence.md「到访进排序」）。
 * <p>
 * <b>与展示口径 {@link NearbyFootprint} 是有意分叉，勿"顺手统一"</b>（2026-10-13 起这是展示数字之外
 * 仅存的分叉：展示数字回答"这一带有多少足迹"并已 C 侧 / admin 合一；排序回答"该给多少分"）：
 * <ol>
 *   <li><b>取数带</b>：排序取命中带 150m（热度公式的输入，改带宽 = 改榜单），展示取附近带 300m；</li>
 *   <li><b>分摊而非并集</b>：同址组内 ≥2 家门店都在营时，每位用户按 1/k 分给 k 家在营店
 *       ⇒ 本记录的人数是<b>小数</b>；并集计数进排序 = 同楼各家各吃一份整楼人流
 *       （比同等人流的独栋店多一倍，52 号 §1.1 第 1 条）；</li>
 *   <li><b>不在营门店按同址归因让渡</b>：同址有在营店时本店无证据（排序记 0）；展示不看营业状态。</li>
 * </ol>
 * 排除内部账号在两侧**同一集合**（热度排除名单），不是分叉点。
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
 * @param shareInOperation {@code true} = 未被同址分摊（本店分摊系数 = 1）；
 *                       {@code false} = 被同址在营店按 1/k 分摊。
 *                       ⚠️ 2026-10-08 起<b>无消费方</b>：它原为 C 侧文案用词依据（V40），
 *                       而文案已改为无条件「附近」语义（门店级归属断言整体退役，见
 *                       52 号「C 侧展示：附近的足迹」节）——保留随行仅为查询/调试可读，
 *                       ⛔ 不得据此重建"未分摊 ⇒ 可断言到这家店"的用词分支。
 */
public record VenueVisitShare(
        BigDecimal visitUsers30d,
        BigDecimal visitUsers7d,
        BigDecimal visitEvents30d,
        int groupSize,
        boolean shareInOperation) {
}
