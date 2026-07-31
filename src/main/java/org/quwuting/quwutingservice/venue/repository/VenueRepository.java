package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VenueRepository extends JpaRepository<Venue, Long>, JpaSpecificationExecutor<Venue> {

    Optional<Venue> findByIdAndDeletedFalse(Long id);

    /**
     * 列表筛选条件（两个排序变体共用）。
     * 所有参数可空：null 表示不限制；keyword 需调用方预先包装为 %xx%。
     */
    String LIST_FILTERS = """
            WHERE v.deleted = false
              AND (:city IS NULL OR v.city = :city)
              AND (:district IS NULL OR v.district = :district)
              AND (:status IS NULL OR v.status = :status)
              AND (:keyword IS NULL
                   OR v.name LIKE :keyword
                   OR v.address LIKE :keyword
                   OR v.description LIKE :keyword)
            """;

    /**
     * 列表主查询（带用户坐标）：筛选 + 复合评分排序 + 分页。
     * <p>
     * 排序公式（服务端排序保证分页正确性）：
     * <pre>
     * score = sortWeight（运营权重）
     *       + 收藏数 × 20 + 动态数 × 10（热度，与 /venues/{id}/heat 占位公式同向）
     *       + 100 / (1 + 距离km)（邻近加成，Haversine）
     * </pre>
     * 距离项使本地场所在全国列表中自然置顶，跨城市时衰减至可忽略，由热度与运营权重决定顺序。
     * <p>
     * <b>latitude / longitude 必须非 null</b>（由 Service 保证）：Postgres 将无类型的 null
     * 绑定参数推断为 bytea，radians() 无法解析。无坐标场景必须改调 {@link #searchRankedNoLocation}，
     * 不要向本方法传 null。
     */
    @Query("""
            SELECT v FROM Venue v
            """ + LIST_FILTERS + """
            ORDER BY (
                v.sortWeight
                + (SELECT COUNT(f) FROM Favorite f WHERE f.venueId = v.id AND f.deleted = false) * 20
                + (SELECT COUNT(p) FROM VenuePost p WHERE p.venueId = v.id AND p.deleted = false) * 10
                + 100.0 / (1.0 + 6371.0 * acos(
                    cos(radians(:latitude)) * cos(radians(v.latitude))
                    * cos(radians(v.longitude) - radians(:longitude))
                    + sin(radians(:latitude)) * sin(radians(v.latitude))))
            ) DESC
            """)
    Page<Venue> searchRanked(@Param("city") String city,
                             @Param("district") String district,
                             @Param("status") VenueStatus status,
                             @Param("keyword") String keyword,
                             @Param("latitude") double latitude,
                             @Param("longitude") double longitude,
                             Pageable pageable);

    /**
     * 列表主查询（无用户坐标）：复合评分退化为 运营权重 + 热度。
     * 与 {@link #searchRanked} 共用筛选条件，仅排序公式不含距离项。
     */
    @Query("""
            SELECT v FROM Venue v
            """ + LIST_FILTERS + """
            ORDER BY (
                v.sortWeight
                + (SELECT COUNT(f) FROM Favorite f WHERE f.venueId = v.id AND f.deleted = false) * 20
                + (SELECT COUNT(p) FROM VenuePost p WHERE p.venueId = v.id AND p.deleted = false) * 10
            ) DESC
            """)
    Page<Venue> searchRankedNoLocation(@Param("city") String city,
                                       @Param("district") String district,
                                       @Param("status") VenueStatus status,
                                       @Param("keyword") String keyword,
                                       Pageable pageable);

    /** 城市维度统计：有场所的城市按场所数倒序（供前端"热门城市"选择，数据驱动、免维护） */
    @Query("""
            SELECT v.city AS city, COUNT(v) AS venueCount
            FROM Venue v
            WHERE v.deleted = false
            GROUP BY v.city
            ORDER BY COUNT(v) DESC
            """)
    List<CityCountProjection> findCityStats();

    /** findCityStats 投影 */
    interface CityCountProjection {
        String getCity();
        Long getVenueCount();
    }

    /**
     * 查询城市内热门场所 ID 集合（热度排名前 20%，至少 1 家/城市）。
     * <p>
     * 排序口径与列表查询一致：sortWeight + 收藏数×20 + 动态数×10（不含距离项，距离是用户维度）。
     * 使用 PostgreSQL 窗口函数 ROW_NUMBER + COUNT 实现"城市内相对排名"，
     * 避免跨城市基数差异（上海普通场所的收藏量可能 > 小城市最热门场所）。
     * <p>
     * 数据规模小（每城市 5~30 家），单次全表查询无性能压力。
     */
    @Query(value = """
            SELECT id FROM (
                SELECT v.id,
                       ROW_NUMBER() OVER (PARTITION BY v.city ORDER BY (
                           v.sort_weight
                           + (SELECT COUNT(*) FROM qwt_favorites f WHERE f.venue_id = v.id AND f.deleted = false) * 20
                           + (SELECT COUNT(*) FROM qwt_venue_posts p WHERE p.venue_id = v.id AND p.deleted = false) * 10
                       ) DESC, v.id) AS rn,
                       COUNT(*) OVER (PARTITION BY v.city) AS city_total
                FROM qwt_venues v
                WHERE v.deleted = false
            ) ranked
            WHERE rn <= GREATEST(1, CEIL(city_total * 0.2))
            """, nativeQuery = true)
    List<Long> findHotVenueIds();
}
