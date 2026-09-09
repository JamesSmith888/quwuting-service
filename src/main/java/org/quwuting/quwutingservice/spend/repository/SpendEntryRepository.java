package org.quwuting.quwutingservice.spend.repository;

import org.quwuting.quwutingservice.spend.entity.SpendEntryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpendEntryRepository extends JpaRepository<SpendEntryEntity, Long> {

    /** 幂等定位：同一用户的客户端条目 id 全局唯一（含软删行——sync 需能恢复） */
    Optional<SpendEntryEntity> findByUserIdAndClientEntryId(Long userId, String clientEntryId);

    /**
     * 增量拉取（跨设备/换机恢复）：updated_at ≥ cursor 的全部行（<b>含软删行</b>——
     * 客户端据 deleted 标记删除本地副本），游标语义与 venues/snapshot 同模式。
     * 软删行仅返回轻量字段需要的信息，量级天然受限（单人账目）。
     */
    @Query("""
            SELECT e FROM SpendEntry e
            WHERE e.userId = :userId AND e.updatedAt >= :cursor
            ORDER BY e.updatedAt ASC
            """)
    List<SpendEntryEntity> findIncremental(@Param("userId") Long userId,
                                           @Param("cursor") LocalDateTime cursor,
                                           Pageable pageable);

    // ── overview 聚合（全部服务端 SQL 算，禁拉明细前端算，44 号文档 §14.3） ──

    /** 月度汇总：总额 / 条目数 / 场次（DANCE 条目数——口径与客户端 summarizeMonth 一致，禁漂移） */
    @Query(value = """
            SELECT COALESCE(SUM(amount), 0) AS total,
                   COUNT(*) AS entryCount,
                   COALESCE(SUM(source = 'DANCE'), 0) AS sessionCount
            FROM qwt_spend_entries
            WHERE user_id = :userId AND deleted = 0 AND ts >= :start AND ts < :end
            """, nativeQuery = true)
    MonthSummaryRow sumByMonth(@Param("userId") Long userId,
                               @Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    /** 分类占比：固定 6 类只返回有数据行（缺类前端按 0 补齐——分类集服务端枚举权威） */
    @Query(value = """
            SELECT category AS category,
                   COALESCE(SUM(amount), 0) AS total,
                   COUNT(*) AS entryCount
            FROM qwt_spend_entries
            WHERE user_id = :userId AND deleted = 0 AND ts >= :start AND ts < :end
            GROUP BY category
            ORDER BY total DESC
            """, nativeQuery = true)
    List<CategorySliceRow> sumByCategory(@Param("userId") Long userId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    /** 门店 TOP（按金额降序，venue_name 快照随行——门店改名不影响历史行归属展示） */
    @Query(value = """
            SELECT venue_id AS venueId,
                   venue_name AS venueName,
                   COALESCE(SUM(amount), 0) AS total
            FROM qwt_spend_entries
            WHERE user_id = :userId AND deleted = 0 AND ts >= :start AND ts < :end
              AND venue_id IS NOT NULL
            GROUP BY venue_id, venue_name
            ORDER BY total DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<VenueSliceRow> sumByVenueTop(@Param("userId") Long userId,
                                      @Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end,
                                      @Param("limit") int limit);

    /** 未关联桶（独立展示不并入 OTHER——数据缺口可见，44 号文档 §13.4） */
    @Query(value = """
            SELECT COALESCE(SUM(amount), 0) AS total
            FROM qwt_spend_entries
            WHERE user_id = :userId AND deleted = 0 AND ts >= :start AND ts < :end
              AND venue_id IS NULL
            """, nativeQuery = true)
    BigDecimal sumUnlinked(@Param("userId") Long userId,
                           @Param("start") LocalDateTime start,
                           @Param("end") LocalDateTime end);

    /** 趋势（跨月区间 GROUP BY 月；无数据月由服务端补零，见 SpendService#buildTrend） */
    @Query(value = """
            SELECT DATE_FORMAT(ts, '%Y-%m') AS month,
                   COALESCE(SUM(amount), 0) AS total
            FROM qwt_spend_entries
            WHERE user_id = :userId AND deleted = 0 AND ts >= :start AND ts < :end
            GROUP BY month
            ORDER BY month ASC
            """, nativeQuery = true)
    List<MonthPointRow> sumByMonthTrend(@Param("userId") Long userId,
                                        @Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end);

    // ── 投影接口（native 聚合行） ──

    interface MonthSummaryRow {
        BigDecimal getTotal();

        long getEntryCount();

        long getSessionCount();
    }

    interface CategorySliceRow {
        String getCategory();

        BigDecimal getTotal();

        long getEntryCount();
    }

    interface VenueSliceRow {
        Long getVenueId();

        String getVenueName();

        BigDecimal getTotal();
    }

    interface MonthPointRow {
        String getMonth();

        BigDecimal getTotal();
    }
}
