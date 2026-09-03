package org.quwuting.quwutingservice.favorite.repository;

import org.quwuting.quwutingservice.favorite.entity.Favorite;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    Optional<Favorite> findByUserIdAndVenueId(Long userId, Long venueId);

    /**
     * 原子收藏 upsert（2026-08-19 根因修复：替代首次收藏路径的「save + 23505 异常吞掉」——
     * Hibernate flush 失败后事务可能已被标记 rollback-only，并发重复收藏的幂等返回实际变为
     * HTTP 500）。本写法恒 1 次往返、零异常：冲突（含软删行）时 DO UPDATE 复位
     * deleted=false 并清空 unfavorited_at（restore 语义与取消趋势口径：清空后该行不再
     * 被计为一次取消，与 FavoriteService.removeFavorite 的唯一写方约定一致）。
     * 冲突目标用列清单推断（qwt_uk_fav_user_venue 为唯一约束，列推断同样适用）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_favorites (user_id, venue_id, created_at, updated_at, deleted) " +
                   "VALUES (:userId, :venueId, :now, :now, false) " +
                   "ON DUPLICATE KEY UPDATE deleted = false, unfavorited_at = NULL, updated_at = VALUES(updated_at)",
           nativeQuery = true)
    int upsertFavorite(@Param("userId") Long userId,
                       @Param("venueId") Long venueId,
                       @Param("now") LocalDateTime now);

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

    /**
     * 收藏某门店的用户 ID 集合（2026-09-03 收藏联动通知，docs/agents/27「受众放大」：
     * 该店热度确认事件推给收藏者——受益者 = 关注这家店的人，互惠闭环；仅未删收藏行）。
     */
    @Query("SELECT f.userId FROM Favorite f " +
           "WHERE f.venueId = :venueId AND f.deleted = false")
    List<Long> findUserIdsByVenueId(@Param("venueId") Long venueId);
}
