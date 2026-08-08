package org.quwuting.quwutingservice.favorite.repository;

import org.quwuting.quwutingservice.favorite.entity.Favorite;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    Optional<Favorite> findByUserIdAndVenueId(Long userId, Long venueId);

    /**
     * 用户收藏的场所列表（按收藏时间倒序），收藏与场所两表联查、单次 DB 往返。
     * <p>
     * 根因：早期实现分两步——先查收藏列表，再按 venueId 集合批量查场所。
     * 两步各自占用一次跨洲 DB 往返；而收藏与场所是固定内连接关系，
     * 一条 JPQL 即可在库内完成连接与排序（排序键为收藏的 createdAt，非场所字段）。
     * 软删场所（v.deleted=true）自然被连接条件过滤。
     */
    @Query("SELECT v FROM Favorite f, Venue v " +
           "WHERE f.userId = :userId AND f.deleted = false " +
           "AND f.venueId = v.id AND v.deleted = false " +
           "ORDER BY f.createdAt DESC")
    List<Venue> findFavoriteVenuesByUserId(@Param("userId") Long userId);
}
