package org.quwuting.quwutingservice.venuepost.repository;

import org.quwuting.quwutingservice.venuepost.entity.VenuePost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VenuePostRepository extends JpaRepository<VenuePost, Long> {

    Page<VenuePost> findByVenueIdAndDeletedFalse(Long venueId, Pageable pageable);

    long countByVenueIdAndDeletedFalse(Long venueId);

    /** 统计时间范围内的新增动态数（热度趋势用） */
    long countByVenueIdAndDeletedFalseAndCreatedAtAfter(Long venueId, java.time.LocalDateTime since);

    /** 单行多列聚合投影：总数 + 近期新增 */
    interface TotalRecentStats {
        Long getTotal();
        Long getRecent();
    }

    /**
     * 单次往返同时获取动态总数和窗口内新增数（热度聚合优化）。
     * until 为排他上界——热度统计口径固定为「截至昨日」，见 VenueHeatService 的 statsAsOfDate 约定。
     */
    @Query("SELECT COUNT(p) as total, SUM(CASE WHEN p.createdAt >= :since AND p.createdAt < :until THEN 1 ELSE 0 END) as recent " +
           "FROM VenuePost p WHERE p.venueId = :venueId AND p.deleted = false")
    TotalRecentStats countTotalAndRecentByVenueId(@Param("venueId") Long venueId,
                                                  @Param("since") java.time.LocalDateTime since,
                                                  @Param("until") java.time.LocalDateTime until);
}
