package org.quwuting.quwutingservice.points.repository;

import org.quwuting.quwutingservice.points.entity.PointsAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
