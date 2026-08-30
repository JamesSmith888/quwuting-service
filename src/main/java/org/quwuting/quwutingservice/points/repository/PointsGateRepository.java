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

    /**
     * 多类型合并批量门槛（2026-08-30：详情 fetchPhotos 照片/视频门槛合并为一次
     * IN 查询——target_type IN + target_id IN，跨洲往返约束下省 1 次往返）。
     * ⚠️ 语义约束：同一 targetId 不得出现在多个 targetType 下（媒体 id 全局唯一、
     * kind 创建时固定——照片/视频门槛 target_id 同源 qwt_dancer_photos.id，
     * 天然满足），结果按 targetId 映射无歧义。
     */
    List<PointsGate> findByTargetTypeInAndTargetIdInAndDeletedFalse(
            Collection<PointsGateTargetType> targetTypes, Collection<Long> targetIds);
}
