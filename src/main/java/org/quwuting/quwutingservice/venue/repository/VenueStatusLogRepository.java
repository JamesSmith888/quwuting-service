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
     * 单次往返同时获取暂停次数和最近状态变迁时间（热度聚合优化）。
     * 返回 Object[]{suspensionCount, latestCreatedAt}。
     * 使用原生 SQL：JPQL 无法在单条投影中同时表达条件 COUNT + MAX。
     */
    @Query(value = "SELECT COUNT(CASE WHEN l.to_status = 'SUSPENDED' AND l.created_at >= :since THEN 1 END), " +
                   "MAX(l.created_at) " +
                   "FROM qwt_venue_status_logs l WHERE l.venue_id = :venueId",
           nativeQuery = true)
    Object[] countSuspensionsAndLatestTime(@Param("venueId") Long venueId, @Param("since") LocalDateTime since);
}
