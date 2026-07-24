package org.quwuting.quwutingservice.favorite.repository;

import org.quwuting.quwutingservice.favorite.entity.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    List<Favorite> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(Long userId);

    Optional<Favorite> findByUserIdAndVenueId(Long userId, Long venueId);

    /** 统计场所的有效收藏数（热度计算用） */
    long countByVenueIdAndDeletedFalse(Long venueId);

    /** 统计时间范围内的新增收藏数（热度趋势用） */
    long countByVenueIdAndDeletedFalseAndCreatedAtAfter(Long venueId, java.time.LocalDateTime since);

    /** 单次往返同时获取收藏总数和近期新增数（热度聚合优化），返回 Object[]{total, recent} */
    @Query("SELECT COUNT(f), SUM(CASE WHEN f.createdAt >= :since THEN 1 ELSE 0 END) " +
           "FROM Favorite f WHERE f.venueId = :venueId AND f.deleted = false")
    Object[] countTotalAndRecentByVenueId(@Param("venueId") Long venueId, @Param("since") java.time.LocalDateTime since);
}
