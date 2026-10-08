package org.quwuting.quwutingservice.timershare.repository;

import org.quwuting.quwutingservice.timershare.entity.TimerShareJoin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 计时分享加入流水仓储（2026-10-07，V42；2026-10-08 V45 增补列表查询） */
public interface TimerShareJoinRepository extends JpaRepository<TimerShareJoin, Long> {

    /** 某用户是否已加入某会话（调用方已持有该会话行的悲观锁，读到的即最终态） */
    Optional<TimerShareJoin> findByShareIdAndUserIdAndDeletedFalse(Long shareId, Long userId);

    /** 某会话的全部加入流水，按加入先后（id 升序即 created_at 顺序；status 装配用，上限 = 人数上限 5） */
    List<TimerShareJoin> findByShareIdAndDeletedFalseOrderByIdAsc(Long shareId);
}
