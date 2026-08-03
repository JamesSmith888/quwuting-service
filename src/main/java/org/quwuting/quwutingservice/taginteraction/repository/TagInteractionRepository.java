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

    // ─── 合并查询（减少远程 DB 往返，性能优化） ─────────────────────────────

    /**
     * 点赞数 + 三窗口评分聚合的单次往返合并查询（TagAggregateStatsService 专用）。
     * 返回 Object[]{tag, likeCount, avgAll, countAll, avg30d, count30d, avg7d, count7d}。
     * <p>
     * 根因：点赞计数与评分聚合本是同一张表上按 tag 分组的两个视角，早期拆成两条查询
     * （liked=true 一条、score IS NOT NULL 一条），各占一次跨洲 DB 往返。
     * 合并为一条：WHERE 取两类交互行的并集，SELECT 内用条件聚合分别统计——
     * likeCount 只数 liked=true 的行，score 系列只统计 score 非空的行，互不干扰。
     * <p>
     * 使用原生 SQL：COUNT/SUM/AVG(CASE WHEN...) 条件聚合同时覆盖点赞与评分两个视角，
     * 与 VenueRepository.countHeatCounters 的标量子查询 mega-query 同属"压缩 DB 往返"手段。
     */
    @Query(value = "SELECT ti.tag, " +
                   "SUM(CASE WHEN ti.liked = true THEN 1 ELSE 0 END) AS like_count, " +
                   "AVG(CASE WHEN ti.score IS NOT NULL THEN ti.score END) AS avg_all, " +
                   "COUNT(CASE WHEN ti.score IS NOT NULL THEN 1 END) AS count_all, " +
                   "AVG(CASE WHEN ti.score IS NOT NULL AND ti.updated_at >= :since30d THEN ti.score END) AS avg_30d, " +
                   "SUM(CASE WHEN ti.score IS NOT NULL AND ti.updated_at >= :since30d THEN 1 ELSE 0 END) AS count_30d, " +
                   "AVG(CASE WHEN ti.score IS NOT NULL AND ti.updated_at >= :since7d THEN ti.score END) AS avg_7d, " +
                   "SUM(CASE WHEN ti.score IS NOT NULL AND ti.updated_at >= :since7d THEN 1 ELSE 0 END) AS count_7d " +
                   "FROM qwt_tag_interactions ti " +
                   "WHERE ti.venue_id = :venueId AND ti.deleted = false " +
                   "AND (ti.liked = true OR ti.score IS NOT NULL) " +
                   "GROUP BY ti.tag",
           nativeQuery = true)
    List<Object[]> aggregateLikesAndScoresByTag(@Param("venueId") Long venueId,
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
