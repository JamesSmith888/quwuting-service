package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.VenuePhoto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VenuePhotoRepository extends JpaRepository<VenuePhoto, Long> {

    /** 门店全部照片（管理入口/编辑页，按展示顺序——上传序）；状态过滤由服务层可见性规则负责 */
    List<VenuePhoto> findByVenueIdAndDeletedFalseOrderBySortOrderAscIdAsc(Long venueId);

    /** 门店全部已入库照片 URL（未软删；2026-08-27 幂等去重查询——新增请求对已存在
     *  URL 整项跳过，配合前端 added 增量 + 串行队列，杜绝批量上传重复入库） */
    @Query("SELECT p.url FROM VenuePhoto p WHERE p.venueId = :venueId AND p.deleted = false")
    List<String> findUrlsByVenueIdAndDeletedFalse(@Param("venueId") Long venueId);

    Optional<VenuePhoto> findByIdAndDeletedFalse(Long id);

    /**
     * 物理删除门店的高德导入相册记录（created_by=0 = 平台存量导入，2026-08-22 新增）。
     * 用途：同步「重置式导入」——每店同步前先清旧高德图再插入最新匹配结果，
     * 保证错配/过期图（如名称模糊匹配混入其他店照片）随重跑自愈，且不积累软删行。
     * <b>禁派生删除</b>（2026-08-15 崩溃根因：子表逐条 em.remove 延迟删除在事务内
     * flush + 同键并发写产生 StaleObjectStateException，一律 @Modifying 批量删除）。
     * 必须在事务内调用（syncGalleryPhotos 的 @Transactional 覆盖）。
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            DELETE FROM VenuePhoto p
            WHERE p.venueId = :venueId AND p.createdBy = 0 AND p.deleted = false
            """)
    int deleteImportedByVenue(@Param("venueId") Long venueId);

    /** 当前最大展示顺序（新照片 sortOrder = max + 1；无照片返回 0）。
     *  单值聚合（同 DancerPhotoRepository#findMaxSortOrder，省跨洲往返与全表行传输） */
    @Query("SELECT COALESCE(MAX(p.sortOrder), 0) FROM VenuePhoto p " +
           "WHERE p.venueId = :venueId AND p.deleted = false")
    int findMaxSortOrder(@Param("venueId") Long venueId);

    /**
     * 批量门店公开照片 URL（详情/列表/收藏消费：一次 IN 查询覆盖整页门店，规避 N+1；
     * 同 VenueViewRepository#countByVenueIds 批量模式）。返回 Object[]{venueId, url, sortOrder}，
     * 服务层按 venueId 聚合为有序列表（sortOrder 升序，保持上传序）。
     */
    @Query(value = """
            SELECT p.venue_id, p.url, p.sort_order
            FROM qwt_venue_photos p
            WHERE p.venue_id IN :venueIds AND p.status = 'PUBLIC' AND p.deleted = false
            ORDER BY p.venue_id, p.sort_order ASC, p.id ASC
            """, nativeQuery = true)
    List<Object[]> findPublicUrlsByVenueIds(@Param("venueIds") List<Long> venueIds);

    /**
     * 管理端照片审核列表（仅 ADMIN，含全部状态，按上传时间倒序——新照片优先审核）。
     * status 可选过滤（null = 全部）。LEFT JOIN qwt_venues 取门店名称（门店已软删时
     * 名称回退占位，服务层处理）、LEFT JOIN qwt_users 取上传者昵称（存量导入
     * created_by=0 或用户已软删时回退占位）。返回 Object[]：
     * {id, url, status, venue_id, venue_name, created_by, uploader_nickname, created_at}。
     * countQuery 与主查询共用状态过滤条件，保证分页总数与筛选一致。
     */
    @Query(value = """
            SELECT p.id, p.url, p.status, p.venue_id, v.name, p.created_by, u.nickname, p.created_at
            FROM qwt_venue_photos p
            LEFT JOIN qwt_venues v ON v.id = p.venue_id AND v.deleted = false
            LEFT JOIN qwt_users u ON u.id = p.created_by AND u.deleted = false
            WHERE p.deleted = false
              AND (:status IS NULL OR p.status = :status)
            ORDER BY p.created_at DESC, p.id DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM qwt_venue_photos p
            WHERE p.deleted = false
              AND (:status IS NULL OR p.status = :status)
            """,
            nativeQuery = true)
    Page<Object[]> findAdminPage(@Param("status") String status, Pageable pageable);
}
