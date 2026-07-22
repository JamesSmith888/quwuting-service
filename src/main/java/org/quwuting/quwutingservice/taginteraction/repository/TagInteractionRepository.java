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

    /** 统计场所各维度的评分均值和人数（score 非空且未删除），返回 Object[]{tag, avgScore, count} */
    @Query("SELECT ti.tag, AVG(ti.score), COUNT(ti) FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.score IS NOT NULL AND ti.deleted = false " +
           "GROUP BY ti.tag")
    List<Object[]> aggregateScoresByVenueGroupByTag(@Param("venueId") Long venueId);

    /** 时间窗口内各维度评分聚合（用于近一月/近一周），返回 Object[]{tag, avgScore, count} */
    @Query("SELECT ti.tag, AVG(ti.score), COUNT(ti) FROM TagInteraction ti " +
           "WHERE ti.venueId = :venueId AND ti.score IS NOT NULL AND ti.deleted = false " +
           "AND ti.updatedAt >= :since " +
           "GROUP BY ti.tag")
    List<Object[]> aggregateScoresByVenueSinceGroupByTag(@Param("venueId") Long venueId,
                                                         @Param("since") LocalDateTime since);

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
}
