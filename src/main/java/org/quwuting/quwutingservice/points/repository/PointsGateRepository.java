package org.quwuting.quwutingservice.points.repository;

import org.quwuting.quwutingservice.points.entity.PointsGate;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PointsGateRepository extends JpaRepository<PointsGate, Long> {

    /** 目标门槛（含软删行——服务层判断软删 = 已清除；用于幂等 upsert 与查询） */
    Optional<PointsGate> findByTargetTypeAndTargetId(PointsGateTargetType targetType, Long targetId);

    /** 批量目标门槛（照片列表/详情组装解锁态用，一次 IN 查询规避 N+1） */
    List<PointsGate> findByTargetTypeAndTargetIdInAndDeletedFalse(
            PointsGateTargetType targetType, Collection<Long> targetIds);
}
