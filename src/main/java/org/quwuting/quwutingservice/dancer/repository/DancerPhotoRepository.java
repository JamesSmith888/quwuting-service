package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerPhoto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DancerPhotoRepository extends JpaRepository<DancerPhoto, Long> {

    /** 舞伴全部照片（详情页/编辑页，按展示顺序——上传序）；PENDING/REJECTED 是否回显由服务层可见性过滤 */
    List<DancerPhoto> findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(Long dancerId);

    Optional<DancerPhoto> findByIdAndDeletedFalse(Long id);

    /** 当前最大展示顺序（新照片 sortOrder = max + 1；无照片返回 0）。
     *  2026-08-19：单值聚合替代「全量加载后流式取 max」（省一次跨洲往返与全表行传输） */
    @Query("SELECT COALESCE(MAX(p.sortOrder), 0) FROM DancerPhoto p " +
           "WHERE p.dancerId = :dancerId AND p.deleted = false")
    int findMaxSortOrder(@Param("dancerId") Long dancerId);

    /**
     * 批量舞伴封面照片：每个舞伴取展示顺序最小的一张 PUBLIC（列表页/我的舞伴主页封面，
     * 一次 IN 查询覆盖整页舞伴，规避 N+1）。返回 Object[]{dancerId, url}。
     * <p>
     * 2026-08-14 积分解锁：<b>跳过有积分门槛的照片</b>（LEFT JOIN qwt_points_gates
     * target_type='DANCER_PHOTO' 未软删行）——封面是"展示性"位置，列表页不应泄露
     * 需解锁的照片原图（未解锁用户经列表页看到封面 = 绕过解锁）；全部照片都设门槛
     * 的舞伴无封面（诚实呈现，也隐性引导舞伴保留至少一张免费展示照）。
     * <p>
     * 2026-08-22 视频扩展：<b>仅照片（kind='PHOTO'）可作封面</b>——视频封面帧是
     * image 组件无法承载的媒体类型，列表卡片/详情快照一律以照片为封面。
     */
    @Query(value = """
            SELECT DISTINCT ON (p.dancer_id) p.dancer_id, p.url
            FROM qwt_dancer_photos p
            LEFT JOIN qwt_points_gates g
              ON g.target_type = 'DANCER_PHOTO' AND g.target_id = p.id AND g.deleted = false
            WHERE p.dancer_id IN :dancerIds AND p.status = 'PUBLIC' AND p.deleted = false
              AND p.kind = 'PHOTO'
              AND g.id IS NULL
            ORDER BY p.dancer_id, p.sort_order ASC, p.id ASC
            """, nativeQuery = true)
    List<Object[]> findCoverUrlsByDancerIds(@Param("dancerIds") List<Long> dancerIds);

    /**
     * 管理端照片审核列表（仅 ADMIN，含全部状态，按上传时间倒序——新照片优先审核）。
     * status 可选过滤（null = 全部）。LEFT JOIN qwt_dancers 取舞伴昵称/城市/头像
     * （舞伴已软删时昵称回退占位，服务层处理）。返回 Object[]：
     * {id, url, status, dancer_id, dancer_nickname, dancer_city, dancer_avatar_url,
     *  created_at, kind, cover_url, duration_seconds}（2026-08-22 视频扩展追加
     *  kind/cover_url/duration_seconds——审核端区分媒体类型与预览）。
     * countQuery 与主查询共用状态过滤条件，保证分页总数与筛选一致。
     */
    @Query(value = """
            SELECT p.id, p.url, p.status, p.dancer_id, d.nickname, d.city, d.avatar_url,
                   p.created_at, p.kind, p.cover_url, p.duration_seconds
            FROM qwt_dancer_photos p
            LEFT JOIN qwt_dancers d ON d.id = p.dancer_id AND d.deleted = false
            WHERE p.deleted = false
              AND (:status IS NULL OR p.status = :status)
            ORDER BY p.created_at DESC, p.id DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM qwt_dancer_photos p
            WHERE p.deleted = false
              AND (:status IS NULL OR p.status = :status)
            """,
            nativeQuery = true)
    Page<Object[]> findAdminPage(@Param("status") String status, Pageable pageable);
}
