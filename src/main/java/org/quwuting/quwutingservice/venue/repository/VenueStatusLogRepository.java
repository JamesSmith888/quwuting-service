package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 场所状态变迁日志仓储。
 * <p>
 * 读模型说明：热度接口所需的"近30天暂停次数 + 最近状态变迁时间"已内联到
 * {@link VenueRepository#countHeatCounters} 的标量子查询中（跨表 mega-query，
 * 单次 DB 往返），本接口不再承载聚合查询，仅保留 JpaRepository 的写入能力
 * （VenueService 在场所创建/状态变更时写入日志）。
 * <p>
 * 详情页专属读（2026-08-08 新增）：{@link #findLatestStatusChangeTime} 供
 * {@code VenueDetailResponse.statusUpdatedAt} 使用——"营业状态字段的更新时间"
 * 的唯一事实源 = 状态日志表最新一条的 createdAt（场所创建时的初始日志即起点，
 * 之后每次 status 变更追加一条）。与整个场所记录的 updatedAt 语义不同
 * （后者任意字段编辑都会刷新），营业状态详情弹窗展示的是前者。
 */
public interface VenueStatusLogRepository extends JpaRepository<VenueStatusLog, Long> {

    /** 场所 status 字段最近一次变更时间（无日志时为 null，理论不存在——创建必写初始日志） */
    @Query("select max(l.createdAt) from VenueStatusLog l where l.venueId = :venueId")
    LocalDateTime findLatestStatusChangeTime(@Param("venueId") Long venueId);
}
