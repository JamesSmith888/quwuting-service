package org.quwuting.quwutingservice.venuepost.repository;

import org.quwuting.quwutingservice.venuepost.entity.VenuePost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VenuePostRepository extends JpaRepository<VenuePost, Long> {

    Page<VenuePost> findByVenueIdAndDeletedFalse(Long venueId, Pageable pageable);

    /** 详情页辅助计数合并投影：动态总数 + 当前用户状态上报标记 */
    interface DetailStats {
        Long getPostcount();
        Boolean getHasmyreport();
    }

    /**
     * 详情页辅助计数合并查询（单次往返）。
     * <p>
     * 动态总数（公共聚合）与"我是否已上报"（个人状态）原本各占一次跨洲 DB 往返，
     * 合并为一条标量子查询 SELECT。个人状态部分必须实时计算（匿名请求 userId 传 null，
     * EXISTS 子查询因 user_id = NULL 恒不命中，自然返回 false）。
     * <p>
     * 跨表说明：主表为 qwt_venue_posts，qwt_venue_status_reports 仅作只读标量子查询引用。
     * 使用原生 SQL：JPQL 无法在单条投影中表达 EXISTS + COUNT 两个标量子查询。
     */
    @Query(value = "SELECT " +
                   "(SELECT COUNT(*) FROM qwt_venue_posts p " +
                   "  WHERE p.venue_id = :venueId AND p.deleted = false) AS postcount, " +
                   "(SELECT EXISTS(SELECT 1 FROM qwt_venue_status_reports r " +
                   "  WHERE r.user_id = :userId AND r.venue_id = :venueId AND r.deleted = false)) AS hasmyreport",
           nativeQuery = true)
    DetailStats findDetailStats(@Param("venueId") Long venueId, @Param("userId") Long userId);
}
