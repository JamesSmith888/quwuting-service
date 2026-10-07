package org.quwuting.quwutingservice.timershare.repository;

import org.quwuting.quwutingservice.timershare.entity.TimerShareJoin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 计时分享加入流水仓储（2026-10-07，V42） */
public interface TimerShareJoinRepository extends JpaRepository<TimerShareJoin, Long> {

    /** 某用户是否已加入某会话（调用方已持有该会话行的悲观锁，读到的即最终态） */
    Optional<TimerShareJoin> findByShareIdAndUserIdAndDeletedFalse(Long shareId, Long userId);
}
