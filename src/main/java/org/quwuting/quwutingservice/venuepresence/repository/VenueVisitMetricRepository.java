package org.quwuting.quwutingservice.venuepresence.repository;

import org.quwuting.quwutingservice.venuepresence.entity.VenueVisitMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 门店到访指标物化汇总仓储（2026-10-06，V38；文档 = docs/agents/52-venue-presence.md「到访进排序」）。
 * <p>
 * 写侧 = 定时刷新任务专用（先整表清零、再逐店 upsert ⇒ 窗口外自然淘汰，不留陈旧值）；
 * 读侧 = 热度公式标量子查询（在 {@code VenueRepository} 的 JPQL/native 片段内直接引用表名/实体名）。
 * <p>
 * ⚠️ <b>2026-10-08 起本表消费方只剩热度公式</b>：C 侧「到店足迹」文案改由
 * {@code VenuePresenceService#nearbyVisitSummaries} 供给（「附近的足迹」= 附近并集口径，
 * 与本表存的 1/k 分摊排序份额<b>刻意不同</b>，见 52 号）——原「列表徽标批量取数」方法
 * （{@code findVisitUsersByVenueIds}）随迁删除。{@code share_in_operation} 列自此亦无消费方
 * （列保留，V40 迁移不回改；刷新仍写入以保持行自洽）。
 */
public interface VenueVisitMetricRepository extends JpaRepository<VenueVisitMetric, Long> {

    /**
     * 整表清零（刷新任务第一步）：把全部行的到访人数归零并刷新时间戳。
     * <p>
     * <b>为什么要先清零</b>：到访是 30 天滚动窗口——某家店上个月有到访、这个月没有时，
     * 若只做 upsert，它的行会永远停在旧值（排序被一个早已过期的信号持续加分）。
     * 清零 + upsert 的组合让"窗内无到访"自然回落为 0，且无需额外记录"上轮有哪些店"。
     * <p>
     * 代价 = 每轮 N 次 UPDATE（N = 有到访历史门店数，稀疏量级）；到万级时改为
     * 差分更新（对比上轮 refreshed_at），触发条件登记在 52 号 §「量级边界」。
     * <p>
     * ⚠️ {@code updated_at} 必须一并显式写：native 语句绕过 Hibernate 的
     * {@code @UpdateTimestamp}，漏写会留下过期时间戳。
     * <p>
     * ⚠️ 只清零三个业务值，<b>不动</b> {@code group_size}/{@code share_in_operation}：
     * 这两列是"最近一轮被计算到时"的口径说明字段，行若本轮未被 upsert 会保持旧值
     * （消费方解读时须以 {@code refreshed_at} 与三数是否全 0 结合判断，见 52 号）。
     */
    @Modifying
    @Query(value = "UPDATE qwt_venue_visit_metrics "
            + "SET visit_users_30d = 0, visit_users_7d = 0, visit_events_30d = 0, "
            + "refreshed_at = :now, updated_at = :now "
            + "WHERE deleted = false",
            nativeQuery = true)
    void resetAll(@Param("now") LocalDateTime now);

    /**
     * 逐店 upsert（刷新任务第二步）：冲突键 = {@code UNIQUE(venue_id)}。
     * 已存在的行只刷新三个业务值与时间戳（{@code created_at} 保留首见）。
     * <p>
     * ⚠️ {@code visit_events_30d} 与两个数列<b>同批写入</b>：三者共用同一趟归因
     * （同attribution / 同分摊 / 同排除集），分开写会出现"人数更新了、次数还是上一轮"
     * 的陈旧对（且这种错不会报错，只会让卡片文案自相矛盾）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_visit_metrics "
            + "(created_at, updated_at, deleted, venue_id, visit_users_30d, visit_users_7d, visit_events_30d, group_size, share_in_operation, refreshed_at) "
            + "VALUES (:now, :now, false, :venueId, :visitUsers30d, :visitUsers7d, :visitEvents30d, :groupSize, :shareInOperation, :now) "
            + "ON DUPLICATE KEY UPDATE "
            + "visit_users_30d = VALUES(visit_users_30d), visit_users_7d = VALUES(visit_users_7d), "
            + "visit_events_30d = VALUES(visit_events_30d), "
            + "group_size = VALUES(group_size), share_in_operation = VALUES(share_in_operation), "
            + "refreshed_at = VALUES(refreshed_at), updated_at = VALUES(updated_at)",
            nativeQuery = true)
    void upsert(@Param("venueId") Long venueId,
                @Param("visitUsers30d") BigDecimal visitUsers30d,
                @Param("visitUsers7d") BigDecimal visitUsers7d,
                @Param("visitEvents30d") BigDecimal visitEvents30d,
                @Param("groupSize") int groupSize,
                @Param("shareInOperation") boolean shareInOperation,
                @Param("now") LocalDateTime now);
}
