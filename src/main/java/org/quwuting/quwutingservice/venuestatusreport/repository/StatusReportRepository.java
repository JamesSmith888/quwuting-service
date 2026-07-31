package org.quwuting.quwutingservice.venuestatusreport.repository;

import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface StatusReportRepository extends JpaRepository<VenueStatusReport, Long> {

    /** 查找用户对某场所的活跃报告（未逻辑删除） */
    Optional<VenueStatusReport> findByUserIdAndVenueIdAndDeletedFalse(Long userId, Long venueId);

    /**
     * 活跃报告聚合：合并 COUNT + MAX(createdAt) 为 1 次往返。
     * 活跃 = 未删除且 createdAt >= since（TTL 窗口，由 Service 层计算）。
     */
    @Query("SELECT COUNT(r) as activeCount, MAX(r.createdAt) as latestTime " +
           "FROM VenueStatusReport r " +
           "WHERE r.venueId = :venueId AND r.deleted = false AND r.createdAt >= :since")
    ActiveReportStats countActiveAndLatestTime(@Param("venueId") Long venueId,
                                                @Param("since") LocalDateTime since);

    /**
     * 全局频率限制：统计用户在指定时间窗口内报告的不同场所数。
     * 用于防止恶意用户批量上报所有场所。
     */
    @Query("SELECT COUNT(DISTINCT r.venueId) FROM VenueStatusReport r " +
           "WHERE r.userId = :userId AND r.deleted = false AND r.createdAt >= :since")
    long countDistinctVenuesByUserIdSince(@Param("userId") Long userId,
                                           @Param("since") LocalDateTime since);

    /** 投影接口：活跃报告聚合结果 */
    interface ActiveReportStats {
        Long getActiveCount();
        LocalDateTime getLatestTime();
    }
}
