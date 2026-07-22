package org.quwuting.quwutingservice.venuepost.repository;

import org.quwuting.quwutingservice.venuepost.entity.VenuePost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenuePostRepository extends JpaRepository<VenuePost, Long> {

    Page<VenuePost> findByVenueIdAndDeletedFalse(Long venueId, Pageable pageable);

    long countByVenueIdAndDeletedFalse(Long venueId);

    /** 统计时间范围内的新增动态数（热度趋势用） */
    long countByVenueIdAndDeletedFalseAndCreatedAtAfter(Long venueId, java.time.LocalDateTime since);
}
