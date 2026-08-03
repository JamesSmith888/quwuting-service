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

    /**
     * 单行投影：某一天的新增收藏数（收藏趋势图用）。
     * getter 类型必须与 Hibernate 对原生查询列的实际映射类型一致——Hibernate 6+ 对 DATE 列默认映射为
     * java.time.LocalDate（而非历史遗留的 java.sql.Date），声明为 java.sql.Date 会在
     * ProjectingMethodInterceptor 转换阶段抛 UnsupportedOperationException（2026-07-31 热度接口 500 事故根因）。
     */
    interface DailyFavoriteCount {
        java.time.LocalDate getDay();
        Long getCount();
    }

    /**
     * 按天统计场所近期新增收藏数（收藏趋势图用）。
     * 使用原生 SQL：JPQL 无法表达按 date_trunc 分组，且需要 Postgres 的 ::date 转换。
     * until 为排他上界，配合 VenueHeatService 的「截至昨日」窗口约定。
     */
    @Query(value = "SELECT date_trunc('day', created_at)::date AS day, COUNT(*) AS count " +
                   "FROM qwt_favorites WHERE venue_id = :venueId AND deleted = false AND created_at >= :since AND created_at < :until " +
                   "GROUP BY day ORDER BY day",
           nativeQuery = true)
    List<DailyFavoriteCount> countDailyFavoritesSince(@Param("venueId") Long venueId,
                                                      @Param("since") java.time.LocalDateTime since,
                                                      @Param("until") java.time.LocalDateTime until);
}
