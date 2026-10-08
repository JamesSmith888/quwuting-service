package org.quwuting.quwutingservice.venuepresence.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.config.VenueHeatWeights;
import org.quwuting.quwutingservice.venue.service.HeatAccountExclusionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 列表卡片「到店足迹」行文案供给（2026-10-06 V38 建；<b>2026-10-08 展示口径重定义；
 * 2026-10-09 取数扩至「附近带」300m</b>；文档 = docs/agents/52-venue-presence.md「C 侧展示」节）。
 * <p>
 * <b>为什么独立成类</b>：{@link VenuePresenceService} 的职责是「从原始 ping 算出事实」
 * （附近并集 / 同址归因 / 分摊 / 展示摘要），而本类只做<b>展示派生</b>（门槛 + 文案）——
 * 事实与表达分离，改文案不动事实口径，改口径不动文案。同 {@code StatusReportLatestService}
 * 为打破循环依赖而拆微服务的先例：能力边界决定类的边界。
 *
 * <h3>⛔ 2026-10-08 重定义：展示语义 = 「附近的足迹」，不再做门店级归属</h3>
 * <b>用户裁决原话</b>：「因为我们无法从经纬度距离上判断线下用户真实在哪家店，所以范围内的店
 * 我们都显示为『附近的足迹』，不管它是否关门……我们没有承诺用户百分之百是这家店，
 * 我们只是承诺是附近，交给用户自己去判断是哪家店。」
 * <p>
 * 落地形态（事实来源 = {@link VenuePresenceService#nearbyVisitSummaries}）：
 * <ul>
 *   <li><b>展示单元 = 门店 ± {@code NEARBY_TRACE_RADIUS_M}（150m）邻域内的全部「附近带」足迹
 *       （距店 ≤ {@code NEARBY_RADIUS_M}）的并集</b>（含停业门店上的证据；一端为已发生的足迹
 *       事实，另一端只声明「附近」，见常量注释标定）；</li>
 *   <li><b>范围内所有门店一律展示</b>——营业状态不参与过滤（不管是否关门）；
 *       同一组门店显示同一句文案（「都显示」）；</li>
 *   <li><b>文案恒为「附近」语义</b>，数字 = 范围并集（与 admin 的共享口径同层；不再使用
 *       1/k 分摊后的排序份额——那曾导致「admin 显示 4 人、小程序显示 2 人」的口径割裂）；</li>
 *   <li><b>「真实到店足迹」措辞全面退役</b>：150m 命中本身不能证明「进店」，且门店归属
 *       不可判定——「真实」是替系统断言无法证实的事实（事故复盘见 52 号）。</li>
 * </ul>
 *
 * <h3>文案（三段）与长度纪律（硬约束不是偏好）</h3>
 * <ul>
 *   <li>≥ {@link #MIN_VISIT_USERS}（3）人：「<b>感谢 N 位舞友 · N 次到过这附近的足迹</b>」——
 *       「次」= 有人<b>多次</b>回来（人数只说"有人来过"，次数才说"有人常来"）；
 *       口径 = {@code (人, 自然日)} 去重（一天内多次只记 1 次，读作"来了几个晚上"）；</li>
 *   <li>1~2 人：「<b>感谢 N 位舞友来过这附近</b>」（省略「次」：1 人来 1 天时两个数字是同一个数、
 *       零信息量；现网多数店是 1~2 人，满屏"1 次"让功能显得"什么都没干"）；</li>
 *   <li>「感谢」= 对贡献的正反馈，也是本行引导开启的正当性来源（感谢是回报，不是诱导）；
 *       「到店足迹」= 与设置页开关名逐字对齐（本行可点，点进去就是那个开关）。</li>
 * </ul>
 * 该行是卡片内<b>单行</b> caption（22rpx + {@code text-overflow: ellipsis}），可用宽 ≈518rpx
 * ⇒ 约 23 个中文字；主文案 N 为一位数时约 20 字，单行放得下。⛔ <b>禁再加从句</b>
 * （用户曾提「为社区建设出力」→ 已否决：必截断）。
 *
 * <h3>展示门槛 = 1，但「无足迹」仍不渲染（唯一硬约束）</h3>
 * 附近无足迹的门店不在 {@link VenuePresenceService#nearbyVisitSummaries} 结果里
 * ⇒ 前端 null 不渲染、卡片零布局变化。⛔ 显示"0 位舞友"是负面失实陈述（真实到访系统性大于本数字）。
 * <p>
 * ⚠️ <b>展示与排序是两条独立的线</b>（2026-10-06 六轮用户裁决）：「有显示 ⇔ 有分」契约
 * <b>已有意放弃</b>——1~2 人店与停业店都会出现"有显示但无分"。这是预期行为，⛔ 勿当 bug 修。
 *
 * <h3>历史留痕（勿回退，2026-10-08）</h3>
 * V40 的 {@code share_in_operation} 文案分支（未分摊 ⇒「真实到店足迹」/ 被分摊 ⇒「附近」）
 * 随本轮重构<b>整体删除</b>，原因有二：① 它的立论（"未分摊时事实无歧义，可断言到这家店"）
 * 与新裁决矛盾（任何门店级归属断言都不可判定）；② 该分支自 10-06 上线起<b>映射被接反</b>
 * （{@code allocated = !isUnallocated(...)} 叠加三元分支写反 ⇒ 未分摊显示"附近"、被分摊显示"真实"，
 * 与文档、与同处注释、与其自身 fail-safe 注释三处全部相反），且从未被端到端验证——
 * 本轮事故复盘的一部分。<b>{@code qwt_venue_visit_metrics.share_in_operation} 列保留
 * （V40 迁移不回改）但自此无消费方</b>；刷新任务仍写入该列（保持行自洽），
 * 删除与否留待该表整体重构时一并处理。
 *
 * <h3>与其它口径的分叉（都读同一份命中事实，⛔ 禁互相"对齐"）</h3>
 * 展示（本类）= 附近并集、无归属、无状态过滤；admin 归因 = ABSORBED/YIELDED 明细；
 * 排序 = 1/k 分摊 + 不在营记 0。三者的数字<b>有意不同</b>，取舍依据见 52 号。
 */
@Service
@RequiredArgsConstructor
public class VenueVisitBadgeService {

    /**
     * <b>「次」的显示门槛</b>（人）= 免计基数 + 1：派生自 {@link VenueHeatWeights#VISIT_FREE_TIER}。
     *
     * <p>⚠️ <b>2026-10-06 六轮起，它不再管"是否展示"</b>（用户裁决「展示与排序是两条线」）：
     * 展示侧门槛已降为 {@link #MIN_DISPLAY_USERS}(=1)，本常量<b>降级为"何时亮出「次」"的分界</b>
     * （人数 ≥3 ⇒ 有人可能常来 ⇒ "次"这个数字开始承载信息）。
     * <b>改它只影响文案形态与热度公式的免计基数，二者仍同源</b>，勿当成纯展示参数。
     */
    private static final long MIN_VISIT_USERS = VenueHeatWeights.VISIT_FREE_TIER + 1;

    /**
     * <b>展示门槛</b>（人）= 1（2026-10-06 六轮用户裁决「展示：全部展示」）。
     *
     * <p><b>为什么是 1 而不是 0</b>：0 意味着"附近没有任何命中足迹"——那正是<b>无数据</b>，
     * 而无数据门店压根不在 {@code nearbyVisitSummaries} 结果里，自然不渲染。
     * 写成显式的 {@code >= 1} 是为了表达"<b>有足迹就展示</b>"这条口径本身，
     * 而不是依赖"map 里没有就是 0"的隐含前提（那个前提一旦被改口径打破就会静默出错）。
     */
    private static final long MIN_DISPLAY_USERS = 1L;

    private final VenuePresenceService venuePresenceService;
    private final HeatAccountExclusionService heatAccountExclusionService;

    /**
     * 整页批量生成（列表卡片到店足迹行，防 N+1）：一次覆盖整页，返回 venueId → 文案。
     * <b>附近无足迹的门店不在 map 里</b>（前端按缺席不渲染）；
     * <b>有足迹（≥1 人）的门店全部展示</b>（2026-10-08：含停业门店，状态不参与过滤）。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> visitBadgeTextsByVenue(Collection<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, NearbyVisitSummary> summaries = venuePresenceService.nearbyVisitSummaries(
                venueIds, heatAccountExclusionService.excludedUserIds());
        Map<Long, String> badges = new HashMap<>();
        summaries.forEach((venueId, summary) -> {
            if (summary.visitUsers() < MIN_DISPLAY_USERS) {
                return;
            }
            badges.put(venueId, renderBadgeText(summary.visitUsers(), summary.visitEvents()));
        });
        return badges;
    }

    /**
     * 文案渲染（纯函数，单点；改文案只改这里）。
     *
     * <p>⚠️ 数字是<b>范围并集的整数</b>（无 1/k 分摊 ⇒ 无小数），且由
     * {@link VenuePresenceService#nearbyVisitSummaries} 保证 @{@code visitEvents >= visitUsers >= 1}。
     * ⛔ <b>刻意不做"下限钳 1"</b>：旧实现的钳位是为分摊小数（0.33 → 显示"0 次"的自相矛盾）
     * 而设；整数并集下次数恒 ≥ 人数，钳位<b>只会把取数错误掩盖成看起来合理的数字</b>
     * （2026-10-06 教训：钳 1 曾把"并集键丢了 user 维"的 bug 掩盖成"1 次"）。
     */
    static String renderBadgeText(long visitUsers, long visitEvents) {
        if (visitUsers >= MIN_VISIT_USERS) {
            return "感谢 " + visitUsers + " 位舞友 · " + visitEvents + " 次到过这附近的足迹";
        }
        return "感谢 " + visitUsers + " 位舞友来过这附近";
    }
}
