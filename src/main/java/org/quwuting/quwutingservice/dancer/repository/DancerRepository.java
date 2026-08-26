package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DancerCity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DancerRepository extends JpaRepository<Dancer, Long> {

    Optional<Dancer> findByIdAndDeletedFalse(Long id);

    /** 批量查询（列表页/详情页一次 IN 查询覆盖整页舞伴，规避 N+1） */
    @Query("SELECT d FROM Dancer d WHERE d.id IN :ids AND d.deleted = false")
    List<Dancer> findByIds(@Param("ids") List<Long> ids);

    /**
     * 开启邀约中转的舞伴（2026-08-26，22 号文档：contact_relay=true）。
     * 管理端邀约工作台待办列表的舞伴范围（数量少，全量拉取；含软删的排除）。
     */
    @Query("SELECT d FROM Dancer d WHERE d.contactRelay = true AND d.deleted = false")
    List<Dancer> findRelayEnabled();

    /**
     * 批量取资料标签列（2026-08-24，列表 enrichments 用）：返回
     * {id, profile_tags}——反序列化 + 字典解析由 DancerListCacheService 完成
     * （一次 IN 查询覆盖整页，规避 N+1）。
     */
    @Query("SELECT d.id, d.profileTags FROM Dancer d WHERE d.id IN :ids AND d.deleted = false")
    List<Object[]> findProfileTagsByDancerIds(@Param("ids") List<Long> ids);

    /**
     * 批量取联系方式更新时间（2026-08-26 晚：列表 / 详情「最近更新了联系方式」信号——
     * 返回 {id, contact_updated_at}（未更新过 = NULL；一次 IN 查询覆盖整页，规避 N+1）。
     */
    @Query(value = """
            SELECT d.id, d.contact_updated_at
            FROM qwt_dancers d
            WHERE d.id IN :ids AND d.deleted = false
            """, nativeQuery = true)
    List<Object[]> findContactUpdatedAtByDancerIds(@Param("ids") List<Long> ids);

    /** 我的舞伴主页列表（创建人视角，含 PENDING/HIDDEN 自有资料） */
    List<Dancer> findByCreatedByAndDeletedFalseOrderByUpdatedAtDesc(Long createdBy);

    /**
     * 用户公开主页的舞伴列表（2026-08-12：TA 创建的**公开**舞伴）。
     * 隐私边界与列表页同：仅 status=NORMAL 才公开；最近创建在前（id 降序）。
     */
    @Query("SELECT d FROM Dancer d WHERE d.createdBy = :createdBy " +
            "AND d.status = 'NORMAL' AND d.deleted = false " +
            "ORDER BY d.id DESC")
    List<Dancer> findPublicByCreatedBy(@Param("createdBy") Long createdBy);

    /**
     * 公开舞伴的常驻城市列表（列表页城市筛选词表，升序去重）。
     * 与 venue 域 /venues/cities 同模式：聚合真实数据而非静态词表（新增城市自动出现）。
     * <p>
     * 2026-08-14 多城市（V29 迁移）：词表改从 qwt_dancer_cities 子表聚合
     * （dancer.city 是主城市 = 子表首个的冗余，从子表聚合可覆盖全量城市；
     * 存量回填后旧数据无缝覆盖）。
     * <p>
     * 2026-08-21（V38 迁移）：按 qwt_city_key 规范化键去重 + MAX 优先保留带「市」
     * 标准形态——历史手填「南通」与标准「南通市」归一后只出一个 chip
     * （MAX 字符串比较：'南通市' > '南通'，恒取带市形态）；查询改 nativeQuery
     * （JPQL 无法引用 PG 自定义函数）。
     */
    @Query(value = """
            SELECT MAX(c.city)
            FROM qwt_dancer_cities c
            JOIN qwt_dancers d ON d.id = c.dancer_id
            WHERE c.city IS NOT NULL AND c.city <> '' AND c.deleted = false
              AND d.status = 'NORMAL' AND d.deleted = false
            GROUP BY qwt_city_key(c.city)
            ORDER BY MAX(c.city)
            """, nativeQuery = true)
    List<String> findPublicCities();

    /**
     * 公开舞伴列表（仅 NORMAL），按排序模式分页（2026-08-26 晚排序升级）。
     * <p>
     * <b>HOT（默认，sortMode='HOT'）</b>——组合分倒序：
     * <ol>
     *   <li><b>近7天认可数 cnt7</b>（主导信号，滚动锚点 now-7d，与 Reaction 同口径；
     *       刻意不用 countAll——"被认可的历史总量不应让活跃度低的旧资料长期霸榜"）</li>
     *   <li><b>新鲜度加成 +2/+4</b>：新舞伴（created_at &gt;= now-14d）+2；
     *       近 3 天更新过相册（最新 PUBLIC 媒体 created_at，V51 信号）或联系方式
     *       （contact_updated_at）任一 &gt;= now-3d 再 +2——冷启动曝光通道 +
     *       "正在维护资料"的活跃信号（2026-08-26 晚新增）</li>
     *   <li><b>近30天收藏数 fav30d</b>（tie-break，2026-08-26 由近30天收到积分
     *       points30d 替换——原口径 SUM(-delta) 仅收费舞伴非零、同分时系统性偏向
     *       "收费型"，混入商业模式信号；收藏零成本表达长期兴趣，口径中性）</li>
     *   <li><b>id 倒序</b>（兜底，新资料优先 + 分页稳定）</li>
     * </ol>
     * <b>LATEST（sortMode='LATEST'）</b>——id 倒序（新资料在前，纯"最新"口径；
     * 新舞伴不受认可数为 0 的沉底约束）。筛选条件与 HOT 完全一致（城市/服务类别
     * 均生效），仅排序不同。
     * <p>
     * 城市筛选：主城市 OR 子表命中（qwt_dancer_cities，V29 多城市）；V38 起
     * 精确相等 OR qwt_city_key 归一相等（历史手填「南通」与标准「南通市」互相命中）。
     * 服务类别筛选：存在 ≥1 个在用且类别匹配的服务（qwt_dancer_services）。
     * countQuery 与主查询共用过滤条件，保证分页总数与筛选一致。
     * <p>
     * 返回 Object[]：{id, nickname, avatar_url, bio, gender, city, count_all,
     * count_today, count_7d, verification_status}（verification_status 追加于末尾，
     * 2026-08-14 官方认证）。排序用列（cnt7/score/fav30d）不参与 SELECT——消费方
     * 行解析与旧契约完全一致。
     */
    @Query(value = """
            SELECT d.id, d.nickname, d.avatar_url, d.bio, d.gender, d.city,
                   COALESCE(a.cnt_all, 0) AS cnt_all,
                   COALESCE(a.cnt_today, 0) AS cnt_today,
                   COALESCE(a.cnt7, 0) AS cnt7,
                   d.verification_status
            FROM qwt_dancers d
            LEFT JOIN (
                SELECT dancer_id, COUNT(*) AS cnt_all,
                       COUNT(*) FILTER (WHERE created_at >= :sinceToday) AS cnt_today,
                       COUNT(*) FILTER (WHERE created_at >= :since7d) AS cnt7
                FROM qwt_dancer_recognitions WHERE deleted = false
                GROUP BY dancer_id
            ) a ON a.dancer_id = d.id
            LEFT JOIN (
                SELECT dancer_id, COUNT(*) AS fav30d
                FROM qwt_dancer_favorites
                WHERE deleted = false AND created_at >= :since30d
                GROUP BY dancer_id
            ) f ON f.dancer_id = d.id
            LEFT JOIN (
                SELECT dancer_id, MAX(created_at) AS album_max
                FROM qwt_dancer_photos
                WHERE status = 'PUBLIC' AND deleted = false
                GROUP BY dancer_id
            ) ph ON ph.dancer_id = d.id
            WHERE d.status = 'NORMAL' AND d.deleted = false
              AND (:city IS NULL OR d.city = :city OR qwt_city_key(d.city) = qwt_city_key(:city)
                   OR EXISTS (
                        SELECT 1 FROM qwt_dancer_cities c
                        WHERE c.dancer_id = d.id AND c.deleted = false
                          AND (c.city = :city OR qwt_city_key(c.city) = qwt_city_key(:city))))
              AND (:serviceCategory IS NULL OR EXISTS (
                        SELECT 1 FROM qwt_dancer_services s
                        WHERE s.dancer_id = d.id AND s.deleted = false AND s.active = true
                          AND s.category = :serviceCategory))
            ORDER BY
              CASE WHEN :sortMode = 'LATEST' THEN 0
                   ELSE COALESCE(a.cnt7, 0)
                      + CASE WHEN d.created_at >= :sinceNew THEN 2 ELSE 0 END
                      + CASE WHEN COALESCE(GREATEST(ph.album_max, d.contact_updated_at),
                                           TIMESTAMP '1970-01-01 00:00:00') >= :sinceFresh
                             THEN 2 ELSE 0 END
              END DESC,
              CASE WHEN :sortMode = 'LATEST' THEN 0 ELSE COALESCE(f.fav30d, 0) END DESC,
              d.id DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM qwt_dancers d
            WHERE d.status = 'NORMAL' AND d.deleted = false
              AND (:city IS NULL OR d.city = :city OR qwt_city_key(d.city) = qwt_city_key(:city)
                   OR EXISTS (
                        SELECT 1 FROM qwt_dancer_cities c
                        WHERE c.dancer_id = d.id AND c.deleted = false
                          AND (c.city = :city OR qwt_city_key(c.city) = qwt_city_key(:city))))
              AND (:serviceCategory IS NULL OR EXISTS (
                        SELECT 1 FROM qwt_dancer_services s
                        WHERE s.dancer_id = d.id AND s.deleted = false AND s.active = true
                          AND s.category = :serviceCategory))
            """,
            nativeQuery = true)
    Page<Object[]> findPublicPage(@Param("sortMode") String sortMode,
                                  @Param("city") String city,
                                  @Param("serviceCategory") String serviceCategory,
                                  @Param("sinceToday") LocalDateTime sinceToday,
                                  @Param("since7d") LocalDateTime since7d,
                                  @Param("since30d") LocalDateTime since30d,
                                  @Param("sinceNew") LocalDateTime sinceNew,
                                  @Param("sinceFresh") LocalDateTime sinceFresh,
                                  Pageable pageable);

    /**
     * 管理端舞伴列表（仅 ADMIN，含全部状态，按提交时间倒序——新注册优先审核）。
     * status 可选过滤（null = 全部）。LEFT JOIN qwt_users 取注册人信息（用户已删时
     * 昵称/头像为 null，服务层回退占位）。返回 Object[]：
     * {id, nickname, avatar_url, bio, gender, city, status, created_at, u.nickname,
     *  u.avatar_url, verification_status, verified_at}（认证列追加于末尾，2026-08-14）。
     * countQuery 与主查询共用状态过滤条件，保证分页总数与筛选一致。
     */
    @Query(value = """
            SELECT d.id, d.nickname, d.avatar_url, d.bio, d.gender, d.city, d.status, d.created_at,
                   u.nickname AS creator_nickname, u.avatar_url AS creator_avatar_url,
                   d.verification_status, d.verified_at
            FROM qwt_dancers d
            LEFT JOIN qwt_users u ON u.id = d.created_by AND u.deleted = false
            WHERE d.deleted = false
              AND (:status IS NULL OR d.status = :status)
            ORDER BY d.created_at DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM qwt_dancers d
            WHERE d.deleted = false
              AND (:status IS NULL OR d.status = :status)
            """,
            nativeQuery = true)
    Page<Object[]> findAdminPage(@Param("status") String status, Pageable pageable);
}
