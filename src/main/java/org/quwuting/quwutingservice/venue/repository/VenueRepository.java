package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VenueRepository extends JpaRepository<Venue, Long>, JpaSpecificationExecutor<Venue> {

    Optional<Venue> findByIdAndDeletedFalse(Long id);

    /** 批量按 ID 查询场所（消除 N+1：收藏列表等场景需一次性加载多个场所） */
    List<Venue> findByIdInAndDeletedFalse(List<Long> ids);

    /**
     * 列表筛选条件（全部排序变体共用）。
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
     * Haversine 球面距离（km，别名 v 的场所坐标 → 请求者坐标）。
     * <p>
     * <b>只允许在 :latitude / :longitude 恒非 null 的查询中使用</b>（由 Service 保证）：
     * Postgres 将无类型的 null 绑定参数推断为 bytea，radians() 无法解析——见 AGENTS.md
     * 「双查询拆分」坑位。无坐标场景必须改调不带本片段的查询，不要向含本片段的查询传 null 坐标。
     */
    String DISTANCE_KM = """
            6371.0 * acos(
                cos(radians(:latitude)) * cos(radians(v.latitude))
                * cos(radians(v.longitude) - radians(:longitude))
                + sin(radians(:latitude)) * sin(radians(v.latitude)))
            """;

    /**
     * 距离半径筛选（可选，叠加在筛选条件上，与排序方式正交）。
     * <p>
     * radiusKm 为 null（不限）时谓词恒真；有值时仅保留距离 ≤ 半径的场所——
     * 无坐标场所的距离表达式为 NULL，`NULL <= :radiusKm` 为 NULL 自然被排除
     * （"未知距离的场所不承诺在半径内"，语义正确）。
     * <p>
     * 含本片段的查询必须同时携带 :latitude/:longitude（见 {@link #DISTANCE_KM} 约束）。
     */
    String RADIUS_PREDICATE = """
            AND (:radiusKm IS NULL OR
            """ + DISTANCE_KM + """
             <= :radiusKm)
            """;

    /**
     * 热度分（不含距离项）：运营权重 + 收藏数 × 20 + 动态数 × 10。
     * 与「热门场所标记」（findHotVenueIds）同口径——热度是场所属性，不随请求者位置变化。
     */
    String HEAT_SCORE = """
            (v.sortWeight
             + (SELECT COUNT(f) FROM Favorite f WHERE f.venueId = v.id AND f.deleted = false) * 20
             + (SELECT COUNT(p) FROM VenuePost p WHERE p.venueId = v.id AND p.deleted = false) * 10)
            """;

    /**
     * 列表主查询（推荐排序 + 用户坐标）：筛选 + 复合评分排序 + 分页。
     * <p>
     * 排序公式（服务端排序保证分页正确性）：
     * <pre>
     * score = sortWeight（运营权重）
     *       + 收藏数 × 20 + 动态数 × 10（热度）
     *       + 100 / (1 + 距离km)（邻近加成，Haversine）
     * </pre>
     * 距离项使本地场所在全国列表中自然置顶，跨城市时衰减至可忽略，由热度与运营权重决定顺序。
     * <p>
     * <b>latitude / longitude 必须非 null</b>（由 Service 保证）：Postgres 将无类型的 null
     * 绑定参数推断为 bytea，radians() 无法解析。无坐标场景必须改调 {@link #searchRankedNoLocation}，
     * 不要向本方法传 null。
     * <p>
     * {@code radiusKm} 可空：null = 不限（谓词短路，行为与旧版完全一致）；有值 = 距离半径筛选。
     */
    @Query("""
            SELECT v FROM Venue v
            """ + LIST_FILTERS + RADIUS_PREDICATE + """
            ORDER BY (
                v.sortWeight
                + (SELECT COUNT(f) FROM Favorite f WHERE f.venueId = v.id AND f.deleted = false) * 20
                + (SELECT COUNT(p) FROM VenuePost p WHERE p.venueId = v.id AND p.deleted = false) * 10
                + 100.0 / (1.0 +
            """ + DISTANCE_KM + """
            )
            ) DESC
            """)
    Page<Venue> searchRanked(@Param("city") String city,
                             @Param("district") String district,
                             @Param("status") VenueStatus status,
                             @Param("keyword") String keyword,
                             @Param("latitude") double latitude,
                             @Param("longitude") double longitude,
                             @Param("radiusKm") Double radiusKm,
                             Pageable pageable);

    /**
     * 列表主查询（推荐排序，无用户坐标）：复合评分退化为 运营权重 + 热度。
     * 与 {@link #searchRanked} 共用筛选条件，仅排序公式不含距离项。
     */
    @Query("""
            SELECT v FROM Venue v
            """ + LIST_FILTERS + """
            ORDER BY
            """ + HEAT_SCORE + """
            DESC
            """)
    Page<Venue> searchRankedNoLocation(@Param("city") String city,
                                       @Param("district") String district,
                                       @Param("status") VenueStatus status,
                                       @Param("keyword") String keyword,
                                       Pageable pageable);

    /**
     * 列表主查询（距离最近排序）：纯距离升序，仅保留有坐标的场所。
     * <p>
     * 无坐标场所（v.latitude/longitude IS NULL）显式排除——"距离最近"只对距离已知的场所
     * 有意义，未知距离的场所排在列表末尾只会造成困惑。radiusKm 可空（语义同
     * {@link #searchRanked}）；坐标必须非 null（Service 保证，见 {@link #DISTANCE_KM}）。
     * id 兜底 tie-break 保证分页稳定。
     */
    @Query("""
            SELECT v FROM Venue v
            """ + LIST_FILTERS + """
            AND v.latitude IS NOT NULL
            AND v.longitude IS NOT NULL
            """ + RADIUS_PREDICATE + """
            ORDER BY
            """ + DISTANCE_KM + """
            ASC, v.id ASC
            """)
    Page<Venue> searchNearest(@Param("city") String city,
                              @Param("district") String district,
                              @Param("status") VenueStatus status,
                              @Param("keyword") String keyword,
                              @Param("latitude") double latitude,
                              @Param("longitude") double longitude,
                              @Param("radiusKm") Double radiusKm,
                              Pageable pageable);

    /**
     * 列表主查询（热度最高排序，无坐标）：运营权重 + 热度 倒序（同推荐排序无坐标变体，
     * 但与距离无关——选择"热度最高"时排序口径与位置解耦，见 {@link VenueSortMode#HEAT}）。
     */
    @Query("""
            SELECT v FROM Venue v
            """ + LIST_FILTERS + """
            ORDER BY
            """ + HEAT_SCORE + """
            DESC, v.id DESC
            """)
    Page<Venue> searchHeat(@Param("city") String city,
                           @Param("district") String district,
                           @Param("status") VenueStatus status,
                           @Param("keyword") String keyword,
                           Pageable pageable);

    /**
     * 列表主查询（热度最高排序 + 距离半径筛选）：热度口径同 {@link #searchHeat}，
     * 叠加 RADIUS_PREDICATE——热度排序本身不需要坐标，但半径筛选需要以请求者位置为圆心，
     * 故本查询仅在"有定位且选了半径"时被调用（Service 分流，坐标恒非 null）。
     */
    @Query("""
            SELECT v FROM Venue v
            """ + LIST_FILTERS + RADIUS_PREDICATE + """
            ORDER BY
            """ + HEAT_SCORE + """
            DESC, v.id DESC
            """)
    Page<Venue> searchHeatWithinRadius(@Param("city") String city,
                                       @Param("district") String district,
                                       @Param("status") VenueStatus status,
                                       @Param("keyword") String keyword,
                                       @Param("latitude") double latitude,
                                       @Param("longitude") double longitude,
                                       @Param("radiusKm") Double radiusKm,
                                       Pageable pageable);

    /**
     * 列表主查询（最新收录排序）：创建时间倒序（新收录场所优先露出），id 兜底 tie-break。
     * 无坐标变体：排序与筛选均不依赖距离。
     */
    @Query("""
            SELECT v FROM Venue v
            """ + LIST_FILTERS + """
            ORDER BY v.createdAt DESC, v.id DESC
            """)
    Page<Venue> searchNewest(@Param("city") String city,
                             @Param("district") String district,
                             @Param("status") VenueStatus status,
                             @Param("keyword") String keyword,
                             Pageable pageable);

    /**
     * 列表主查询（最新收录排序 + 距离半径筛选）：排序口径同 {@link #searchNewest}，
     * 叠加 RADIUS_PREDICATE（坐标恒非 null，Service 分流保证，见
     * {@link #searchHeatWithinRadius} 注释）。
     */
    @Query("""
            SELECT v FROM Venue v
            """ + LIST_FILTERS + RADIUS_PREDICATE + """
            ORDER BY v.createdAt DESC, v.id DESC
            """)
    Page<Venue> searchNewestWithinRadius(@Param("city") String city,
                                         @Param("district") String district,
                                         @Param("status") VenueStatus status,
                                         @Param("keyword") String keyword,
                                         @Param("latitude") double latitude,
                                         @Param("longitude") double longitude,
                                         @Param("radiusKm") Double radiusKm,
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
     * 热度读模型：单场所全部"单值计数器"的跨表合并投影（供 VenueHeatService.getHeat 使用）。
     * <p>
     * getter 类型约定：TIMESTAMP 列（lateststatuslogtime / latestreporttime）必须声明为
     * java.time.LocalDateTime——Hibernate 6+ 对原生查询的默认映射（见 AGENTS.md「投影接口
     * getter 类型」章节，历史遗留 java.sql.* 类型会运行时抛 UnsupportedOperationException）。
     */
    interface HeatCounters {
        /** 近30天浏览量 PV（含匿名） */
        Long getPv();
        /** 近30天独立用户浏览数 UV（仅已登录去重，COUNT DISTINCT 天然忽略 NULL） */
        Long getUv();
        /** 收藏总数 */
        Long getFavtotal();
        /** 近30天新增收藏 */
        Long getFavrecent();
        /** 动态总数 */
        Long getPosttotal();
        /** 近30天新增动态 */
        Long getPostrecent();
        /** 近30天评分数（score 非空的交互记录数，按 created_at 窗口——改分不刷新窗口，防"定期改分保持计数常青"） */
        Long getRatingcount30d();
        /** 近30天正向 Reaction 总数（仅 Polarity.POSITIVE 的 code，热度公式计入项） */
        Long getPositivereactioncount30d();
        /** 近30天负向 Reaction 总数（仅 Polarity.NEGATIVE 的 code，不计入公式，单独展示负面信号） */
        Long getNegativereactioncount30d();
        /** 评价总人数（去重 userId） */
        Long getRaters();
        /** 近30天暂停营业次数（to_status = SUSPENDED 的状态变迁数） */
        Long getSuspensioncount();
        /** 最近一条状态变迁时间（当前状态持续天数的依据，实时事实，无窗口上界） */
        LocalDateTime getLateststatuslogtime();
        /** TTL 窗口内的活跃状态上报数 */
        Long getReportcount();
        /** TTL 窗口内最新上报时间，null = 无活跃上报 */
        LocalDateTime getLatestreporttime();
    }

    /**
     * 热度计数器 mega-query：一次 DB 往返取回热度公式与状态可信度所需的全部单值统计。
     * <p>
     * 根因：这些计数器分布在 6 张表上（views / favorites / posts / tag_interactions /
     * status_logs / status_reports），早期按表各发一条合并查询，仍需 6 次串行跨洲往返
     * （约 2.1s）。它们都是"以 venueId 为键的单值聚合"，可以收敛为一条 SELECT 内的
     * 标量子查询——Postgres 一次解析执行，网络开销只剩 1 次往返。各子查询均命中
     * (venue_id, ...) 复合索引，库内执行时间为毫秒级。
     * <p>
     * 窗口语义（与 VenueHeatService 保持一致）：
     * <ul>
     *   <li>viewSince/viewUntil：浏览按 view_date 过滤，[30天前的日期, 今天)，即「截至昨日」</li>
     *   <li>windowSince/windowUntil：其余滚动窗口按时间戳过滤，[30天前0点, 今天0点)，即「截至昨日」</li>
     *   <li>reportSince：活跃上报为实时 TTL 窗口（now - 4h），不受「截至昨日」约束</li>
     *   <li>lateststatuslogtime：当前状态的实时事实，全量 MAX，无窗口约束</li>
     * </ul>
     * 收藏趋势（多行时间序列）与满意度（分组均值，依赖 raters 条件触发）形态不同，
     * 不参与本合并，仍为独立查询。
     */
    @Query(value = """
            SELECT
              (SELECT COUNT(*) FROM qwt_venue_views vv
                WHERE vv.venue_id = :venueId AND vv.view_date >= :viewSince AND vv.view_date < :viewUntil) AS pv,
              (SELECT COUNT(DISTINCT vv.user_id) FROM qwt_venue_views vv
                WHERE vv.venue_id = :venueId AND vv.view_date >= :viewSince AND vv.view_date < :viewUntil) AS uv,
              (SELECT COUNT(*) FROM qwt_favorites f
                WHERE f.venue_id = :venueId AND f.deleted = false) AS favtotal,
              (SELECT COUNT(*) FROM qwt_favorites f
                WHERE f.venue_id = :venueId AND f.deleted = false
                  AND f.created_at >= :windowSince AND f.created_at < :windowUntil) AS favrecent,
              (SELECT COUNT(*) FROM qwt_venue_posts p
                WHERE p.venue_id = :venueId AND p.deleted = false) AS posttotal,
              (SELECT COUNT(*) FROM qwt_venue_posts p
                WHERE p.venue_id = :venueId AND p.deleted = false
                  AND p.created_at >= :windowSince AND p.created_at < :windowUntil) AS postrecent,
              (SELECT COUNT(*) FROM qwt_tag_interactions ti
                WHERE ti.venue_id = :venueId AND ti.deleted = false AND ti.score IS NOT NULL
                  AND ti.created_at >= :windowSince AND ti.created_at < :windowUntil) AS ratingcount30d,
              (SELECT COUNT(*) FROM qwt_venue_reactions r
                WHERE r.venue_id = :venueId AND r.deleted = false
                  AND r.reaction_code IN :positiveCodes
                  AND r.created_at >= :windowSince AND r.created_at < :windowUntil) AS positivereactioncount30d,
              (SELECT COUNT(*) FROM qwt_venue_reactions r
                WHERE r.venue_id = :venueId AND r.deleted = false
                  AND r.reaction_code IN :negativeCodes
                  AND r.created_at >= :windowSince AND r.created_at < :windowUntil) AS negativereactioncount30d,
              (SELECT COUNT(DISTINCT ti.user_id) FROM qwt_tag_interactions ti
                WHERE ti.venue_id = :venueId AND ti.deleted = false AND ti.score IS NOT NULL) AS raters,
              (SELECT COUNT(*) FROM qwt_venue_status_logs l
                WHERE l.venue_id = :venueId AND l.to_status = 'SUSPENDED'
                  AND l.created_at >= :windowSince AND l.created_at < :windowUntil) AS suspensioncount,
              (SELECT MAX(l.created_at) FROM qwt_venue_status_logs l
                WHERE l.venue_id = :venueId) AS lateststatuslogtime,
              (SELECT COUNT(*) FROM qwt_venue_status_reports r
                WHERE r.venue_id = :venueId AND r.deleted = false AND r.created_at >= :reportSince) AS reportcount,
              (SELECT MAX(r.created_at) FROM qwt_venue_status_reports r
                WHERE r.venue_id = :venueId AND r.deleted = false AND r.created_at >= :reportSince) AS latestreporttime
            """, nativeQuery = true)
    HeatCounters countHeatCounters(@Param("venueId") Long venueId,
                                   @Param("viewSince") java.time.LocalDate viewSince,
                                   @Param("viewUntil") java.time.LocalDate viewUntil,
                                   @Param("windowSince") LocalDateTime windowSince,
                                   @Param("windowUntil") LocalDateTime windowUntil,
                                   @Param("reportSince") LocalDateTime reportSince,
                                   @Param("positiveCodes") List<String> positiveCodes,
                                   @Param("negativeCodes") List<String> negativeCodes);

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
