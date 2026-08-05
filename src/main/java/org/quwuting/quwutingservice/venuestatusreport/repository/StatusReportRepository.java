package org.quwuting.quwutingservice.venuestatusreport.repository;

import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StatusReportRepository extends JpaRepository<VenueStatusReport, Long> {

    /** 查找用户对某场所的活跃报告（未逻辑删除） */
    Optional<VenueStatusReport> findByUserIdAndVenueIdAndDeletedFalse(Long userId, Long venueId);

    /**
     * 查找用户对某场所的报告（含逻辑删除的记录）。
     * 用于 upsert 恢复逻辑：撤销（soft delete）后再次上报时，
     * 需找到已软删的记录并恢复，而非 INSERT 新行（UNIQUE 约束会冲突）。
     * 与 FavoriteService.findByUserIdAndVenueId 同模式。
     */
    Optional<VenueStatusReport> findByUserIdAndVenueId(Long userId, Long venueId);

    /**
     * 活跃报告聚合：合并 COUNT + MAX(createdAt) 为 1 次往返。
     * 活跃 = 未删除且 createdAt >= since（TTL 窗口，由 Service 层计算）。
     */
    @Query("SELECT COUNT(r) as activeCount, MAX(r.createdAt) as latestTime " +
           "FROM VenueStatusReport r " +
           "WHERE r.venueId = :venueId AND r.deleted = false AND r.createdAt >= :since")
    ActiveReportStats countActiveAndLatestTime(@Param("venueId") Long venueId,
                                                @Param("since") LocalDateTime since);

    /**
     * 全局频率限制：统计用户在指定时间窗口内报告的不同场所数。
     * 用于防止恶意用户批量上报所有场所。
     */
    @Query("SELECT COUNT(DISTINCT r.venueId) FROM VenueStatusReport r " +
           "WHERE r.userId = :userId AND r.deleted = false AND r.createdAt >= :since")
    long countDistinctVenuesByUserIdSince(@Param("userId") Long userId,
                                           @Param("since") LocalDateTime since);

    /**
     * 当前用户的全部状态上报记录（"我的上报记录"弹窗数据源）。
     * <p>
     * 范围：仅未撤销（deleted=false）的记录——撤销是用户主动收回动作，soft delete 属内部
     * 实现细节，已撤销记录不再视为"上报记录"；包含已过期（TTL 外）记录，供前端标注
     * 「已过期」提醒用户可重新上报。active 判定不在 SQL 内完成（避免 SQL 层自行定义时间窗），
     * 由 Service 层按 {@code ACTIVE_REPORT_TTL_HOURS} 常量统一计算（TTL 唯一权威源）。
     * <p>
     * JOIN qwt_venues 一次取回场所名称/地址，消除 N+1（与 /admin/reports 的
     * findByIdInAndDeletedFalse 批量回填同思路，此处 JOIN 形态更直接）。
     * 不过滤 v.deleted：场所软删除后历史上报记录仍应展示原名（记录真实性不因场所下架而消失）。
     * <p>
     * 原生 SQL + 投影接口：跨表 JOIN + 排序形态 JPQL 可表达，但投影别名映射在 JPQL
     * constructor 表达式中需手写全字段，原生 SQL 更直观；getter 类型遵循
     * 「投影接口 getter 类型」约定（TIMESTAMP 列必须 LocalDateTime）。
     * 别名必须全小写（PG 将未引用标识符折叠为小写，`AS venueId` → venueid 会与
     * getVenueId 失配；全小写别名 + 全小写 getter 是 countHeatCounters 的既定模式）。
     */
    @Query(value = "SELECT r.id AS id, r.venue_id AS venueid, r.created_at AS createdat, " +
                   "       v.name AS venuename, v.city AS venuecity, " +
                   "       v.district AS venuedistrict, v.address AS venueaddress " +
                   "FROM qwt_venue_status_reports r " +
                   "JOIN qwt_venues v ON v.id = r.venue_id " +
                   "WHERE r.user_id = :userId AND r.deleted = false " +
                   "ORDER BY r.created_at DESC", nativeQuery = true)
    List<MyReportRow> findMyReportsByUserId(@Param("userId") Long userId);

    /** 投影接口：我的上报记录行（含场所信息，供 GET /status-reports/mine 使用） */
    interface MyReportRow {
        Long getId();
        Long getVenueid();
        LocalDateTime getCreatedat();
        String getVenuename();
        String getVenuecity();
        String getVenuedistrict();
        String getVenueaddress();
    }

    /** 投影接口：活跃报告聚合结果 */
    interface ActiveReportStats {
        Long getActiveCount();
        LocalDateTime getLatestTime();
    }
}
