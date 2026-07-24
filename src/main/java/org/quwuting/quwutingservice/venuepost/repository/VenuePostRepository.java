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

    /** 单次往返同时获取动态总数和近期新增数（热度聚合优化），返回 Object[]{total, recent} */
    @Query("SELECT COUNT(p), SUM(CASE WHEN p.createdAt >= :since THEN 1 ELSE 0 END) " +
           "FROM VenuePost p WHERE p.venueId = :venueId AND p.deleted = false")
    Object[] countTotalAndRecentByVenueId(@Param("venueId") Long venueId, @Param("since") java.time.LocalDateTime since);
}
