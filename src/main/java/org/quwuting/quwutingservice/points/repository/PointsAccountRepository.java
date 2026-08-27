package org.quwuting.quwutingservice.points.repository;

import org.quwuting.quwutingservice.points.entity.PointsAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PointsAccountRepository extends JpaRepository<PointsAccount, Long> {

    Optional<PointsAccount> findByUserId(Long userId);

    /**
     * 原子条件扣减（赠送防并发超扣的核心，无锁）：
     * {@code balance >= :amount} 才扣，affected rows = 0 即余额不足（调用方抛 1011）。
     * spentTotal 同步累加——与余额同一 UPDATE 原子完成。
     */
    @Modifying
    @Query("""
            UPDATE PointsAccount pa
            SET pa.balance = pa.balance - :amount,
                pa.spentTotal = pa.spentTotal + :amount
            WHERE pa.userId = :userId AND pa.balance >= :amount
            """)
    int deductBalance(@Param("userId") Long userId, @Param("amount") long amount);

    /** 原子累加（挣取路径：打卡/采纳/管理加分） */
    @Modifying
    @Query("""
            UPDATE PointsAccount pa
            SET pa.balance = pa.balance + :amount,
                pa.earnedTotal = pa.earnedTotal + :amount
            WHERE pa.userId = :userId
            """)
    int addBalance(@Param("userId") Long userId, @Param("amount") long amount);

    /**
     * 批量余额快照（2026-08-27 管理端用户列表聚合，docs/agents/23）：一次查询
     * 覆盖一页用户，避免 N+1。返回 Object[]{userId, balance}；无账户用户不出现在
     * 结果（从未参与积分活动，调用方按 0 兜底，见 PointsAccount 注释）。
     */
    @Query("SELECT a.userId, a.balance FROM PointsAccount a WHERE a.userId IN :userIds")
    List<Object[]> findBalancesByUserIds(@Param("userIds") Collection<Long> userIds);

    /**
     * 批量账户快照（2026-08-27 用户管理增强——详情页积分账户收支维度）：一次查询
     * 覆盖用户集的 balance + earnedTotal + spentTotal 三元组。
     * 返回 Object[]{userId, balance, earnedTotal, spentTotal}；无账户用户不出现在
     * 结果（调用方按 0 兜底）。
     */
    @Query("SELECT a.userId, a.balance, a.earnedTotal, a.spentTotal FROM PointsAccount a WHERE a.userId IN :userIds")
    List<Object[]> findAccountSummariesByUserIds(@Param("userIds") Collection<Long> userIds);
}
