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
 * <h3>文案为什么是「感谢 N 位舞友 · N 次真实到店足迹」（2026-10-06 三轮定稿，勿简写）</h3>
 * 四个词各担一个职责，缺一个都会说错话：
 * <ul>
 *   <li><b>「感谢」= 对贡献的正反馈</b>。愿意开启的人是在替全体用户补全"这家店真的有人去"
 *       这一事实，理应有回应；这也是本行"顺带引导更多用户开启"的正当性来源——
 *       感谢是回报，不是诱导。</li>
 *   <li><b>「N 位舞友」= 有多少人参与</b>。人数是最粗但最可感的量级感来源。</li>
 *   <li><b>「N 次」= 有人<b>多次</b>回来</b>。人数只说明"有人来过"，次数才说明
 *       "有人常来"——后者才是热度公式真正想吃的信息，也是让用户愿意继续开着的理由。
 *       口径 = {@code (人, 店, 自然日)} 去重（V39，见本类「人数与次数为什么同源却不可互推」节）：
 *       <b>一天内去多次只记 1 次</b>（用户 2026-10-06 定案），所以"次"读作"来了几个晚上"，
 *       不读作"打开了几次小程序"。</li>
 *   <li><b>「真实」= 口径 + 合规</b>。到访只覆盖「到店 × 打开小程序 × 定位命中 ×
 *       <b>用户显式开启到店足迹</b>」的联合事件（服务端同意门禁，见 {@link VenuePresenceService}）
 *       —— 数字**远小于**真实到店人数、且低估幅度不可测。把"真实"写在脸上，用户就不会
 *       把它读成"这家店只有 N 个人来过"（对门店是失真的负面陈述），同时强化了本功能的
 *       知情同意叙事（个保法第 28/29 条要求处理敏感个人信息须单独同意）。</li>
 *   <li><b>「到店足迹」= 用户可读的功能名</b>。与「我的-设置」里的开关名逐字对齐——
 *       本行可点，点进去就是那个开关，文案一致才能形成"看到 → 点开 → 找到开关"的闭环
 *       （文案与目标页用词不一致 = 用户到了设置页认不出）。</li>
 * </ul>
 * ⛔ 禁改写为无限定词形态（「N 人到店」/「N 位舞友到店」/「已记录 N 位舞友到店」——
 * 一轮稿，被"只陈述事实、不解释来源、也不感谢"否决）。<b>改文案前先读本段</b>。
 *
 * <h3>长度纪律（2026-10-06 三轮实测，硬约束不是偏好）</h3>
 * 该行是卡片内<b>单行</b> caption（22rpx + {@code text-overflow: ellipsis}），
 * 行内还要扣前置 signal 图标与行尾「点击了解更多」（V39 同轮新增，6 字 ≈ 132rpx）：
 * <ul>
 *   <li>可用宽 ≈ <b>518rpx</b> ⇒ 每行约 <b>23 个中文字</b>；</li>
 *   <li>主文案「感谢 N 位舞友 · N 次真实到店足迹」在 N 为一位数时约 <b>19 字</b>
 *       ⇒ 单行放得下（人数取整后 ≥100 的店现网尚无，门槛与量级决定它短期不会出现）；</li>
 *   <li>⛔ <b>禁再加从句</b>：用户曾提出追加「为社区建设出力」→ 已否决（推到 24+ 字必截断）。
 *       "为社区出力"的分量由「感谢」+「真实」+「舞友」三个词承担，不靠追加说明。</li>
 * </ul>
 *
 * <h3>「人数」与「次数」的关系（V39）</h3>
 * 两者由<b>同一趟归因循环</b>产出（同 attribution / 同 1/k 分摊 / 同排除集），
 * 口径上的关系是<b>次数 ≥ 人数</b>：每位用户至少贡献 1 次（同址让渡下一个人算 1 人、
 * 却来了 3 天 ⇒ 3 次）。两个数字的去重粒度不同，<b>不可互推</b>：
 * <ul>
 *   <li>人数 = {@code MAX(createdAt)} 时间窗去重 ⇒ <b>跨零点连场算 1 人</b>
 *       （舞厅 22:00 进 02:00 出不会被拆成两天）；</li>
 *   <li>次数 = {@code (人, 自然日)} 二元组去重 ⇒ 跨零点连场算 <b>2 次</b>
 *       （这正是"有人常来"与"有人来过"的差别）。</li>
 * </ul>
 * ⛔ 禁止用其中之一推算另一个（推出来的数与真实口径不符，且错得静默）；
 * ⛔ 也**不需要**做"次数 ≥ 人数"的纠偏——那只会把取数 bug 掩盖成看起来合理的数字
 * （2026-10-06 实测教训，见 {@link #displayEvents}）。
 *
 * <h3>文案由后端下发，前端只渲染</h3>
 * 与 {@code crowdBadgeText} / {@code heatFormulaText} 同模式：措辞承载口径（"真实"是口径），
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
     * <b>无到访 / 未过门槛的门店不在map 里</b>（前端按缺席不渲染）。
     * <p>
     * ⚠️ 两列取值口径<b>刻意不同</b>，改前先读本类「人数与次数为什么同源却不可互推」节：
     * 人数 = 时间窗去重（跨零点连场算 1 人），次数 = 自然日去重（跨零点算 2 次）。
     * 两个数字来自同一份命中集与同一趟归因，但去重粒度不同 ⇒ 不可互相推算。
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
            // row[2] 来自 JPQL 原生返回的 Object[]，元素声明为 Object（不像 row[1] 有赋值
            // 处的向下转型）⇒ 必须显式转型，漏了就是编译错误（不会静默出null）
            long events = displayEvents((BigDecimal) row[2]);
            badges.put((Long) row[0], "感谢 " + displayed + " 位舞友 · " + events + " 次真实到店足迹");
        }
        return badges;
    }

    /**
     * 次数列的展示取整（2026-10-06，V39）。
     * <p>
     * **下限钳 1 的理由**：分摊后可能是 0.33（1 人 / 3 家同址在营店），HALF_UP 会显示
     * "0 次"——那读起来是"有人来过却一次没记录"的自相矛盾文案，是对贡献者的否定。
     * 钳 1 与"显示几位"的口径纪律同源（宁可略高不略低；分摊本身已把同址人流摊薄，
     * 不是虚报）。
     * <p>
     * ⚠️ **钳 1 绝不用于掩盖取数 bug**（2026-10-06 实测教训）：本次首版实现把并集键错写成
     * 纯日期（丢了 user 维度），现网样本 6 位用户散在 5 天 ⇒ 误算 5 次 ⇒ ×1/2 分摊 = 2.5
     * ⇒ 存 0.00 ⇒ 钳 1 显示"1 次"，而真实是 6 位用户。
     * <b>看到"1 次"先怀疑取数、别怀疑钳 1</b>：钳 1 只应把 0.33 抬到 1，不该把 0 抬到 1
     * （真为 0 说明该店根本没有到访日，而人数已过≥3 ⇒ 矛盾，必是取数问题）。
     * <p>
     * ✅ <b>次数恒 ≥ 人数</b>（每个用户至少贡献 1 次，见
     * {@code VenuePresenceService#countVisitEvents}）⇒ <b>不需要</b>也不允许再做
     * "次数 ≥ 人数"的对齐/纠偏：那样会把取数错误掩盖成看起来合理的数字。
     */
    private static long displayEvents(BigDecimal raw) {
        if (raw == null) {
            return 0;
        }
        long rounded = raw.setScale(0, RoundingMode.HALF_UP).longValue();
        return Math.max(1, rounded);
    }
}
