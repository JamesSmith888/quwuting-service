package org.quwuting.quwutingservice.venuecrowd.repository;

import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReportLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 今晚热度上报行级点赞仓储（2026-09-03，docs/agents/27-venue-crowd-report.md「行级点赞」）。
 * <p>
 * toggle 模型 = 软删 + 全量唯一 (liker_id, report_id)（对齐 qwt_favorites 收藏 toggle 先例，
 * 与「每日一记」的生成列部分唯一索引不同——赞是「同一条上报的关系」，软删行占位、
 * 取消后再赞 = UPDATE 恢复原行）：
 * <ul>
 *   <li>{@link #like}：INSERT ... ON DUPLICATE KEY UPDATE 幂等 toggle ON——受影响行数
 *       <b>1 = 首次赞</b>（唯一触发被赞通知的机会）、2 = 取消后恢复或重复赞（不重发）、
 *       0 = 同秒重复（不重发）；并发双击由唯一键冲突兜底，无需应用层锁；</li>
 *   <li>{@link #unlike}：UPDATE 软删 toggle OFF（幂等，未赞时影响 0 行不报错）。</li>
 * </ul>
 */
public interface VenueCrowdReportLikeRepository extends JpaRepository<VenueCrowdReportLike, Long> {

    /**
     * 赞（幂等 toggle ON）：新赞插入；已赞/取消后再赞 → 恢复原行（deleted=false）。
     * 返回受影响行数：1 = 本次为「首次赞」（服务端据此判定是否履行被赞通知义务——
     * 仅非自赞发信）；2/0 = 已赞态或恢复（通知义务已履行/不适用，不重发）。
     * <p>
     * ⚠️ 时间口径：created_at/updated_at 由 Java 传 JVM LocalDateTime.now()（北京时间），
     * 禁 DB now()——全库约定（同 VenueCrowdReportRepository.upsert 注释，跨库兼容）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_crowd_report_likes " +
            "(created_at, updated_at, deleted, report_id, liker_id) " +
            "VALUES (:createdAt, :updatedAt, false, :reportId, :likerId) " +
            "ON DUPLICATE KEY UPDATE deleted = false, updated_at = :updatedAt",
            nativeQuery = true)
    int like(@Param("reportId") Long reportId, @Param("likerId") Long likerId,
             @Param("createdAt") LocalDateTime createdAt, @Param("updatedAt") LocalDateTime updatedAt);

    /** 取消赞（幂等 toggle OFF）：软删；未赞过影响 0 行（不报错）。返回受影响行数 */
    @Modifying
    @Query("UPDATE VenueCrowdReportLike l SET l.deleted = true, l.updatedAt = :updatedAt " +
            "WHERE l.reportId = :reportId AND l.likerId = :likerId AND l.deleted = false")
    int unlike(@Param("reportId") Long reportId, @Param("likerId") Long likerId,
               @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 批量按上报行聚合赞数（summary/history 行数据源）：一次 IN + GROUP BY report_id
     * 覆盖整页/整窗口，防逐行 COUNT 的 N+1（同 VenueCrowdReportRepository 批量模式）。
     * 返回 Object[]{reportId, count}；未命中行不在结果（调用方默认 0）。
     */
    @Query("SELECT l.reportId, COUNT(l) FROM VenueCrowdReportLike l " +
            "WHERE l.reportId IN :reportIds AND l.deleted = false GROUP BY l.reportId")
    List<Object[]> countByReportIds(@Param("reportIds") Collection<Long> reportIds);

    /**
     * 我赞过的上报行 ID 集（详情页热度卡 likedByMe 回填；likerId 为当前登录用户）。
     * 未登录时调用方直接传空集/跳过本查询。
     */
    List<VenueCrowdReportLike> findByLikerIdAndReportIdInAndDeletedFalse(
            @Param("likerId") Long likerId, @Param("reportIds") Collection<Long> reportIds);

    /** 单条上报赞数（like/unlike 响应回读） */
    long countByReportIdAndDeletedFalse(@Param("reportId") Long reportId);
}
