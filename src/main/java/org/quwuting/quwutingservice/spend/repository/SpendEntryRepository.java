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
    //
    // 2026-10-13（统计口径身份化）：全部聚合按 `direction` 参数过滤——客人看支出、
    // 舞伴看收入（生产取证：舞伴侧收入此前不进任何图表）。参数取服务端枚举名
    // （EXPENSE/INCOME），由 SpendService 解析后以字符串下传（native query 显式
    // 绑定字符串，避免枚举绑定形态的分歧）。

    /** 月度汇总：总额/条目数只计**请求方向**，场次 = 全部 DANCE 条目（含两个方向
     *  ——"这个月跳了几场"对客人/舞伴两类身份都成立，44 号 §24） */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN direction = :direction THEN amount ELSE 0 END), 0) AS total,
                   COALESCE(SUM(CASE WHEN direction = :direction THEN 1 ELSE 0 END), 0) AS entryCount,
                   COALESCE(SUM(source = 'DANCE'), 0) AS sessionCount
            FROM qwt_spend_entries
            WHERE user_id = :userId AND deleted = 0 AND ts >= :start AND ts < :end
            """, nativeQuery = true)
    MonthSummaryRow sumByMonth(@Param("userId") Long userId,
                               @Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end,
                               @Param("direction") String direction);

    /** 分类占比：只返回**请求方向**的有数据行（缺类按 SpendCategory.forDirection 补零
     *  ——分类集服务端枚举权威） */
    @Query(value = """
            SELECT category AS category,
                   COALESCE(SUM(amount), 0) AS total,
                   COUNT(*) AS entryCount
            FROM qwt_spend_entries
            WHERE user_id = :userId AND deleted = 0 AND direction = :direction AND ts >= :start AND ts < :end
            GROUP BY category
            ORDER BY total DESC
            """, nativeQuery = true)
    List<CategorySliceRow> sumByCategory(@Param("userId") Long userId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end,
                                         @Param("direction") String direction);

    /** 门店 TOP（请求方向；按金额降序，venue_name 快照随行——门店改名不影响历史行归属展示） */
    @Query(value = """
            SELECT venue_id AS venueId,
                   venue_name AS venueName,
                   COALESCE(SUM(amount), 0) AS total
            FROM qwt_spend_entries
            WHERE user_id = :userId AND deleted = 0 AND direction = :direction AND ts >= :start AND ts < :end
              AND venue_id IS NOT NULL
            GROUP BY venue_id, venue_name
            ORDER BY total DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<VenueSliceRow> sumByVenueTop(@Param("userId") Long userId,
                                      @Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end,
                                      @Param("limit") int limit,
                                      @Param("direction") String direction);

    /** 未关联桶（请求方向；独立展示不并入 OTHER——数据缺口可见，44 号文档 §13.4） */
    @Query(value = """
            SELECT COALESCE(SUM(amount), 0) AS total
            FROM qwt_spend_entries
            WHERE user_id = :userId AND deleted = 0 AND direction = :direction AND ts >= :start AND ts < :end
              AND venue_id IS NULL
            """, nativeQuery = true)
    BigDecimal sumUnlinked(@Param("userId") Long userId,
                           @Param("start") LocalDateTime start,
                           @Param("end") LocalDateTime end,
                           @Param("direction") String direction);

    /** 趋势（请求方向；跨月区间 GROUP BY 月；无数据月由服务端补零，见 SpendService#buildTrend） */
    @Query(value = """
            SELECT DATE_FORMAT(ts, '%Y-%m') AS month,
                   COALESCE(SUM(amount), 0) AS total
            FROM qwt_spend_entries
            WHERE user_id = :userId AND deleted = 0 AND direction = :direction AND ts >= :start AND ts < :end
            GROUP BY month
            ORDER BY month ASC
            """, nativeQuery = true)
    List<MonthPointRow> sumByMonthTrend(@Param("userId") Long userId,
                                        @Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end,
                                        @Param("direction") String direction);

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
