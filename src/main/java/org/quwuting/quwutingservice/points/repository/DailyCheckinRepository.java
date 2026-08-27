package org.quwuting.quwutingservice.points.repository;

import org.quwuting.quwutingservice.points.entity.DailyCheckin;
import org.springframework.data.domain.Pageable;
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

    /**
     * 批量统计：指定用户集的最近打卡时间（2026-08-27 用户管理增强——「最近活跃」
     * 四源之一：打卡是日常高频信号）。返回 Object[]{userId, MAX(createdAt)}；
     * 无打卡用户不出现在结果。
     */
    @Query("SELECT c.userId, MAX(c.createdAt) FROM DailyCheckin c " +
            "WHERE c.userId IN :userIds GROUP BY c.userId")
    List<Object[]> findLatestGroupByUserIds(@Param("userIds") Collection<Long> userIds);

    /**
     * 单用户打卡日期倒序（2026-08-27 用户管理增强——详情页「连续打卡 N 天」：
     * 应用层从最近一天往回数连续日期，今天未打不打断连续（锚点 = 今天或昨天）。
     * LIMIT 400 覆盖一年以上打卡记录，足够支撑连续天数计算（连续超 400 天按
     * 400 计，实际已无区分意义）。
     */
    @Query("SELECT c.checkinDate FROM DailyCheckin c WHERE c.userId = :userId " +
            "ORDER BY c.checkinDate DESC")
    List<LocalDate> findDatesByUserIdDesc(@Param("userId") Long userId, Pageable pageable);
}
