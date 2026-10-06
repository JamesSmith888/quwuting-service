package org.quwuting.quwutingservice.venuepresence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 门店到访指标物化汇总（2026-10-06，V38；文档 = docs/agents/52-venue-presence.md「到访进排序」）。
 * <p>
 * 一行 = 一家「曾经有过到访」的门店的最新排序口径到访人数。无到访门店**不落行**，
 * 公式侧 COALESCE 为 0。
 *
 * <h3>为什么必须有这张表（不是缓存优化，是可行性前提）</h3>
 * 到访人数是<b>派生量</b>：{@code f(ping(距离,精度) × 同址组几何 ≤50m × 双方营业状态 × 用户并集)}，
 * 其中同址组归因（SHARED / ABSORBED / YIELDED）是 {@code VenuePresenceService} 的
 * <b>Java 侧</b>计算，而 <b>JPQL 无 FROM 派生表能力</b> ⇒ 无法在
 * {@code VenueRepository.HEAT_BEHAVIOR} 内表达。
 * <p>
 * ⛔ <b>禁止的替代做法</b>：在公式里直接写「距离 ≤150m 的 COUNT(DISTINCT user_id)」标量子查询
 * ——那会绕过归因，同楼竞品各吃一份整楼人流，且丢掉「停业店证据让渡给在营店」的消歧结果
 * （52 号 §1.1 第 1 条明文禁止）。
 *
 * <h3>本表的三个口径（与 admin 展示口径**有意分叉**，勿"顺手统一"）</h3>
 * <ol>
 *   <li><b>排除内部账号</b>（{@code heat.excluded.user.ids} ∪ 哨兵）：到访是低基数信号
 *       （2026-10-06 现网 51 条 ping 中 ADMIN 一人占 26 条 = 51%），内部账号在本项的占比
 *       远高于它在收藏/反馈里的占比。admin 展示口径**不排除**（展示回答"发生了什么"、
 *       公式回答"有多火"）。</li>
 *   <li><b>分摊口径</b>：同址组内 ≥2 家门店都在营时，每位用户按 1/k 分给 k 家在营店
 *       ⇒ 本列是<b>小数</b>（decimal(8,2)）；admin 展示用<b>共享</b>口径并同屏说明。
 *       共享计数进排序 = 同楼两家各吃一份整楼人流（比同等人流的独栋店多一倍）。</li>
 *   <li><b>不在营门店记 0</b>：门店状态 ∉ {OPEN, CLOSED} ⇒ 本店到访计入 0。
 *       根因：人不可能"到店"一家停业门店；2026-10-06 只读模拟显示「钜之淋音乐酒吧」(CEASED)
 *       靠 1 条命中在 W=15 时能升到西安同城第 5。admin 侧保留该证据作为门店状态复核线索
 *       （48 号域价值）——回归到公式的只有"排序不允许停业店上浮"。</li>
 * </ol>
 *
 * <h3>时效代价（有意例外）</h3>
 * 由 {@code VenueVisitMetricsScheduler} 定时刷新（默认 30 分钟）⇒ <b>到访项有刷新延迟</b>，
 * 与"当日行为当天反映排序"的现网契约（见 HEAT_SCORE javadoc）不同，登记在 05 号文档。
 *
 * <h3>⚠️ 列名显式声明</h3>
 * Hibernate 驼峰→下划线策略（isUnderscoreRequired）要求大写字母<b>前后均为小写/数字</b>才插下划线，
 * 且不改写数字边界 ⇒ {@code visitUsers30d} 隐式映射为 {@code visitusers30d}，与 V38 的
 * {@code visit_users_30d} 错位、validate 拒启（V33 启动失败同款，见 {@link VenuePresencePing}）。
 * <b>本类全部业务列显式 {@code @Column(name = ...)}，勿删。</b>
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_visit_metrics",
        indexes = {
                @Index(name = "qwt_idx_vvm_refreshed", columnList = "refreshedAt")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "qwt_uk_vvm_venue", columnNames = {"venueId"})
        })
public class VenueVisitMetric extends BaseEntity {

    /** 门店（qwt_venues.id） */
    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    /**
     * 近 30 天到访人数（归因 + 分摊 + 排除内部账号）——<b>排序口径</b>。
     * 小数来源：同址组都在营时按 1/k 分摊（见类注释 ②）。
     */
    @Column(name = "visit_users_30d", nullable = false)
    private BigDecimal visitUsers30d;

    /** 近 7 天到访人数（同口径）；<b>仅展示</b>，不进公式（避免第二处时间项） */
    @Column(name = "visit_users_7d", nullable = false)
    private BigDecimal visitUsers7d;

    /** 同址组规模（含本店，≥1）——口径说明用，<b>不参与计算</b> */
    @Column(name = "group_size", nullable = false)
    private int groupSize;

    /** 本行刷新时刻（JVM 北京时间）；排序侧据此说明时效，禁 DB now() */
    @Column(name = "refreshed_at", nullable = false)
    private LocalDateTime refreshedAt;
}
