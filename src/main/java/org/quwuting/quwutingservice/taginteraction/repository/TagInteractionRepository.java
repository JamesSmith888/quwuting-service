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

    /** 统计场所各标签的点赞数（仅 liked=true 且未删除），返回 Object[]{tag, count} */
    @Query("SELECT ti.tag, COUNT(ti) FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.liked = true AND ti.deleted = false " +
           "GROUP BY ti.tag")
    List<Object[]> countLikesByVenueGroupByTag(@Param("venueId") Long venueId);

    /**
     * 批量统计多个场所各标签的点赞数（列表页展示标签热度用），返回 Object[]{venueId, tag, count}。
     * 一次查询覆盖整页场所，避免按场所逐条查询造成的 N+1。
     */
    @Query("SELECT ti.venueId, ti.tag, COUNT(ti) FROM TagInteraction ti " +
           "WHERE ti.venueId IN :venueIds AND ti.liked = true AND ti.deleted = false " +
           "GROUP BY ti.venueId, ti.tag")
    List<Object[]> countLikesByVenueIdsGroupByTag(@Param("venueIds") List<Long> venueIds);

    /** 统计场所各维度的评分均值和人数（score 非空且未删除），返回 Object[]{tag, avgScore, count} */
    @Query("SELECT ti.tag, AVG(ti.score), COUNT(ti) FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.score IS NOT NULL AND ti.deleted = false " +
           "GROUP BY ti.tag")
    List<Object[]> aggregateScoresByVenueGroupByTag(@Param("venueId") Long venueId);

    /**
     * 时间窗口内各维度评分聚合（仅供 VenueHeatService 满意度计算使用），返回 Object[]{tag, avgScore, count}。
     * until 为排他上界——热度统计口径固定为「截至昨日」，见 VenueHeatService 的 statsAsOfDate 约定。
     */
    @Query("SELECT ti.tag, AVG(ti.score), COUNT(ti) FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.score IS NOT NULL AND ti.deleted = false " +
           "AND ti.updatedAt >= :since AND ti.updatedAt < :until " +
           "GROUP BY ti.tag")
    List<Object[]> aggregateScoresByVenueSinceGroupByTag(@Param("venueId") Long venueId,
                                                         @Param("since") LocalDateTime since,
                                                         @Param("until") LocalDateTime until);

    /** 查询用户对指定场所各标签的点赞状态（liked=true 的记录） */
    @Query("SELECT ti.tag FROM TagInteraction ti " +
           "WHERE ti.userId = :userId AND ti.venueId = :venueId AND ti.liked = true AND ti.deleted = false")
    List<String> findLikedTagsByUserAndVenue(@Param("userId") Long userId, @Param("venueId") Long venueId);

    /** 查询用户对指定场所各标签的评分记录 */
    @Query("SELECT ti FROM TagInteraction ti " +
           "WHERE ti.userId = :userId AND ti.venueId = :venueId AND ti.score IS NOT NULL AND ti.deleted = false")
    List<TagInteraction> findScoredInteractionsByUserAndVenue(@Param("userId") Long userId,
                                                              @Param("venueId") Long venueId);

    /** 统计时间范围内的评价记录数（score 非空，热度趋势用） */
    @Query("SELECT COUNT(ti) FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.score IS NOT NULL AND ti.deleted = false AND ti.updatedAt >= :since")
    long countRatingsSince(@Param("venueId") Long venueId, @Param("since") LocalDateTime since);

    /** 统计时间范围内的点赞记录数（liked=true，热度趋势用） */
    @Query("SELECT COUNT(ti) FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.liked = true AND ti.deleted = false AND ti.updatedAt >= :since")
    long countLikesSince(@Param("venueId") Long venueId, @Param("since") LocalDateTime since);

    /** 统计场所的评价总人数（去重 userId，score 非空） */
    @Query("SELECT COUNT(DISTINCT ti.userId) FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.score IS NOT NULL AND ti.deleted = false")
    long countDistinctRatersByVenueId(@Param("venueId") Long venueId);

    // ─── 合并查询（减少远程 DB 往返，性能优化） ─────────────────────────────

    /** 单行多列聚合投影：评价数 + 点赞数 + 去重评价人数 */
    interface HeatInteractionStats {
        Long getRatingcount();
        Long getLikecount();
        Long getRaters();
    }

    /**
     * 单次往返同时获取：近30天评价数、近30天点赞数、总评价人数（去重）。
     * 使用原生 SQL：JPQL 不支持 COUNT(DISTINCT CASE WHEN ...) 语法。
     * until 为排他上界——热度统计口径固定为「截至昨日」，见 VenueHeatService 的 statsAsOfDate 约定。
     */
    @Query(value = "SELECT " +
                   "COUNT(CASE WHEN ti.score IS NOT NULL AND ti.updated_at >= :since AND ti.updated_at < :until THEN 1 END) as ratingcount, " +
                   "COUNT(CASE WHEN ti.liked = true AND ti.updated_at >= :since AND ti.updated_at < :until THEN 1 END) as likecount, " +
                   "COUNT(DISTINCT CASE WHEN ti.score IS NOT NULL THEN ti.user_id END) as raters " +
                   "FROM qwt_tag_interactions ti WHERE ti.venue_id = :venueId AND ti.deleted = false",
           nativeQuery = true)
    HeatInteractionStats countInteractionsForHeat(@Param("venueId") Long venueId,
                                                  @Param("since") LocalDateTime since,
                                                  @Param("until") LocalDateTime until);

    /**
     * 单次往返同时获取各维度的全量/30天/7天评分聚合。
     * 返回 Object[]{tag, avgAll, countAll, avg30d, count30d, avg7d, count7d}。
     * AVG(CASE WHEN ... THEN score END) 中不满足条件的行返回 NULL，AVG 自动忽略 NULL。
     */
    @Query("SELECT ti.tag, " +
           "AVG(ti.score), COUNT(ti), " +
           "AVG(CASE WHEN ti.updatedAt >= :since30d THEN ti.score END), " +
           "SUM(CASE WHEN ti.updatedAt >= :since30d THEN 1 ELSE 0 END), " +
           "AVG(CASE WHEN ti.updatedAt >= :since7d THEN ti.score END), " +
           "SUM(CASE WHEN ti.updatedAt >= :since7d THEN 1 ELSE 0 END) " +
           "FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.score IS NOT NULL AND ti.deleted = false " +
           "GROUP BY ti.tag")
    List<Object[]> aggregateScoresMultiWindow(@Param("venueId") Long venueId,
                                              @Param("since30d") LocalDateTime since30d,
                                              @Param("since7d") LocalDateTime since7d);

    /**
     * 单次往返获取用户对指定场所的全部交互状态（点赞 + 评分）。
     * 返回 Object[]{tag, liked, score}。
     */
    @Query("SELECT ti.tag, ti.liked, ti.score FROM TagInteraction ti " +
           "WHERE ti.userId = :userId AND ti.venueId = :venueId AND ti.deleted = false " +
           "AND (ti.liked = true OR ti.score IS NOT NULL)")
    List<Object[]> findUserInteractionsByVenue(@Param("userId") Long userId, @Param("venueId") Long venueId);
}
