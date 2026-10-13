package org.quwuting.quwutingservice.timershare.repository;

import org.quwuting.quwutingservice.timershare.entity.TimerShareJoin;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 计时分享加入流水仓储（2026-10-07，V42；2026-10-08 V45 增补列表查询） */
public interface TimerShareJoinRepository extends JpaRepository<TimerShareJoin, Long> {

    /** 某用户是否已加入某会话（调用方已持有该会话行的悲观锁，读到的即最终态） */
    Optional<TimerShareJoin> findByShareIdAndUserIdAndDeletedFalse(Long shareId, Long userId);

    /** 某会话的全部加入流水，按加入先后（id 升序即 created_at 顺序；status 装配用，上限 = 人数上限 5） */
    List<TimerShareJoin> findByShareIdAndDeletedFalseOrderByIdAsc(Long shareId);

    /**
     * 调用者作为<b>加入者</b>进过的会话 id（新→旧，调用方用 {@code PageRequest.of(0, n)} 限量）。
     * 点赞记录的取数入口之一（2026-10-13）；走 {@code qwt_idx_tsj_user_created (user_id, created_at)}，只取 id 不取整行。
     */
    @Query("SELECT j.shareId FROM TimerShareJoin j WHERE j.userId = :userId AND j.deleted = false "
            + "ORDER BY j.createdAt DESC, j.id DESC")
    List<Long> findRecentShareIdsByUser(@Param("userId") Long userId, Pageable pageable);
}
