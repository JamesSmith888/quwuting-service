package org.quwuting.quwutingservice.venuepost.repository;

import org.quwuting.quwutingservice.venuepost.entity.VenuePost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

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
     * <b>TTL 口径（2026-08-05 修复）</b>：hasmyreport 的 EXISTS 子查询必须带
     * {@code created_at >= :since}（活跃报告 TTL 窗口，since 由调用方用
     * {@link org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService#ACTIVE_REPORT_TTL_HOURS}
     * 计算）。历史实现只过滤 {@code deleted = false}，漏掉 TTL 过滤，与热度聚合
     * （VenueRepository.countHeatCounters 的 reportcount / StatusReportRepository.countActiveAndLatestTime）
     * 的"活跃"定义不一致——TTL 过期后活跃计数归零但个人标记恒真，详情页"已报告·补充"
     * 永不还原（用户必须手动撤销）。「活跃」判定的唯一权威源是 StatusReportService 的 TTL
     * 常量，所有活跃判定查询点必须经参数传入同一窗口，禁止在 SQL 中自行定义时间窗。
     * <p>
     * 跨表说明：主表为 qwt_venue_posts，qwt_venue_status_reports 仅作只读标量子查询引用。
     * 使用原生 SQL：JPQL 无法在单条投影中表达 EXISTS + COUNT 两个标量子查询。
     */
    @Query(value = "SELECT " +
                   "(SELECT COUNT(*) FROM qwt_venue_posts p " +
                   "  WHERE p.venue_id = :venueId AND p.deleted = false) AS postcount, " +
                   "(SELECT EXISTS(SELECT 1 FROM qwt_venue_status_reports r " +
                   "  WHERE r.user_id = :userId AND r.venue_id = :venueId AND r.deleted = false " +
                   "    AND r.created_at >= :since)) AS hasmyreport",
           nativeQuery = true)
    DetailStats findDetailStats(@Param("venueId") Long venueId,
                                @Param("userId") Long userId,
                                @Param("since") LocalDateTime since);
}
