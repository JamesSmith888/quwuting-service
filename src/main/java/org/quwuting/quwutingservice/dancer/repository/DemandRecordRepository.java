package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DemandRecord;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 联系方式需求记录仓库（2026-08-24 风控留痕，qwt_demand_records）。
 * 只写不删（锚点记录，与 PointsUnlock 同模式）；查询维度 = 用户（异常解锁频次）/
 * 舞伴（骚扰投诉排查）——本期落库留痕，风控查询面在管理端按需扩展。
 */
public interface DemandRecordRepository extends JpaRepository<DemandRecord, Long> {
}
