package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.VenueView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface VenueViewRepository extends JpaRepository<VenueView, Long> {

    /** 去重查询：同一用户同一场所同一天是否已记录 */
    Optional<VenueView> findByVenueIdAndUserIdAndViewDate(Long venueId, Long userId, LocalDate viewDate);

    /** 近 N 天内的浏览记录总数（含匿名，UV+PV 混合口径） */
    @Query("SELECT COUNT(v) FROM VenueView v WHERE v.venueId = :venueId AND v.viewDate >= :since")
    long countByVenueIdSince(@Param("venueId") Long venueId, @Param("since") LocalDate since);

    /** 近 N 天内的独立用户浏览数（仅已登录用户，去重 UV） */
    @Query("SELECT COUNT(DISTINCT v.userId) FROM VenueView v " +
           "WHERE v.venueId = :venueId AND v.viewDate >= :since AND v.userId IS NOT NULL")
    long countDistinctUsersByVenueIdSince(@Param("venueId") Long venueId, @Param("since") LocalDate since);

    /** 单行多列聚合投影：PV + UV */
    interface PvUvStats {
        Long getPv();
        Long getUv();
    }

    /**
     * 单次往返同时获取 PV 和 UV（热度聚合优化）。
     * until 为排他上界——热度统计口径固定为「截至昨日」，见 VenueHeatService 的 statsAsOfDate 约定。
     */
    @Query("SELECT COUNT(v) as pv, COUNT(DISTINCT v.userId) as uv FROM VenueView v " +
           "WHERE v.venueId = :venueId AND v.viewDate >= :since AND v.viewDate < :until")
    PvUvStats countPvAndUvByVenueIdSince(@Param("venueId") Long venueId, @Param("since") LocalDate since, @Param("until") LocalDate until);
}
