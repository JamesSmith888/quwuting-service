package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 场所状态变迁日志仓储。
 * <p>
 * 读模型说明：热度接口所需的"近30天暂停次数 + 最近状态变迁时间"已内联到
 * {@link VenueRepository#countHeatCounters} 的标量子查询中（跨表 mega-query，
 * 单次 DB 往返），聚合查询不落在本接口；明细读仅限下方两条，均为命中
 * qwt_idx_status_logs_venue_time (venueId, createdAt) 的窄查询。
 * <p>
 * 详情页专属读：
 * <ul>
 *   <li>{@link #findLatestStatusChangeTime}（2026-08-08）供
 *       {@code VenueDetailResponse.statusUpdatedAt} 使用——"营业状态字段的更新时间"
 *       的唯一事实源 = 状态日志表最新一条的 createdAt（场所创建时的初始日志即起点，
 *       之后每次 status 变更追加一条）。与整个场所记录的 updatedAt 语义不同
 *       （后者任意字段编辑都会刷新），营业状态详情弹窗展示的是前者。</li>
 *   <li>{@link #findTop5ByVenueIdAndCreatedAtAfterOrderByCreatedAtDesc}（2026-08-29）
 *       供 VenueHeatResponse.statusLogs 使用——营业状态详情弹窗「状态记录」区块，
 *       近30天变更事件列表（可信度判定的证据层）。 freshness 与热度缓存一致：
 *       状态日志仅随状态变更写入，写路径均显式 {@code venueHeatService.invalidate}。</li>
 * </ul>
 */
public interface VenueStatusLogRepository extends JpaRepository<VenueStatusLog, Long> {

    /** 场所 status 字段最近一次变更时间（无日志时为 null，理论不存在——创建必写初始日志） */
    @Query("select max(l.createdAt) from VenueStatusLog l where l.venueId = :venueId")
    LocalDateTime findLatestStatusChangeTime(@Param("venueId") Long venueId);

    /** 近30天状态变更记录（最多 5 条，按变更时间倒序），营业状态弹窗「状态记录」区块数据源 */
    List<VenueStatusLog> findTop5ByVenueIdAndCreatedAtAfterOrderByCreatedAtDesc(Long venueId, LocalDateTime after);

    /**
     * 系统自动反转记录（2026-09-01，Web 后台「更新记录」数据源）：changedBy IS NULL
     * （系统/Agent 来源；人工编辑=userId）且 CEASED/SUSPENDED → OPEN 的变更，按时间倒序。
     * 命中 qwt_idx_status_logs_venue_time 不直接生效（查询条件无 venueId 前缀），
     * 但本查询低频（管理后台点击）且表量小，倒序 TOP-N 走主键扫描即可。
     */
    List<VenueStatusLog> findByChangedByIsNullAndToStatusAndFromStatusInOrderByCreatedAtDesc(
            VenueStatus toStatus, Collection<VenueStatus> fromStatuses, Pageable pageable);
}
