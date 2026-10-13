package org.quwuting.quwutingservice.venuepresence.service;

import java.time.LocalDateTime;

/**
 * 单店「附近的足迹」<b>完整事实</b>（2026-10-13；展示口径的唯一计算结果，52 号「展示口径一源」节）。
 * <p>
 * 由 {@code VenuePresenceService#nearbyFootprints} 一次计算产出，<b>两个消费面各取所需</b>：
 * <ul>
 *   <li>C 侧卡片 = {@link #toDisplaySummary()}（人数 + 次数，30 天窗；无足迹不渲染）；</li>
 *   <li>admin 门店列表 / 详情 / 名单 = 本记录全量（多出 7 天人数、最近到访、被排除人数、邻域门店数）。</li>
 * </ul>
 * <b>为什么是一个记录两个投影，而不是两套各算各的</b>（2026-10-13 根因）：此前 admin 与 C 侧各有一套
 * 取数（admin = 150m 命中带 × 同址归因；C 侧 = 邻域 150m × 附近带 300m 并集），并在注释里写明
 * 「⛔ 禁互相对齐」。后果是运营在后台看到的数字与用户在小程序里看到的数字对不上，且后台的人员名单
 * 无法对应到用户看到的那句话——口径分叉的本意是「各自回答不同问题」，但<b>展示给人看的数字</b>
 * 只该有一个答案。分叉只保留在真正回答不同问题的地方（热度排序的 1/k 分摊、用户个人到访明细）。
 *
 * @param visitUsers7d      近 7 天去重用户数（附近带并集；与 {@link #visitUsers30d} 同源，窗口判据同为「最近命中时刻 ≥ 起点」）
 * @param visitUsers30d     近 30 天去重用户数（= C 侧「感谢 N 位舞友」的 N）
 * @param visitEvents30d    近 30 天去重到访次数 = {@code (用户, 自然日)} 二元组并集大小（= C 侧「N 次」的 N）；
 *                          ⚠️ 恒 ≥ {@link #visitUsers30d}，两数去重粒度不同，⛔ 禁互相推算
 * @param lastVisitAt       最近一次足迹时刻（同并集、<b>不设时间窗</b>）；null = 附近从无足迹。
 *                          窗外的老足迹在这里仍可见（admin 排序 / 「最近」列需要），C 侧不消费本字段
 * @param excludedUsers30d  近 30 天落在附近并集内、但因「排除账号集合」未计入上面数字的用户数。
 *                          集合与热度排序同源（现状为空 ⇒ 恒 0）；非 0 时 admin 必须明说，
 *                          否则运营数一遍名单会发现比卡片上多
 * @param nearbyVenueCount  邻域内的<b>其他</b>门店数（≤ {@code NEARBY_TRACE_RADIUS_M}，不含本店）：
 *                          这些门店与本店显示同一组足迹——解释「为什么隔壁店数字一样」
 */
public record NearbyFootprint(
        long visitUsers7d,
        long visitUsers30d,
        long visitEvents30d,
        LocalDateTime lastVisitAt,
        long excludedUsers30d,
        int nearbyVenueCount) {

    /** 无足迹、无邻居（批量结果缺席时的兜底） */
    public static final NearbyFootprint EMPTY = new NearbyFootprint(0, 0, 0, null, 0, 0);

    /** C 侧展示事实投影（人数 + 次数）。调用方先判 {@code visitUsers30d > 0}——无足迹不渲染 */
    public NearbyVisitSummary toDisplaySummary() {
        return new NearbyVisitSummary(visitUsers30d, visitEvents30d);
    }
}
