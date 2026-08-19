package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerRecognition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DancerRecognitionRepository extends JpaRepository<DancerRecognition, Long> {

    /**
     * 事务级咨询锁：串行化同 (user, dancer, date) 的并发认可 toggle（2026-08-15 新增，
     * 对齐 VenueReactionService 单票路径）——"查当日记录 → 删/换 → 插"若不串行化，
     * 两个并发请求可能同时命中/新建，破坏"一日一枚表情"与删除幂等不变量。
     * pg_advisory_xact_lock 事务提交/回滚自动释放。
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
    void lockDailyTicket(@Param("lockKey") String lockKey);

    /**
     * 批量删除认可记录（2026-08-15 根因修复：与标签批量删除同语义——派生删除
     * 的 SELECT+em.remove 延迟实体删除在并发/事务内 flush 场景会产生
     * StaleObjectStateException；@Modifying 批量删除幂等且无实体管理状态）。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM DancerRecognition r WHERE r.id = :id")
    void deleteRecognitionById(@Param("id") Long id);

    /**
     * 精确命中"当日记录"（toggle 用，每日一记模型的核心查询）。
     * 唯一约束 (userId, dancerId, recognitionDate) 保证至多一行：
     * 命中 → 取消（物理删除该行 + 级联删除其标签）；未命中 → 插入今日新行。
     */
    Optional<DancerRecognition> findByUserIdAndDancerIdAndRecognitionDate(
            Long userId, Long dancerId, LocalDate recognitionDate);

    /**
     * 当前用户"今日已认可"的舞伴 ID 集合（个人状态，实时查询不缓存）。
     * "已认可"语义 = 今日存在该记录——次日自动恢复可认可状态。
     */
    @Query("SELECT r.dancerId FROM DancerRecognition r " +
           "WHERE r.userId = :userId AND r.recognitionDate = :date")
    List<Long> findTodayRecognizedDancerIds(@Param("userId") Long userId,
                                            @Param("date") LocalDate date);

    /**
     * 批量舞伴内当前用户"今日已认可"的舞伴 ID（列表页个人状态，实时查询不缓存）。
     * 与 {@link #findTodayRecognizedDancerIds} 同语义，按舞伴集合过滤。
     */
    @Query("SELECT r.dancerId FROM DancerRecognition r " +
           "WHERE r.userId = :userId AND r.dancerId IN :dancerIds AND r.recognitionDate = :date")
    List<Long> findTodayRecognizedDancerIdsIn(@Param("userId") Long userId,
                                              @Param("dancerIds") List<Long> dancerIds,
                                              @Param("date") LocalDate date);

    /**
     * 批量舞伴内当前用户"今日认可记录"的 (recognitionId, dancerId)（列表页个人投票态，
     * 2026-08-19 新增：列表卡片 reaction 区域 chip 活跃态数据源）。与
     * {@link #findTodayRecognizedDancerIdsIn} 同过滤，但返回记录 ID 供关联标签查询
     * （配合 {@code DancerRecognitionTagRepository#findTagsByRecognitionIds}），
     * 避免按认可记录逐条查标签的 N+1。
     */
    @Query("SELECT r.id, r.dancerId FROM DancerRecognition r " +
           "WHERE r.userId = :userId AND r.dancerId IN :dancerIds AND r.recognitionDate = :date")
    List<Object[]> findTodayRecognitionIdsByDancerIds(@Param("userId") Long userId,
                                                      @Param("dancerIds") List<Long> dancerIds,
                                                      @Param("date") LocalDate date);

    /**
     * 单舞伴四窗口认可聚合（详情页 / 我的认可页的聚合缓存 loader）。
     * 返回 Object[]{countAll, countToday, count7d, count30d}。
     * "今日/7天/30天"为真实时间窗口（锚点"此刻"，与 Reaction 统计口径一致，见
     * {@code VenueReactionRepository#aggregateByVenue} 注释）。
     */
    @Query(value = """
            SELECT COUNT(*) AS count_all,
                   SUM(CASE WHEN r.created_at >= :sinceToday THEN 1 ELSE 0 END) AS count_today,
                   SUM(CASE WHEN r.created_at >= :since7d THEN 1 ELSE 0 END) AS count_7d,
                   SUM(CASE WHEN r.created_at >= :since30d THEN 1 ELSE 0 END) AS count_30d
            FROM qwt_dancer_recognitions r
            WHERE r.dancer_id = :dancerId AND r.deleted = false
            """, nativeQuery = true)
    Object[] aggregateByDancer(@Param("dancerId") Long dancerId,
                               @Param("sinceToday") LocalDateTime sinceToday,
                               @Param("since7d") LocalDateTime since7d,
                               @Param("since30d") LocalDateTime since30d);

    /**
     * 批量舞伴的认可计数（列表页用），一次 IN 查询同时返回 countAll / countToday / count7d。
     * 返回 Object[]{dancerId, countAll, countToday, count7d}。
     */
    @Query(value = """
            SELECT r.dancer_id,
                   COUNT(*) AS count_all,
                   COUNT(*) FILTER (WHERE r.created_at >= :sinceToday) AS count_today,
                   COUNT(*) FILTER (WHERE r.created_at >= :since7d) AS count_7d
            FROM qwt_dancer_recognitions r
            WHERE r.dancer_id IN :dancerIds AND r.deleted = false
            GROUP BY r.dancer_id
            """, nativeQuery = true)
    List<Object[]> countByDancerIds(@Param("dancerIds") List<Long> dancerIds,
                                    @Param("sinceToday") LocalDateTime sinceToday,
                                    @Param("since7d") LocalDateTime since7d);

    /**
     * 近 N 日每日认可数（详情页"最近认可：昨天 +3 前天 +5"动态信息的数据源）。
     * 按 {@code recognitionDate}（自然日）分组——动态信息以自然日为粒度，
     * 与窗口统计（createdAt 滚动锚点）职责分离，同 VenueReaction 的
     * reactionDate/createdAt 分离约定。
     * 返回 Object[]{recognitionDate, count}。
     */
    @Query(value = """
            SELECT r.recognition_date, COUNT(*)
            FROM qwt_dancer_recognitions r
            WHERE r.dancer_id = :dancerId AND r.deleted = false
              AND r.recognition_date >= :sinceDate
            GROUP BY r.recognition_date
            """, nativeQuery = true)
    List<Object[]> countByDay(@Param("dancerId") Long dancerId,
                              @Param("sinceDate") LocalDate sinceDate);

    /**
     * 我的认可记录（按认可时间倒序），返回 Object[]{recognitionId, dancerId, recognitionDate}。
     * 仅登录用户调用（个人数据，无缓存）。
     */
    @Query("SELECT r.id, r.dancerId, r.recognitionDate FROM DancerRecognition r " +
           "WHERE r.userId = :userId AND r.deleted = false ORDER BY r.createdAt DESC")
    List<Object[]> findMyRecognitions(@Param("userId") Long userId);
}
