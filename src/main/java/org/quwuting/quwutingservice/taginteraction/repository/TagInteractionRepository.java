package org.quwuting.quwutingservice.taginteraction.repository;

import org.quwuting.quwutingservice.taginteraction.entity.TagInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TagInteractionRepository extends JpaRepository<TagInteraction, Long> {

    Optional<TagInteraction> findByUserIdAndVenueIdAndTag(Long userId, Long venueId, String tag);

    /** 统计场所各维度的评分均值和人数（score 非空且未删除），返回 Object[]{tag, avgScore, count} */
    @Query("SELECT ti.tag, AVG(ti.score), COUNT(ti) FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.score IS NOT NULL AND ti.deleted = false " +
           "GROUP BY ti.tag")
    List<Object[]> aggregateScoresByVenueGroupByTag(@Param("venueId") Long venueId);

    /**
     * 时间窗口内各维度评分聚合（仅供 VenueHeatService 满意度计算使用），返回 Object[]{tag, avgScore, count}。
     * until 为排他上界——热度统计口径固定为「截至昨日」，见 VenueHeatService 的 statsAsOfDate 约定。
     * <p>
     * 窗口按 createdAt（评分创建时间）而非 updatedAt：与 mega-query 的 ratingCount30d 同口径
     * （"近30天产生的评分"），改分不把记录拉回窗口——防"定期改分让满意度/计数常青"的刷分漏洞。
     */
    @Query("SELECT ti.tag, AVG(ti.score), COUNT(ti) FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.score IS NOT NULL AND ti.deleted = false " +
           "AND ti.createdAt >= :since AND ti.createdAt < :until " +
           "GROUP BY ti.tag")
    List<Object[]> aggregateScoresByVenueSinceGroupByTag(@Param("venueId") Long venueId,
                                                         @Param("since") LocalDateTime since,
                                                         @Param("until") LocalDateTime until);

    /**
     * 三窗口评分聚合的单次往返查询（TagAggregateStatsService 专用）。
     * 返回 Object[]{tag, avgAll, countAll, avg30d, count30d, avg7d, count7d}。
     * <p>
     * 使用原生 SQL：AVG/SUM(CASE WHEN...) 条件聚合同时覆盖三个时间窗口，
     * 与 VenueRepository.countHeatCounters 的标量子查询 mega-query 同属"压缩 DB 往返"手段。
     */
    @Query(value = "SELECT ti.tag, " +
                   "AVG(ti.score) AS avg_all, COUNT(*) AS count_all, " +
                   "AVG(CASE WHEN ti.updated_at >= :since30d THEN ti.score END) AS avg_30d, " +
                   "SUM(CASE WHEN ti.updated_at >= :since30d THEN 1 ELSE 0 END) AS count_30d, " +
                   "AVG(CASE WHEN ti.updated_at >= :since7d THEN ti.score END) AS avg_7d, " +
                   "SUM(CASE WHEN ti.updated_at >= :since7d THEN 1 ELSE 0 END) AS count_7d " +
                   "FROM qwt_tag_interactions ti " +
                   "WHERE ti.venue_id = :venueId AND ti.deleted = false AND ti.score IS NOT NULL " +
                   "GROUP BY ti.tag",
           nativeQuery = true)
    List<Object[]> aggregateScoresMultiWindowByTag(@Param("venueId") Long venueId,
                                                   @Param("since30d") LocalDateTime since30d,
                                                   @Param("since7d") LocalDateTime since7d);

    /** 当前用户对指定场所的全部评分状态，返回 Object[]{tag, score} */
    @Query("SELECT ti.tag, ti.score FROM TagInteraction ti " +
           "WHERE ti.userId = :userId AND ti.venueId = :venueId AND ti.deleted = false AND ti.score IS NOT NULL")
    List<Object[]> findUserScoresByVenue(@Param("userId") Long userId, @Param("venueId") Long venueId);
}
