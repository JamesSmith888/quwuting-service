package org.quwuting.quwutingservice.venuecrowd.repository;

import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 门店热度上报仓储（2026-08-29，docs/agents/27-venue-crowd-report.md）。
 * <p>
 * upsert = 原生 INSERT ... ON CONFLICT（部分唯一索引 qwt_idx_crowd_reports_user_day
 * 谓词 WHERE deleted=false）——幂等确定性：同日再次上报 = UPDATE 原行 +
 * modify_count+1，不产生新行；并发（同用户同店同日在飞请求）由数据库冲突处理兜底，
 * 无需应用层锁。
 */
public interface VenueCrowdReportRepository extends JpaRepository<VenueCrowdReport, Long> {

    /** 聚合窗口扫描：最近 N 小时（CROWD_WINDOW_HOURS=2）该店全部未删上报 */
    List<VenueCrowdReport> findByVenueIdAndCreatedAtAfterAndDeletedFalse(
            @Param("venueId") Long venueId, @Param("since") LocalDateTime since);

    /** 我今天的上报（详情页「已上报 · 可改一下」mine 态判定） */
    List<VenueCrowdReport> findByVenueIdAndUserIdAndReportDateAndDeletedFalse(
            @Param("venueId") Long venueId, @Param("userId") Long userId,
            @Param("reportDate") LocalDate reportDate);

    /**
     * 批量每店独立上报人数（2026-08-29 列表角标数据源）：一次 IN + 窗口过滤 +
     * GROUP BY venue_id 覆盖整页，避免逐店 COUNT 的 N+1（同
     * VenueViewRepository#countByVenueIds 批量模式）。窗口 = 最近
     * CROWD_WINDOW_HOURS 小时（2h，与详情聚合同口径）。返回 Object[]{venueId, count}。
     */
    @Query("SELECT r.venueId, COUNT(DISTINCT r.userId) FROM VenueCrowdReport r " +
            "WHERE r.venueId IN :venueIds AND r.createdAt >= :since AND r.deleted = false " +
            "GROUP BY r.venueId")
    List<Object[]> countDistinctUsersByVenueIdsSince(
            @Param("venueIds") Collection<Long> venueIds, @Param("since") LocalDateTime since);

    /** 窗口内全量上报（管理端按店聚合用，数据量小——日活 5~36 规模，内存分组可接受） */
    List<VenueCrowdReport> findByCreatedAtAfterAndDeletedFalse(@Param("since") LocalDateTime since);

    /**
     * 幂等 upsert（每日一记）：INSERT 新行 / ON CONFLICT UPDATE 原行（modify_count+1）。
     * 冲突目标 = 部分唯一索引（venue_id, user_id, report_date）WHERE deleted=false。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_crowd_reports " +
            "(id, created_at, updated_at, deleted, venue_id, user_id, female_level, male_level, report_date, modify_count) " +
            "VALUES (nextval('qwt_venue_crowd_reports_id_seq'), now(), now(), false, :venueId, :userId, :femaleLevel, :maleLevel, :reportDate, 0) " +
            "ON CONFLICT (venue_id, user_id, report_date) WHERE deleted = false " +
            "DO UPDATE SET female_level = EXCLUDED.female_level, " +
            "male_level = EXCLUDED.male_level, " +
            "modify_count = qwt_venue_crowd_reports.modify_count + 1, " +
            "updated_at = now()",
            nativeQuery = true)
    void upsert(@Param("venueId") Long venueId, @Param("userId") Long userId,
                @Param("femaleLevel") int femaleLevel, @Param("maleLevel") Integer maleLevel,
                @Param("reportDate") LocalDate reportDate);
}
