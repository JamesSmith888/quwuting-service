package org.quwuting.quwutingservice.timershare.repository;

import jakarta.persistence.LockModeType;
import org.quwuting.quwutingservice.timershare.entity.TimerShare;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 计时分享会话仓储（2026-10-07，V42）。
 * <p>
 * 两条带 {@code FOR UPDATE} 的查询是并发正确性的<b>唯一手段</b>：
 * 加入路径对分享行加悲观写锁，把「人数上限判定 + join_count 自增 + 流水写入」串行化；
 * 创建路径对 (host, session_key) 行加锁，把「存在则刷新、不存在则新建」的读改写串行化。
 * 不走 save + catch 唯一键的原因见 V42 头注。
 */
public interface TimerShareRepository extends JpaRepository<TimerShare, Long> {

    Optional<TimerShare> findByTokenAndDeletedFalse(String token);

    /** 加入 / 关闭：对分享行加悲观写锁 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM TimerShare s WHERE s.token = :token AND s.deleted = false")
    Optional<TimerShare> findByTokenForUpdate(@Param("token") String token);

    /** 创建 / 刷新：对 (host, session_key) 行加悲观写锁（行不存在时无锁可加，由唯一键 + 调用方重试兜底） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM TimerShare s WHERE s.hostUserId = :hostUserId "
            + "AND s.sessionKey = :sessionKey AND s.deleted = false")
    Optional<TimerShare> findByHostAndSessionKeyForUpdate(@Param("hostUserId") Long hostUserId,
                                                          @Param("sessionKey") String sessionKey);

    /** 主持方滚动窗口内新建的会话数（每日新建上限；刷新同一场不新增行，故不计入） */
    @Query("SELECT COUNT(s) FROM TimerShare s WHERE s.hostUserId = :hostUserId "
            + "AND s.createdAt >= :since AND s.deleted = false")
    long countNewSince(@Param("hostUserId") Long hostUserId, @Param("since") LocalDateTime since);

    /**
     * 调用者作为<b>主持方</b>开过的会话 id（新→旧，调用方用 {@code PageRequest.of(0, n)} 限量）。
     * 点赞记录的取数入口之一（2026-10-13）；走 {@code qwt_idx_ts_host_created (host_user_id, created_at)}，只取 id 不取整行。
     */
    @Query("SELECT s.id FROM TimerShare s WHERE s.hostUserId = :hostUserId AND s.deleted = false "
            + "ORDER BY s.createdAt DESC, s.id DESC")
    List<Long> findRecentIdsByHost(@Param("hostUserId") Long hostUserId, Pageable pageable);
}
