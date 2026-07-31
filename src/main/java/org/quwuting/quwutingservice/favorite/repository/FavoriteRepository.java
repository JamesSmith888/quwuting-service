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

    /** 单行多列聚合投影：总数 + 近期新增 */
    interface TotalRecentStats {
        Long getTotal();
        Long getRecent();
    }

    /**
     * 单次往返同时获取收藏总数和窗口内新增数（热度聚合优化）。
     * until 为排他上界——热度统计口径固定为「截至昨日」，避免把当天未过完的部分数据混入近30天窗口
     * （否则今天数据不全，会让最新一天看起来比实际偏低，见 VenueHeatService 的 statsAsOfDate 约定）。
     */
    @Query("SELECT COUNT(f) as total, SUM(CASE WHEN f.createdAt >= :since AND f.createdAt < :until THEN 1 ELSE 0 END) as recent " +
           "FROM Favorite f WHERE f.venueId = :venueId AND f.deleted = false")
    TotalRecentStats countTotalAndRecentByVenueId(@Param("venueId") Long venueId,
                                                  @Param("since") java.time.LocalDateTime since,
                                                  @Param("until") java.time.LocalDateTime until);

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
