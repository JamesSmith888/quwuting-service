package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.config.VenueHeatWeights;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface VenueRepository extends JpaRepository<Venue, Long>, JpaSpecificationExecutor<Venue> {

    Optional<Venue> findByIdAndDeletedFalse(Long id);

    /** 批量按 ID 查询场所（消除 N+1：收藏列表等场景需一次性加载多个场所） */
    List<Venue> findByIdInAndDeletedFalse(List<Long> ids);

    /**
     * 列表筛选条件（全部排序变体共用）。
     * 所有参数可空：null 表示不限制；keyword 需调用方预先包装为 %xx%。
     * <p>
     * {@code hotOnly} / {@code hotIds}（2026-08-08 新增「热门」快捷筛选）：
     * 热门筛选 = 仅保留热门场所（ID ∈ 城市内 top 20% 且 热度分 ≥ 门槛的集合，
     * 集合由 {@link VenueLookupService#getHotVenueIds} 计算，5min 缓存）。
     * {@code hotOnly=false} 时谓词短路恒真（不筛选，默认口径=不做隐式过滤）；
     * {@code hotOnly=true} 时按集合过滤，集合为空（无热门场所）则 IN 空集恒假——
     * 返回空列表而非报错，语义正确。使用本片段的查询方法必须声明这两个参数
     * （boolean + Set&lt;Long&gt;，禁 null，Service 层保证）。
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
              AND (:hotOnly = false OR v.id IN :hotIds)
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
     * 热度分（不含距离项）：运营权重 + 「行为热度」。
     * <p>
     * <b>2026-08-08 口径统一</b>（修复列表/详情双口径分叉）：本片段是
     * {@link org.quwuting.quwutingservice.venue.service.VenueHeatService#computeHeat}
     * 热度公式的<b>行为部分</b>镜像——近30天浏览×1 + 收藏总数×10 + 近30天新增收藏×15
     * + 动态总数×5 + 近30天评分数×8 + 近30天正向 Reaction×3
     * <b>+ 近30天收到积分 × :pointsWeight（2026-08-10 V2 新增，权重来自配置
     * app.points.heat-weight，运营校准对象）</b>。满意度偏移（口碑微调 ±80）仅参与
     * 热度页综合展示，不进列表排序——排序看"行为热度"，口碑在热度页呈现
     * （权重唯一事实源 = {@link org.quwuting.quwutingservice.config.VenueHeatWeights}
     * + PointsProperties，调整权重必须同步本片段与 findHotVenueIds 双处镜像，
     * 见后端 AGENTS.md「场所热度」章节）。
     * <p>
     * 窗口统一锚定「截至昨日」（与 VenueHeatService 的 [since30d, today) 一致）：
     * CURRENT_DATE 为今天（服务器时区 Asia/Shanghai，见 application.yaml），排他上界 =
     * 今天 0 点。同一天内多次请求结果稳定，不随请求时刻漂移。
     * <p>
     * 注意：本片段引用 {@code :positiveCodes}（正向 code 列表，来自
     * ReactionCode.positiveCodeNames()）与 {@code :pointsWeight}（积分权重，
     * 来自 PointsProperties）——使用本片段的查询方法必须声明这两个参数。
     */
    /**
     * JPQL 子查询注意：
     * <ul>
     *   <li>根实体必须用<b>实体名</b>（VenueView/Favorite/...），列引用必须用<b>Java 属性名</b>
     *       （camelCase）——本片段是 HQL 字符串（非 nativeQuery），写数据库表名（qwt_venue_views 等）
     *       会在启动期查询校验时报 UnknownEntityException。nativeQuery 查询（如 findHotVenueIds /
     *       countHeatCounters）不受此约束，仍用表名。</li>
     *   <li>HQL 时间量减法必须带单位后缀（{@code CURRENT_DATE - 30 day}）；裸整数
     *       （{@code CURRENT_DATE - 30}）会被 Hibernate 7 报 SemanticException
     *       "Operand of - is of type 'java.lang.Integer' which is not a temporal amount"。
     *       此处窗口 = [今天-30天, 今天)，即「截至昨日」30 天。</li>
     *   <li>积分目标类型用<b>全限定枚举字面量</b>（HQL 标准做法，无需参数）——
     *       与 {@link org.quwuting.quwutingservice.points.entity.PointsTransaction}
     *       的 targetType 枚举字段比较。</li>
     * </ul>
     */
    String HEAT_SCORE = """
            (v.sortWeight
             + (SELECT COUNT(*) FROM VenueView vv
                WHERE vv.venueId = v.id AND vv.viewDate >= (CURRENT_DATE - 30 day) AND vv.viewDate < CURRENT_DATE) * """
            + VenueHeatWeights.VIEW + """
             + (SELECT COUNT(*) FROM Favorite f
                WHERE f.venueId = v.id AND f.deleted = false) * """
            + VenueHeatWeights.FAVORITE + """
             + (SELECT COUNT(*) FROM Favorite f2
                WHERE f2.venueId = v.id AND f2.deleted = false
                  AND f2.createdAt >= (CURRENT_DATE - 30 day) AND f2.createdAt < CURRENT_DATE) * """
            + VenueHeatWeights.NEW_FAVORITE + """
             + (SELECT COUNT(*) FROM VenuePost p
                WHERE p.venueId = v.id AND p.deleted = false) * """
            + VenueHeatWeights.POST + """
             + (SELECT COUNT(*) FROM TagInteraction ti
                WHERE ti.venueId = v.id AND ti.deleted = false AND ti.score IS NOT NULL
                  AND ti.createdAt >= (CURRENT_DATE - 30 day) AND ti.createdAt < CURRENT_DATE) * """
            + VenueHeatWeights.RATING + """
             + (SELECT COUNT(*) FROM VenueReaction r
                WHERE r.venueId = v.id AND r.deleted = false
                  AND r.reactionCode IN :positiveCodes
                  AND r.createdAt >= (CURRENT_DATE - 30 day) AND r.createdAt < CURRENT_DATE) * """
            + VenueHeatWeights.REACTION + """
             + (SELECT COALESCE(SUM(-pt.delta), 0) FROM PointsTransaction pt
                WHERE pt.targetType = org.quwuting.quwutingservice.points.enums.PointsTargetType.VENUE
                  AND pt.targetId = v.id AND pt.delta < 0
                  AND pt.createdAt >= (CURRENT_DATE - 30 day) AND pt.createdAt < CURRENT_DATE) * :pointsWeight)
            """;

    /**
     * 列表主查询（推荐排序 + 用户坐标）：筛选 + 复合评分排序 + 分页。
     * <p>
     * 排序公式（服务端排序保证分页正确性）：
     * <pre>
     * score = 行为热度（HEAT_SCORE：运营权重 + 近30天浏览×1 + 收藏×10 + 新增收藏×15
     *                     + 动态×5 + 评分×8 + 正向反馈×3，见 HEAT_SCORE 注释）
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
            """ + HEAT_SCORE + """
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
                             @Param("positiveCodes") List<String> positiveCodes,
                             @Param("pointsWeight") int pointsWeight,
                             @Param("hotOnly") boolean hotOnly,
                             @Param("hotIds") Set<Long> hotIds,
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
                                       @Param("positiveCodes") List<String> positiveCodes,
                                       @Param("pointsWeight") int pointsWeight,
                                       @Param("hotOnly") boolean hotOnly,
                                       @Param("hotIds") Set<Long> hotIds,
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
                              @Param("hotOnly") boolean hotOnly,
                              @Param("hotIds") Set<Long> hotIds,
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
                           @Param("positiveCodes") List<String> positiveCodes,
                           @Param("pointsWeight") int pointsWeight,
                           @Param("hotOnly") boolean hotOnly,
                           @Param("hotIds") Set<Long> hotIds,
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
                                       @Param("positiveCodes") List<String> positiveCodes,
                                       @Param("pointsWeight") int pointsWeight,
                                       @Param("hotOnly") boolean hotOnly,
                                       @Param("hotIds") Set<Long> hotIds,
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
                             @Param("hotOnly") boolean hotOnly,
                             @Param("hotIds") Set<Long> hotIds,
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
                                         @Param("hotOnly") boolean hotOnly,
                                         @Param("hotIds") Set<Long> hotIds,
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
        /** 收到积分总数（target_type='VENUE' 的全量 SUM，2026-08-10 V2 新增） */
        Long getPointsreceivedtotal();
        /** 近30天收到积分（target_type='VENUE' 的窗口 SUM，热度公式积分输入项） */
        Long getPointsreceived30d();
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
                WHERE r.venue_id = :venueId AND r.deleted = false AND r.created_at >= :reportSince) AS latestreporttime,
              (SELECT COALESCE(SUM(-pt.delta), 0) FROM qwt_points_transactions pt
                WHERE pt.target_type = 'VENUE' AND pt.target_id = :venueId AND pt.delta < 0) AS pointsreceivedtotal,
              (SELECT COALESCE(SUM(-pt.delta), 0) FROM qwt_points_transactions pt
                WHERE pt.target_type = 'VENUE' AND pt.target_id = :venueId AND pt.delta < 0
                  AND pt.created_at >= :windowSince AND pt.created_at < :windowUntil) AS pointsreceived30d
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
     * 趋势单日行投影（热度页 收藏/浏览/反馈 三张趋势图的统一数据源）。
     * getter 类型约定：day 必须声明为 java.time.LocalDate，且 SQL 侧骨架必须显式
     * {@code ::date} 转成 DATE 列（2026-08-08 缺陷修复，见 {@link #countDailyTrends} 根因）。
     * <p>
     * 根因：generate_series(date, date, interval) 会被 Postgres 解析到
     * <b>timestamptz 重载</b>（datetime 类别的 preferred type），返回列类型是
     * timestamptz——Hibernate 6+ 对原生查询默认映射为 java.time.Instant，
     * Spring Data 投影对具体类目标（LocalDate 非接口）无 Instant→LocalDate
     * Converter，运行期抛 UnsupportedOperationException。骨架经 ::date 后列类型为
     * DATE，JDBC 返回 java.sql.Date，经 Jsr310Converters.DateToLocalDateConverter
     * 正常转换（与 {@link HeatCounters} 的 LocalDateTime 同理，勿再移除该 cast）。
     */
    interface DailyTrendRow {
        java.time.LocalDate getDay();
        /** 当日新增收藏数 */
        Long getFavcount();
        /** 当日浏览数（含匿名，按日计数） */
        Long getViewcount();
        /** 当日正向反馈数 */
        Long getPosreaction();
        /** 当日负向反馈数 */
        Long getNegreaction();
        /** 当日收到积分（target_type='VENUE' 的 SUM，2026-08-10 V2 新增，已补零） */
        Long getPoints();
    }

    /**
     * 热度趋势 mega-query：一条 DB 往返取回 收藏/浏览/正负 Reaction 四组按天时间序列。
     * <p>
     * 根因（两层）：热度页三张趋势图若各自一条查询（favorites / views / reactions 各
     * GROUP BY day），热度接口往返从 2~4 次膨胀到 5~7 次——违反「最少往返」第一约束。
     * 四组序列都是"以 venueId 为键、按天分组的单值聚合"，收敛为一条 SELECT：
     * generate_series 生成连续日期骨架（天然补零），四张源表 GROUP BY day 后 LEFT JOIN 骨架。
     * <p>
     * <b>时区链缺陷（2026-08-08 实机复现，用户反馈"统计图全空但互动卡片有数"）</b>：
     * generate_series(date, date, interval) 的 date 参数会被 PG 解析到 <b>timestamptz 重载</b>
     * （datetime 类别 preferred type），date→timestamptz 与 timestamptz→::date 的往返
     * 依赖 session timezone——session/JVM 时区不一致时骨架整体偏移一天、且与源表 DATE 列
     * LEFT JOIN 恒失配（计数全 0）。上轮修复只在 SELECT 投影加 d.day::date（解决投影类型
     * 异常），ON 条件仍失配；本轮改为<b>骨架显式 ::timestamp 重载 + ::date 收口</b>——
     * generate_series(timestamp, timestamp, interval) 返回无时区 timestamp，::date 直接
     * 截断，与 session/JVM 时区<b>完全无关</b>（已用 UTC / Asia/Shanghai / America/Los_Angeles
     * 三时区实测：窗口恒 [sinceDate, asOfDate]、计数一致）。ON 条件为 date = date 纯比较。
     * <p>
     * 窗口语义（与 VenueHeatService「截至昨日」口径一致）：
     * <ul>
     *   <li>day 骨架 = [sinceDate, asOfDate]（即 [今天-30, 昨天]，共 30 天）</li>
     *   <li>favorites / reactions 按 created_at 过滤 [windowSince, windowUntil)（今天0点为排他上界）</li>
     *   <li>views 按 view_date（DATE 列）过滤 [viewSince, viewUntil)</li>
     * </ul>
     */
    @Query(value = """
            SELECT d.day,
                   COALESCE(f.cnt, 0) AS favcount,
                   COALESCE(v.cnt, 0) AS viewcount,
                   COALESCE(pr.cnt, 0) AS posreaction,
                   COALESCE(nr.cnt, 0) AS negreaction,
                   COALESCE(pt.cnt, 0) AS points
            FROM (SELECT generate_series(CAST(:sinceDate AS timestamp), CAST(:asOfDate AS timestamp), interval '1 day')::date AS day) AS d
            LEFT JOIN (SELECT date_trunc('day', created_at)::date AS day, COUNT(*) AS cnt
                       FROM qwt_favorites
                       WHERE venue_id = :venueId AND deleted = false
                         AND created_at >= :windowSince AND created_at < :windowUntil
                       GROUP BY 1) f ON f.day = d.day
            LEFT JOIN (SELECT view_date AS day, COUNT(*) AS cnt
                       FROM qwt_venue_views
                       WHERE venue_id = :venueId
                         AND view_date >= :viewSince AND view_date < :viewUntil
                       GROUP BY 1) v ON v.day = d.day
            LEFT JOIN (SELECT date_trunc('day', created_at)::date AS day, COUNT(*) AS cnt
                       FROM qwt_venue_reactions
                       WHERE venue_id = :venueId AND deleted = false
                         AND reaction_code IN :positiveCodes
                         AND created_at >= :windowSince AND created_at < :windowUntil
                       GROUP BY 1) pr ON pr.day = d.day
            LEFT JOIN (SELECT date_trunc('day', created_at)::date AS day, COUNT(*) AS cnt
                       FROM qwt_venue_reactions
                       WHERE venue_id = :venueId AND deleted = false
                         AND reaction_code IN :negativeCodes
                         AND created_at >= :windowSince AND created_at < :windowUntil
                       GROUP BY 1) nr ON nr.day = d.day
            LEFT JOIN (SELECT date_trunc('day', created_at)::date AS day, SUM(-delta) AS cnt
                       FROM qwt_points_transactions
                       WHERE target_type = 'VENUE' AND target_id = :venueId AND delta < 0
                         AND created_at >= :windowSince AND created_at < :windowUntil
                       GROUP BY 1) pt ON pt.day = d.day
            ORDER BY d.day
            """, nativeQuery = true)
    List<DailyTrendRow> countDailyTrends(@Param("venueId") Long venueId,
                                         @Param("sinceDate") java.time.LocalDate sinceDate,
                                         @Param("asOfDate") java.time.LocalDate asOfDate,
                                         @Param("viewSince") java.time.LocalDate viewSince,
                                         @Param("viewUntil") java.time.LocalDate viewUntil,
                                         @Param("windowSince") LocalDateTime windowSince,
                                         @Param("windowUntil") LocalDateTime windowUntil,
                                         @Param("positiveCodes") List<String> positiveCodes,
                                         @Param("negativeCodes") List<String> negativeCodes);

    /**
     * 查询城市内热门场所 ID 集合（城市内热度排名前 20% 且 热度分 ≥ 绝对门槛）。
     * <p>
     * 排序口径与列表查询一致（{@link #HEAT_SCORE} 行为热度镜像，2026-08-08 与列表
     * 排序统一——修复此前 fav×20+post×10 旧公式与热度页 heatScore 的双口径分叉）。
     * <p>
     * <b>双条件判定（2026-08-08 确立，修复"热度指数 2 也有热门标签"的伪热门缺陷）</b>：
     * <ul>
     *   <li><b>城市内相对排名</b>：ROW_NUMBER + COUNT 窗口函数取同城市 top 20%
     *       （CEIL 向上取整）——避免跨城市基数差异（上海普通场所的收藏量可能 &gt;
     *       小城市最热门场所）。旧实现 GREATEST(1, CEIL(...)) 的"至少 1 家/城市"
     *       兜底已被<b>移除</b>：它使每个城市的第一名恒被标记热门，哪怕热度分仅
     *       等于 2 次浏览——"小池塘里最不冷"被误读为"热门"；</li>
     *   <li><b>绝对热度门槛</b>：<b>行为热度</b>（完整热度分扣除运营权重 sortWeight，
     *       即 {@code heat_score - sort_weight}）≥ {@code :minHotScore}（配置
     *       {@code venue.hot.min-heat-score}，唯一事实源 =
     *       {@link org.quwuting.quwutingservice.venue.config.VenueHotProperties}）——
     *       没有实质用户活跃的场所（纯浏览/冷启动）即使城市内排名第一也不得标记
     *       热门。
     *       <b>门槛为何作用于行为热度部分（2026-08-08 用户反馈根因修复）</b>：
     *       运营权重 sortWeight 仍参与排名（top 20%）与列表排序（运营推广提升曝光属
     *       其本职），但<b>不得伪造热门资格</b>——历史实现把门槛放在含 sortWeight
     *       的完整分上，运营加权门店（如 sortWeight=68）即使行为热度仅 2（近30天
     *       2 次浏览）也能被抬过门槛，出现"详情页热度指数 2 却有热门标签"的自相矛盾。
     *       门槛改到行为部分后：热门 ⟺ 行为热度 ≥ 门槛，与详情页热度 chip 的核心
     *       行为项口径一致（满意度偏移属评分纠偏小项，不参与热门判定，见 AGENTS.md
     *       「热门场所标记」演进说明）。</li>
     * </ul>
     * 三层子查询结构：最内层 scored 一次性计算热度分与 sort_weight（公式唯一出现点），
     * 中间层在其上做窗口排名，最外层施加排名 + 门槛双条件——避免公式在 SQL 中
     * 重复书写导致镜像漂移。
     * <p>
     * <b>列透传契约（2026-08-08 线上事故根因，勿再犯）</b>：外层 WHERE 引用的
     * {@code heat_score} / {@code sort_weight} 都是派生列——<b>中间层子查询必须把它们
     * 选进投影</b>（{@code SELECT id, heat_score, sort_weight, ...}）。历史缺陷：
     * 重写为三层结构时中间层只投影了 id/rn/city_total，外层 {@code WHERE ... AND heat_score >= ?}
     * 引用了该层不可见的列 → 运行期报 {@code ERROR: column "heat_score" does not exist}。
     * nativeQuery 不受启动期 JPQL 校验覆盖（见 AGENTS.md「native SQL 验证」），
     * 此类错误只能在真实数据库执行时暴露。
     * <p>
     * 数据规模小（每城市 5~30 家），单次全表查询无性能压力；结果由
     * VenueLookupService 缓存 5min（变化频率极低）。
     * <p>
     * 窗口日期在 SQL 内取 CURRENT_DATE（与 {@link #HEAT_SCORE} 一致），无参数日期依赖。
     */
    @Query(value = """
            SELECT id FROM (
                SELECT id,
                       heat_score,
                       sort_weight,
                       ROW_NUMBER() OVER (PARTITION BY city ORDER BY heat_score DESC, id) AS rn,
                       COUNT(*) OVER (PARTITION BY city) AS city_total
                FROM (
                    SELECT v.id, v.city,
                           v.sort_weight AS sort_weight,
                           v.sort_weight
                           + (SELECT COUNT(*) FROM qwt_venue_views vv
                              WHERE vv.venue_id = v.id AND vv.view_date >= (CURRENT_DATE - 30) AND vv.view_date < CURRENT_DATE) * """
            + VenueHeatWeights.VIEW + """
                           + (SELECT COUNT(*) FROM qwt_favorites f
                              WHERE f.venue_id = v.id AND f.deleted = false) * """
            + VenueHeatWeights.FAVORITE + """
                           + (SELECT COUNT(*) FROM qwt_favorites f2
                              WHERE f2.venue_id = v.id AND f2.deleted = false
                                AND f2.created_at >= (CURRENT_DATE - 30) AND f2.created_at < CURRENT_DATE) * """
            + VenueHeatWeights.NEW_FAVORITE + """
                           + (SELECT COUNT(*) FROM qwt_venue_posts p
                              WHERE p.venue_id = v.id AND p.deleted = false) * """
            + VenueHeatWeights.POST + """
                           + (SELECT COUNT(*) FROM qwt_tag_interactions ti
                              WHERE ti.venue_id = v.id AND ti.deleted = false AND ti.score IS NOT NULL
                                AND ti.created_at >= (CURRENT_DATE - 30) AND ti.created_at < CURRENT_DATE) * """
            + VenueHeatWeights.RATING + """
                           + (SELECT COUNT(*) FROM qwt_venue_reactions r
                              WHERE r.venue_id = v.id AND r.deleted = false
                                AND r.reaction_code IN :positiveCodes
                                AND r.created_at >= (CURRENT_DATE - 30) AND r.created_at < CURRENT_DATE) * """
            + VenueHeatWeights.REACTION + """
                           + (SELECT COALESCE(SUM(-pt.delta), 0) FROM qwt_points_transactions pt
                              WHERE pt.target_type = 'VENUE' AND pt.target_id = v.id AND pt.delta < 0
                                AND pt.created_at >= (CURRENT_DATE - 30) AND pt.created_at < CURRENT_DATE) * :pointsWeight
                           AS heat_score
                    FROM qwt_venues v
                    WHERE v.deleted = false
                ) scored
            ) ranked
            WHERE rn <= CEIL(city_total * 0.2)
              AND heat_score - sort_weight >= :minHotScore
            """, nativeQuery = true)
    List<Long> findHotVenueIds(@Param("positiveCodes") List<String> positiveCodes,
                               @Param("pointsWeight") int pointsWeight,
                               @Param("minHotScore") int minHotScore);
}
