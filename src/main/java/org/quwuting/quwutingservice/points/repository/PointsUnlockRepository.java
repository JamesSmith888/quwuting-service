package org.quwuting.quwutingservice.points.repository;

import org.quwuting.quwutingservice.points.entity.PointsUnlock;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PointsUnlockRepository extends JpaRepository<PointsUnlock, Long> {

    /** 单用户单目标的解锁记录（幂等校验：已解锁 = 不重复扣费） */
    Optional<PointsUnlock> findByUserIdAndTargetTypeAndTargetId(
            Long userId, PointsGateTargetType targetType, Long targetId);

    /** 批量解锁记录（照片列表/详情组装"当前用户已解锁"态，一次 IN 查询规避 N+1） */
    List<PointsUnlock> findByUserIdAndTargetTypeAndTargetIdIn(
            Long userId, PointsGateTargetType targetType, Collection<Long> targetIds);
}
