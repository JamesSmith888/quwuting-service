package org.quwuting.quwutingservice.points.repository;

import org.quwuting.quwutingservice.points.entity.PointsUnlock;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    /**
     * 解锁记录的<b>确定性原子写入</b>（2026-08-26 修复 DemandRelayService 的
     * 「save + catch 23505」不可靠幂等——PointsUnlock 主键 IDENTITY，persist 即
     * INSERT，撞唯一索引抛 DataIntegrityViolationException 后 Hibernate 已将事务
     * 标记 rollback-only，即便 catch 吞掉异常，提交仍抛
     * UnexpectedRollbackException = HTTP 500，见 22 号文档「工作台发放 500 根因」）。
     * <p>
     * 撞 UNIQUE(user_id, target_type, target_id)（qwt_uk_points_unlocks_user_target）
     * 时 DO NOTHING 返回 0 行——调用方按受影响行数判定：0 = 已存在（幂等跳过，
     * 同 user×dancer 此前已获批过），1 = 真实写入。与
     * {@code PointsTransactionRepository#upsertEarn} 同范式（主代码零 catch 23505）。
     * <p>
     * <b>enum 必须传 name() 字符串</b>（同 upsertEarn 根因：原生 SQL 绑定 enum 默认
     * ORDINAL → target_type 列落库序号而非枚举名，回查按 name() 匹配必然 0 条；
     * 本列 @Enumerated(STRING)，传 name() 与实体派生回查一致）。
     * transaction_id 传 NULL（获批解锁 = 免费解锁，无扣费流水；V42 DROP NOT NULL）。
     *
     * @return 受影响行数：1 = 写入成功；0 = 解锁记录已存在（幂等跳过）
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_points_unlocks " +
                   "(user_id, target_type, target_id, transaction_id, created_at) " +
                   "VALUES (:userId, :targetType, :targetId, NULL, :now) " +
                   "ON CONFLICT (user_id, target_type, target_id) DO NOTHING",
           nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("targetType") String targetType,
                       @Param("targetId") Long targetId,
                       @Param("now") LocalDateTime now);

    /** 单用户单目标的解锁记录（幂等校验：已解锁 = 不重复扣费） */
    Optional<PointsUnlock> findByUserIdAndTargetTypeAndTargetId(
            Long userId, PointsGateTargetType targetType, Long targetId);

    /** 批量解锁记录（照片列表/详情组装"当前用户已解锁"态，一次 IN 查询规避 N+1） */
    List<PointsUnlock> findByUserIdAndTargetTypeAndTargetIdIn(
            Long userId, PointsGateTargetType targetType, Collection<Long> targetIds);

    /**
     * 某用户自某时刻起的解锁记录（2026-08-24 联系方式每日首免判定）：
     * 取当日全部 DANCER_CONTACT 解锁（target_id = 舞伴 ID），调用方据此判断
     * "今日是否已对任意有门槛舞伴解锁过"——每日首次获取联系方式免费。
     */
    List<PointsUnlock> findByUserIdAndTargetTypeAndCreatedAtGreaterThanEqual(
            Long userId, PointsGateTargetType targetType, LocalDateTime since);
}
