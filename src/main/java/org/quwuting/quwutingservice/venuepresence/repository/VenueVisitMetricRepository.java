package org.quwuting.quwutingservice.venuepresence.repository;

import org.quwuting.quwutingservice.venuepresence.entity.VenueVisitMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 门店到访指标物化汇总仓储（2026-10-06，V38；文档 = docs/agents/52-venue-presence.md「到访进排序」）。
 * <p>
 * 写侧 = 定时刷新任务专用（先整表清零、再逐店 upsert ⇒ 窗口外自然淘汰，不留陈旧值）；
 * 读侧 = 公式标量子查询（在 {@code VenueRepository} 的 JPQL/native 片段内直接引用表名/实体名）
 * 与列表徽标批量取数（本接口）。
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
     */
    @Modifying
    @Query(value = "UPDATE qwt_venue_visit_metrics "
            + "SET visit_users_30d = 0, visit_users_7d = 0, refreshed_at = :now, updated_at = :now "
            + "WHERE deleted = false",
            nativeQuery = true)
    void resetAll(@Param("now") LocalDateTime now);

    /**
     * 逐店 upsert（刷新任务第二步）：冲突键 = {@code UNIQUE(venue_id)}。
     * 已存在的行只刷新三个业务值与时间戳（{@code created_at} 保留首见）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_visit_metrics "
            + "(created_at, updated_at, deleted, venue_id, visit_users_30d, visit_users_7d, group_size, refreshed_at) "
            + "VALUES (:now, :now, false, :venueId, :visitUsers30d, :visitUsers7d, :groupSize, :now) "
            + "ON DUPLICATE KEY UPDATE "
            + "visit_users_30d = VALUES(visit_users_30d), visit_users_7d = VALUES(visit_users_7d), "
            + "group_size = VALUES(group_size), refreshed_at = VALUES(refreshed_at), updated_at = VALUES(updated_at)",
            nativeQuery = true)
    void upsert(@Param("venueId") Long venueId,
                @Param("visitUsers30d") BigDecimal visitUsers30d,
                @Param("visitUsers7d") BigDecimal visitUsers7d,
                @Param("groupSize") int groupSize,
                @Param("now") LocalDateTime now);

    /**
     * 整页批量取数（列表卡片徽标，防 N+1）：<b>排序口径</b>的分摊后到访人数。
     * 返回 Object[]{venueId, visitUsers30d}；无到访的门店不在结果里（调用方按缺席处理）。
     */
    @Query("SELECT m.venueId, m.visitUsers30d FROM VenueVisitMetric m "
            + "WHERE m.deleted = false AND m.venueId IN :venueIds")
    List<Object[]> findVisitUsersByVenueIds(@Param("venueIds") Collection<Long> venueIds);
}
