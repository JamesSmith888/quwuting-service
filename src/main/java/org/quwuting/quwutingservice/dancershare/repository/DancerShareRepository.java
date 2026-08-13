package org.quwuting.quwutingservice.dancershare.repository;

import org.quwuting.quwutingservice.dancershare.entity.DancerShare;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 舞伴分享事件日志仓库（纯 append，无查询需求——分析查询走数仓/BI 层，不在 API 暴露）。
 */
public interface DancerShareRepository extends JpaRepository<DancerShare, Long> {
}
