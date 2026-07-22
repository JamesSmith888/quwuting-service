package org.quwuting.quwutingservice.favorite.repository;

import org.quwuting.quwutingservice.favorite.entity.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    List<Favorite> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(Long userId);

    Optional<Favorite> findByUserIdAndVenueId(Long userId, Long venueId);

    /** 统计场所的有效收藏数（热度计算用） */
    long countByVenueIdAndDeletedFalse(Long venueId);

    /** 统计时间范围内的新增收藏数（热度趋势用） */
    long countByVenueIdAndDeletedFalseAndCreatedAtAfter(Long venueId, java.time.LocalDateTime since);
}
