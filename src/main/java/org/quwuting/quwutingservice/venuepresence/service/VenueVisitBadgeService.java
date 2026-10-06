package org.quwuting.quwutingservice.venuepresence.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.config.VenueHeatWeights;
import org.quwuting.quwutingservice.venuepresence.repository.VenueVisitMetricRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 列表页「到店足迹」胶囊文案供给（2026-10-06，V38；文档 = docs/agents/52-venue-presence.md「到访进排序」）。
 * <p>
 * <b>为什么独立成类</b>：{@link VenuePresenceService} 的职责是「从原始 ping 算出到访事实」
 * （归因 / 分摊 / 展示摘要），而本类读的是**已经物化的排序指标**再做一层展示派生
 * （门槛 + 文案）——两者输入不同（原始 ping vs V38 物化表）、生命周期不同
 * （写路径 vs 列表读路径）。同 {@code StatusReportLatestService} 为打破循环依赖而拆微服务的
 * 先例：能力边界决定类的边界。
 *
 * <h3>门槛为什么 = {@code VISIT_FREE_TIER + 1}（不是另拍一个数）</h3>
 * <ol>
 *   <li><b>与计分口径同门槛</b>：卡片上出现的数字必然对应热度公式里的正分——否则会出现
 *       "展示了 N 人到店、但这家店没拿任何到访分"的隐性矛盾（用户看不出，但口径上是撒谎）；</li>
 *   <li><b>1~2 人无判定价值</b>：到访观测服从 λ≈1 泊松（2026-10-06 现网 8 家有到访门店中
 *       5 家只有 1 人），1 人无法区分"真的有人去"与"恰好一个人路过"；</li>
 *   <li><b>无数据不渲染</b>：无到访的门店不在结果里 ⇒ 前端 null 不渲染，卡片零布局变化——
 *       显示"0 人到店"对门店是负面的失实陈述（真实到店人数系统性大于本数字）。</li>
 * </ol>
 *
 * <h3>文案为什么是「感谢 N 位舞友自愿分享到店足迹」（2026-10-06 二轮定稿，勿简写）</h3>
 * 三个词各担一个职责，缺一个都会说错话：
 * <ul>
 *   <li><b>「自愿」= 口径 + 合规</b>。到访只覆盖「到店 × 打开小程序 × 定位命中 ×
 *       <b>用户显式开启到店足迹</b>」的联合事件（服务端同意门禁，见 {@link VenuePresenceService}）
 *       —— 数字**远小于**真实到店人数、且低估幅度不可测。把"自愿"写在脸上，用户就不会
 *       把它读成"这家店只有 N 个客人"（对门店是失真的负面陈述），同时强化了本功能的
 *       知情同意叙事（个保法第 28/29 条要求处理敏感个人信息须单独同意）。</li>
 *   <li><b>「感谢」= 对贡献的正反馈</b>。愿意开启的人是在替全体用户补全"这家店真的有人去"
 *       这一事实，理应有回应；这也是本行"顺带引导更多用户开启"的正当性来源——
 *       感谢是回报，不是诱导。</li>
 *   <li><b>「分享到店足迹」= 用户可读的功能名</b>。与「我的-设置」里的开关名
 *       （「到店足迹」）逐字对齐 —— 本行可点，点进去就是那个开关，文案一致才能形成
 *       "看到 → 点开 → 找到开关"的闭环（文案与目标页用词不一致 = 用户到了设置页认不出）。</li>
 * </ul>
 * ⛔ 禁改写为无限定词形态（「N人到店」/「N位舞友到店」/「已记录 N 位舞友到店」——最后一种是
 * 一轮稿，被"只陈述事实、不解释来源、也不感谢"否决）。<b>改文案前先读本段</b>。
 *
 * <h3>文案由后端下发，前端只渲染</h3>
 * 与 {@code crowdBadgeText} / {@code heatFormulaText} 同模式：措辞承载口径（"自愿"是口径），
 * 口径归后端；前端换文案要走发版，而这里改一行即可全端生效。
 *
 * <h3>与热度公式同门槛（不是巧合）</h3>
 * 卡片上出现的数字必然对应公式里的正分（见 {@link #MIN_VISIT_USERS}）——否则会出现
 * "展示了 N 人、但这家店没拿到任何到访分"的隐性矛盾（用户看不出，但口径上是撒谎）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueVisitBadgeService {

    /**
     * 展示门槛（人）= 免计基数 + 1：派生自 {@link VenueHeatWeights#VISIT_FREE_TIER}，
     * <b>不是独立可调参数</b>——两者语义绑定（有分 ⇔ 有展示），改一个必须同时想另一个。
     */
    private static final long MIN_VISIT_USERS = VenueHeatWeights.VISIT_FREE_TIER + 1;

    private final VenueVisitMetricRepository metricRepository;

    /**
     * 整页批量生成（列表卡片标签行，防 N+1）：一次 IN 覆盖整页，返回 venueId → 文案。
     * <b>无到访 / 未过门槛的门店不在 map 里</b>（前端按缺席不渲染）。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> visitBadgeTextsByVenue(Collection<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> badges = new HashMap<>();
        for (Object[] row : metricRepository.findVisitUsersByVenueIds(venueIds)) {
            BigDecimal visitUsers = (BigDecimal) row[1];
            if (visitUsers == null) {
                continue;
            }
            // 分摊会产生 1/k 小数（同址组都在营）——展示取整，与门槛比较也用取整值，
            // 保证"显示几位"与"是否过门槛"是同一个数（不出现 2.6→显示3 却按2判门槛）
            long displayed = visitUsers.setScale(0, RoundingMode.HALF_UP).longValue();
            if (displayed < MIN_VISIT_USERS) {
                continue;
            }
            badges.put((Long) row[0], "感谢 " + displayed + " 位舞友自愿分享到店足迹");
        }
        return badges;
    }
}
