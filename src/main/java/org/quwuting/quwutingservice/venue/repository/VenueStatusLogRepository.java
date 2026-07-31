package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VenueStatusLogRepository extends JpaRepository<VenueStatusLog, Long> {

    /** 查询场所最近一条状态变迁记录（用于计算当前状态持续天数） */
    Optional<VenueStatusLog> findTopByVenueIdOrderByCreatedAtDesc(Long venueId);

    /** 统计时间范围内进入指定状态的次数（用于"近 N 天暂停营业次数"） */
    @Query("SELECT COUNT(l) FROM VenueStatusLog l " +
           "WHERE l.venueId = :venueId AND l.toStatus IN :statuses AND l.createdAt >= :since")
    long countByVenueIdAndToStatusInSince(@Param("venueId") Long venueId,
                                          @Param("statuses") List<VenueStatus> statuses,
                                          @Param("since") LocalDateTime since);

    /**
     * 单行多列聚合投影：暂停次数 + 最近状态变迁时间。
     * getter 类型必须与 Hibernate 对原生查询 TIMESTAMP 列的实际映射类型一致——Hibernate 6+ 默认映射为
     * java.time.LocalDateTime（而非历史遗留的 java.sql.Timestamp），与 FavoriteRepository.DailyFavoriteCount 同类
     * 问题（2026-07-31 热度接口 500 事故根因）。
     */
    interface SuspensionStats {
        Long getSuspensioncount();
        LocalDateTime getLatestcreatedat();
    }

    /**
     * 单次往返同时获取暂停次数和最近状态变迁时间（热度聚合优化）。
     * 使用原生 SQL：JPQL 无法在单条投影中同时表达条件 COUNT + MAX。
     * until 仅约束 suspensioncount 窗口上界（「截至昨日」，见 VenueHeatService 的 statsAsOfDate 约定）；
     * latestcreatedat 代表当前状态的实时事实（当前状态持续天数依赖它），不施加窗口上界。
     */
    @Query(value = "SELECT COUNT(CASE WHEN l.to_status = 'SUSPENDED' AND l.created_at >= :since AND l.created_at < :until THEN 1 END) as suspensioncount, " +
                   "MAX(l.created_at) as latestcreatedat " +
                   "FROM qwt_venue_status_logs l WHERE l.venue_id = :venueId",
           nativeQuery = true)
    SuspensionStats countSuspensionsAndLatestTime(@Param("venueId") Long venueId,
                                                  @Param("since") LocalDateTime since,
                                                  @Param("until") LocalDateTime until);
}
