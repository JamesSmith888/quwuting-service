package org.quwuting.quwutingservice.points.repository;

import org.quwuting.quwutingservice.points.entity.PointsUnlock;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PointsUnlockRepository extends JpaRepository<PointsUnlock, Long> {

    /**
     * 单用户解锁事务级串行化（2026-08-19 根因修复）：同一用户并发解锁不同/相同目标时，
     * 「查幂等 → 扣费 → 写解锁」三段式若交错执行，后发请求的解锁 INSERT 会撞唯一索引
     * 23505——旧实现靠 catch + entityManager.clear() 吞异常，但 Hibernate flush 失败后
     * 事务可能已被标记 rollback-only，幂等返回实际变为 HTTP 500（语义不可靠）。
     * 本锁按 user 粒度串行化整个解锁事务（一人同时解锁多目标无真实并发价值，串行正确），
     * 使 check-then-act 原子化、23505 路径变为不可达（异常兜底仍保留为纵深防御）。
     * pg_advisory_xact_lock 事务提交/回滚自动释放（对齐认可域 lockDailyTicket 先例）。
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
    void lockUserUnlock(@Param("lockKey") String lockKey);

    /** 单用户单目标的解锁记录（幂等校验：已解锁 = 不重复扣费） */
    Optional<PointsUnlock> findByUserIdAndTargetTypeAndTargetId(
            Long userId, PointsGateTargetType targetType, Long targetId);

    /** 批量解锁记录（照片列表/详情组装"当前用户已解锁"态，一次 IN 查询规避 N+1） */
    List<PointsUnlock> findByUserIdAndTargetTypeAndTargetIdIn(
            Long userId, PointsGateTargetType targetType, Collection<Long> targetIds);
}
