package org.quwuting.quwutingservice.venuecrowd.repository;

import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 门店热度上报仓储（2026-08-29，docs/agents/27-venue-crowd-report.md；统计口径见 53-venue-crowd-stats.md）。
 * <p>
 * upsert = 原生 INSERT ... ON DUPLICATE KEY UPDATE（V41 生成列部分唯一索引
 * qwt_idx_crowd_reports_user_night = (venue_id, user_id, business_date) WHERE deleted=0）——
 * 幂等确定性：同一营业夜再次上报（含跨午夜）= UPDATE 原行 + modify_count+1，不产生新行；
 * 并发（同用户同店同夜在飞请求）由数据库唯一键冲突兜底，无需应用层锁。
 */
public interface VenueCrowdReportRepository extends JpaRepository<VenueCrowdReport, Long> {

    /** 聚合窗口扫描：最近 6h（CrowdPolicy.TONIGHT_WINDOW_HOURS）该店全部未删上报（详情聚合数据源） */
    List<VenueCrowdReport> findByVenueIdAndCreatedAtAfterAndDeletedFalse(
            @Param("venueId") Long venueId, @Param("since") LocalDateTime since);

    /** 管理端按店明细分页（2026-09-01 热度管理下钻）：24h 窗口 + 分页 + 未删，createdAt 倒序 */
    Page<VenueCrowdReport> findByVenueIdAndCreatedAtAfterAndDeletedFalse(
            @Param("venueId") Long venueId, @Param("since") LocalDateTime since, Pageable pageable);

    /** 全部热度历史（2026-08-29 历史页数据源）：该店全量未删上报，createdAt 倒序分页 */
    Page<VenueCrowdReport> findByVenueIdAndDeletedFalseOrderByCreatedAtDesc(
            @Param("venueId") Long venueId, Pageable pageable);

    /** 我今晚（本营业日）的上报（详情页「已上报 · 可改一下」mine 态判定；营业日口径见 BusinessDay） */
    List<VenueCrowdReport> findByVenueIdAndUserIdAndBusinessDateAndDeletedFalse(
            @Param("venueId") Long venueId, @Param("userId") Long userId,
            @Param("businessDate") LocalDate businessDate);

    /**
     * 区间内全部未删上报（2026-10-07 常态人气 / 折叠头摘要数据源）：[from, to) 半开区间，
     * 区间按营业日起点换算（BusinessDay.startOf），不读 report_date——营业日口径只有一个出处。
     */
    List<VenueCrowdReport> findByVenueIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndDeletedFalse(
            @Param("venueId") Long venueId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 批量每店独立上报人数（2026-08-29 列表角标数据源）：一次 IN + 窗口过滤 +
     * GROUP BY venue_id 覆盖整页，避免逐店 COUNT 的 N+1（同
     * VenueViewRepository#countByVenueIds 批量模式）。窗口 = 最近
     * TONIGHT_WINDOW_HOURS 小时（6h，与详情聚合同口径）。返回 Object[]{venueId, count}。
     * <p>
     * 2026-10-07：<b>认领人（门店主）的上报不计入</b>——商家自报有营销动机，与详情页统计同口径
     * （文档 27 早有此承诺，此前 venuecrowd 包里从未实现）。原生 SQL：JOIN qwt_venues 过滤
     * claimed_by，语句已在生产库只读执行验证（JPQL 隐式 theta-join 无法这样验证）。
     */
    @Query(value = "SELECT r.venue_id, COUNT(DISTINCT r.user_id) FROM qwt_venue_crowd_reports r " +
            "JOIN qwt_venues v ON v.id = r.venue_id " +
            "WHERE r.venue_id IN (:venueIds) AND r.created_at >= :since AND r.deleted = 0 " +
            "AND (v.claimed_by IS NULL OR r.user_id <> v.claimed_by) " +
            "GROUP BY r.venue_id", nativeQuery = true)
    List<Object[]> countDistinctUsersByVenueIdsSince(
            @Param("venueIds") Collection<Long> venueIds, @Param("since") LocalDateTime since);

    /**
     * 批量每店最新一条上报（2026-08-29 列表「最新上报」行数据源）：窗口内
     * （TONIGHT_WINDOW_HOURS=6h）每店 created_at 最大的记录，一次查询覆盖整页，
     * 防 N+1（同 countDistinctUsersByVenueIdsSince 批量模式）。子查询 = 窗口内
     * 每店最大 created_at，外查询等值匹配；同一店同一时刻多条（理论罕见，
     * upsert 幂等 + timestamp 精度）由 Service 按 venueId 取首条兜底。
     * <p>
     * 2026-10-07：认领人上报同样排除（内外两层都要带条件，否则最大值取到店家那条、外层过滤后整店丢行）。
     */
    @Query(value = "SELECT r.* FROM qwt_venue_crowd_reports r " +
            "JOIN qwt_venues v ON v.id = r.venue_id " +
            "WHERE r.venue_id IN (:venueIds) AND r.created_at >= :since AND r.deleted = 0 " +
            "AND (v.claimed_by IS NULL OR r.user_id <> v.claimed_by) " +
            "AND r.created_at = (SELECT MAX(r2.created_at) FROM qwt_venue_crowd_reports r2 " +
            "WHERE r2.venue_id = r.venue_id AND r2.created_at >= :since AND r2.deleted = 0 " +
            "AND (v.claimed_by IS NULL OR r2.user_id <> v.claimed_by))", nativeQuery = true)
    List<VenueCrowdReport> findLatestByVenueIdsSince(
            @Param("venueIds") Collection<Long> venueIds, @Param("since") LocalDateTime since);

    /** 窗口内全量上报（管理端按店聚合用，数据量小——日活 5~36 规模，内存分组可接受） */
    List<VenueCrowdReport> findByCreatedAtAfterAndDeletedFalse(@Param("since") LocalDateTime since);

    /**
     * 幂等 upsert（每营业夜一记）：INSERT 新行 / ON DUPLICATE KEY UPDATE 原行（modify_count+1）。
     * 冲突目标 = V41 生成列部分唯一索引 (venue_id, user_id, <b>business_date</b>) WHERE deleted=0。
     * <p>
     * 两个日期各管各的坐标系（2026-10-07）：{@code businessDate} = 营业日（05:00 分界，唯一键）；
     * {@code reportDate} = 自然日（行为统计口径的日列，仅首次插入写入，改报不更新）。
     * <p>
     * ⚠️ 时间口径（2026-08-29 修复）：created_at/updated_at **必须由 Java 传入
     * LocalDateTime.now()（JVM 时区=北京时间）**，禁止用 DB 端 now()——Supabase
     * 会话时区是 UTC，DB now() 写入 UTC 墙钟值，而聚合窗口（since = JVM
     * LocalDateTime.now() - 2h）是北京时间，比较永远错位 → 上报恒落在窗口外、
     * 详情页恒显「暂无舞友上报」。全库其余表（@CreationTimestamp）均为 JVM 时间，
     * 本表须同口径。同夜「改一下」命中 ON DUPLICATE KEY 时同时刷新 created_at（重新
     * 上报 = 数据此刻新鲜，6h TTL 重新计时——顺带自愈修复前的 UTC 脏行）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_crowd_reports " +
            "(created_at, updated_at, deleted, venue_id, user_id, female_level, male_level, report_date, business_date, modify_count) " +
            "VALUES (:createdAt, :updatedAt, false, :venueId, :userId, :femaleLevel, :maleLevel, :reportDate, :businessDate, 0) " +
            "ON DUPLICATE KEY UPDATE female_level = VALUES(female_level), " +
            "male_level = VALUES(male_level), " +
            "modify_count = modify_count + 1, " +
            "created_at = :createdAt, " +
            "updated_at = :updatedAt",
            nativeQuery = true)
    void upsert(@Param("venueId") Long venueId, @Param("userId") Long userId,
                @Param("femaleLevel") int femaleLevel, @Param("maleLevel") Integer maleLevel,
                @Param("reportDate") LocalDate reportDate, @Param("businessDate") LocalDate businessDate,
                @Param("createdAt") LocalDateTime createdAt, @Param("updatedAt") LocalDateTime updatedAt);
}
