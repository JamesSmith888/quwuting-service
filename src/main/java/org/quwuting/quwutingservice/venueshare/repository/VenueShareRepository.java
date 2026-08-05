package org.quwuting.quwutingservice.venueshare.repository;

import org.quwuting.quwutingservice.venueshare.entity.VenueShare;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 场所分享事件日志仓库（纯 append，无查询需求——分析查询走数仓/BI 层，不在 API 暴露）。
 */
public interface VenueShareRepository extends JpaRepository<VenueShare, Long> {
}
