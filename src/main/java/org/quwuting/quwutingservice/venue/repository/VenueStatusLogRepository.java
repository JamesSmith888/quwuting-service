package org.quwuting.quwutingservice.venue.repository;

import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 场所状态变迁日志仓储。
 * <p>
 * 读模型说明：热度接口所需的"近30天暂停次数 + 最近状态变迁时间"已内联到
 * {@link VenueRepository#countHeatCounters} 的标量子查询中（跨表 mega-query，
 * 单次 DB 往返），本接口不再承载聚合查询，仅保留 JpaRepository 的写入能力
 * （VenueService 在场所创建/状态变更时写入日志）。
 */
public interface VenueStatusLogRepository extends JpaRepository<VenueStatusLog, Long> {
}
