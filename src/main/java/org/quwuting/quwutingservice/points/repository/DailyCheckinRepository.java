package org.quwuting.quwutingservice.points.repository;

import org.quwuting.quwutingservice.points.entity.DailyCheckin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DailyCheckinRepository extends JpaRepository<DailyCheckin, Long> {

    Optional<DailyCheckin> findByUserIdAndCheckinDate(Long userId, LocalDate checkinDate);

    /**
     * 打卡事务级串行化（2026-08-19 根因修复）：零点并发打卡场景下「查打卡 → 插打卡」
     * 若交错执行，后发请求的 INSERT 会撞唯一索引 23505——旧实现靠 catch +
     * entityManager.clear() 吞异常，但 Hibernate flush 失败后事务可能已被标记
     * rollback-only，幂等返回实际变为 HTTP 500（语义不可靠）。
     * 本锁按 user 粒度串行化整个打卡事务（一人一天只打一次，串行正确），
     * 使 check-then-act 原子化、23505 路径变为不可达。
     * pg_advisory_xact_lock 事务提交/回滚自动释放（对齐认可域 lockDailyTicket 先例）。
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
    void lockUserCheckin(@Param("lockKey") String lockKey);

    /**
     * 批量统计：指定用户集的打卡天数（2026-08-27 贡献档案/管理端用户列表聚合，
     * docs/agents/23）：UNIQUE(user_id, checkin_date) 保证一人一天一行，条数 = 天数。
     * 返回 Object[]{userId, count}；无打卡用户不出现在结果（调用方按 0 兜底）。
     */
    @Query("SELECT c.userId, COUNT(c) FROM DailyCheckin c WHERE c.userId IN :userIds GROUP BY c.userId")
    List<Object[]> countGroupByUserIds(@Param("userIds") Collection<Long> userIds);
}
