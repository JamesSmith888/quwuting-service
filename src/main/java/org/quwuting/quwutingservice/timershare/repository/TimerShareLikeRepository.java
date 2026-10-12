package org.quwuting.quwutingservice.timershare.repository;

import org.quwuting.quwutingservice.timershare.entity.TimerShareLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 给同行者点赞仓储（2026-10-12，V48）。toggle 模型 = 软删 + 全量唯一，与热度点赞
 * {@code VenueCrowdReportLikeRepository} 同构：
 * <ul>
 *   <li>{@link #like}：{@code INSERT … ON DUPLICATE KEY UPDATE} 幂等 toggle ON。受影响行数
 *       <b>1 = 该对首次赞</b>（唯一触发被赞通知的机会）；2 = 取消后恢复或重复赞（不重发）；并发双击由唯一键兜底，
 *       无需应用层锁，也没有事务内捕获唯一键异常的 rollback-only 问题；</li>
 *   <li>{@link #unlike}：UPDATE 软删 toggle OFF（幂等，未赞时影响 0 行）。</li>
 * </ul>
 * 一张会话最多 1 位主持方 + 5 位加入者，所以一对多读取（{@code findBy…}）行数天然 ≤ 5，不分页、不批量。
 */
public interface TimerShareLikeRepository extends JpaRepository<TimerShareLike, Long> {

    /**
     * 赞（幂等 toggle ON）。⚠️ 时间口径：created_at / updated_at 由 Java 传 JVM 本地时刻，
     * 禁 DB now()——全库约定（同 {@code VenueCrowdReportLikeRepository#like}）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_timer_share_likes "
            + "(created_at, updated_at, deleted, share_id, liker_id, liked_user_id, code) "
            + "VALUES (:now, :now, false, :shareId, :likerId, :likedUserId, :code) "
            + "ON DUPLICATE KEY UPDATE deleted = false, updated_at = :now, code = :code",
            nativeQuery = true)
    int like(@Param("shareId") Long shareId, @Param("likerId") Long likerId,
             @Param("likedUserId") Long likedUserId, @Param("code") String code,
             @Param("now") LocalDateTime now);

    /** 取消赞（幂等 toggle OFF）：软删；未赞过影响 0 行。返回受影响行数 */
    @Modifying
    @Query("UPDATE TimerShareLike l SET l.deleted = true, l.updatedAt = :now "
            + "WHERE l.shareId = :shareId AND l.likerId = :likerId AND l.likedUserId = :likedUserId "
            + "AND l.deleted = false")
    int unlike(@Param("shareId") Long shareId, @Param("likerId") Long likerId,
               @Param("likedUserId") Long likedUserId, @Param("now") LocalDateTime now);

    /** 调用者在这张会话里赞过的人（读状态：「我赞过 X」） */
    List<TimerShareLike> findByShareIdAndLikerIdAndDeletedFalse(Long shareId, Long likerId);

    /** 这张会话里赞了调用者的人（读状态：「X 赞了我」） */
    List<TimerShareLike> findByShareIdAndLikedUserIdAndDeletedFalse(Long shareId, Long likedUserId);

    /** 某人在这张会话里收到的赞数（被赞通知正文里的累计数） */
    long countByShareIdAndLikedUserIdAndDeletedFalse(Long shareId, Long likedUserId);
}
