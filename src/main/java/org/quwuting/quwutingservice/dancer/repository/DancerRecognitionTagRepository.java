package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerRecognitionTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DancerRecognitionTagRepository extends JpaRepository<DancerRecognitionTag, Long> {

    /**
     * 删除某认可携带的全部标签（取消认可 / 换票旧标签清空）。
     * <p>
     * 2026-08-15 根因修复：必须<b>批量删除（@Modifying）</b>——旧实现为 Spring Data
     * 派生删除（SELECT + em.remove 逐实体延迟到 flush 执行），与同事务内聚合查询
     * （buildStats → aggregateByDancer 触发 flush）及同键并发（同 user+dancer+date
     * 双请求）组合时，后发请求的实体删除会命中"行已被删"→ StaleObjectStateException
     * （见 GlobalExceptionHandler 的 ObjectOptimisticLockingFailureException 报错）。
     * 批量删除：立即执行、不存在行 = 0 行影响（幂等）、无实体管理状态，
     * clearAutomatically 清空持久化上下文（防批量删除后遗留脏实体）。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM DancerRecognitionTag t WHERE t.recognitionId = :recognitionId")
    void deleteByRecognitionId(@Param("recognitionId") Long recognitionId);

    /**
     * 删除某认可携带的单个标签（2026-08-15 多选模式：每枚表情独立 toggle）。
     * 批量删除语义同上（幂等、无实体管理状态）。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM DancerRecognitionTag t WHERE t.recognitionId = :recognitionId AND t.tag = :tag")
    void deleteByRecognitionIdAndTag(@Param("recognitionId") Long recognitionId, @Param("tag") String tag);

    /**
     * 认可标签的<b>确定性原子写入</b>（2026-08-20 根因修复：替代
     * 「save + catch 23505 + clear + 继续循环」——撞 UNIQUE(recognition_id, tag) 后
     * 事务已中止（25P02），循环内后续标签的 save 必然抛 JpaSystemException → HTTP 500）。
     * <p>
     * 命中 V1 唯一索引 {@code qwt_uk_drt_recognition_tag}（UNIQUE(recognition_id,
     * tag)，列清单推断）时 DO NOTHING 返回 0 行——幂等忽略重复标签（去重先于
     * 本写入：legacy 路径 {@code validateAndDedupeTags} 已去重，本 upsert 收口
     * 并发/边界重复）。
     *
     * @return 受影响行数：1 = 新标签；0 = 该认可已带此标签（幂等跳过）
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_dancer_recognition_tags " +
                   "(recognition_id, dancer_id, user_id, tag, created_at, updated_at, deleted) " +
                   "VALUES (:recognitionId, :dancerId, :userId, :tag, :now, :now, false) " +
                   "ON CONFLICT (recognition_id, tag) DO NOTHING",
           nativeQuery = true)
    int upsertRecognitionTag(@Param("recognitionId") Long recognitionId,
                             @Param("dancerId") Long dancerId,
                             @Param("userId") Long userId,
                             @Param("tag") String tag,
                             @Param("now") LocalDateTime now);

    /**
     * 按认可记录 ID 批量取标签（我的认可明细用）。
     * 返回 Object[]{recognitionId, tag}。
     */
    @Query("SELECT t.recognitionId, t.tag FROM DancerRecognitionTag t " +
           "WHERE t.recognitionId IN :recognitionIds AND t.deleted = false")
    List<Object[]> findTagsByRecognitionIds(@Param("recognitionIds") List<Long> recognitionIds);

    /**
     * 单舞伴标签聚合（详情页认可 chip），按计数倒序。
     * 返回 Object[]{tag, countAll, countToday, count7d, count30d}——
     * 2026-08-15 窗口化：认可 chip 默认展示近7天、可切换近30天/全部（对齐 Reaction
     * 四窗口统计口径，窗口锚点 = createdAt "此刻"，同 DancerRecognitionRepository）。
     */
    @Query(value = """
            SELECT t.tag,
                   COUNT(*) AS count_all,
                   SUM(CASE WHEN t.created_at >= :sinceToday THEN 1 ELSE 0 END) AS count_today,
                   SUM(CASE WHEN t.created_at >= :since7d THEN 1 ELSE 0 END) AS count_7d,
                   SUM(CASE WHEN t.created_at >= :since30d THEN 1 ELSE 0 END) AS count_30d
            FROM qwt_dancer_recognition_tags t
            WHERE t.dancer_id = :dancerId AND t.deleted = false
            GROUP BY t.tag
            ORDER BY COUNT(*) DESC, t.tag ASC
            """, nativeQuery = true)
    List<Object[]> aggregateByDancer(@Param("dancerId") Long dancerId,
                                     @Param("sinceToday") LocalDateTime sinceToday,
                                     @Param("since7d") LocalDateTime since7d,
                                     @Param("since30d") LocalDateTime since30d);

    /**
     * 批量舞伴标签聚合（列表页 Top 标签，一次 IN 查询覆盖整页舞伴，规避 N+1）。
     * 返回 Object[]{dancerId, tag, countAll, countToday, count7d, count30d}。
     */
    @Query(value = """
            SELECT t.dancer_id, t.tag,
                   COUNT(*) AS count_all,
                   SUM(CASE WHEN t.created_at >= :sinceToday THEN 1 ELSE 0 END) AS count_today,
                   SUM(CASE WHEN t.created_at >= :since7d THEN 1 ELSE 0 END) AS count_7d,
                   SUM(CASE WHEN t.created_at >= :since30d THEN 1 ELSE 0 END) AS count_30d
            FROM qwt_dancer_recognition_tags t
            WHERE t.dancer_id IN :dancerIds AND t.deleted = false
            GROUP BY t.dancer_id, t.tag
            ORDER BY t.dancer_id, COUNT(*) DESC
            """, nativeQuery = true)
    List<Object[]> aggregateByDancerIds(@Param("dancerIds") List<Long> dancerIds,
                                        @Param("sinceToday") LocalDateTime sinceToday,
                                        @Param("since7d") LocalDateTime since7d,
                                        @Param("since30d") LocalDateTime since30d);
}
