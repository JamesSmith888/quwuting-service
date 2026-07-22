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
}
