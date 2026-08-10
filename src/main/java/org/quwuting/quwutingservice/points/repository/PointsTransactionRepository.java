package org.quwuting.quwutingservice.points.repository;

import org.quwuting.quwutingservice.points.entity.PointsTransaction;
import org.quwuting.quwutingservice.points.enums.PointsSourceType;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PointsTransactionRepository extends JpaRepository<PointsTransaction, Long> {

    /** 挣取幂等查询：同一来源是否已发分（唯一索引冲突前的软检查，冲突时仍靠 23505 兜底） */
    Optional<PointsTransaction> findByUserIdAndSourceTypeAndSourceId(Long userId, PointsSourceType sourceType, Long sourceId);

    /**
     * 用户流水分页（最近在前）。delta &gt; 0 = 挣取，delta &lt; 0 = 赠送；
     * type 过滤（ALL 恒真 / EARN / GIFT）。
     */
    @Query("""
            SELECT pt FROM PointsTransaction pt
            WHERE pt.userId = :userId
              AND (:type = 'ALL' OR (:type = 'EARN' AND pt.delta > 0) OR (:type = 'GIFT' AND pt.delta < 0))
            ORDER BY pt.createdAt DESC, pt.id DESC
            """)
    Page<PointsTransaction> findPageByUserAndType(@Param("userId") Long userId, @Param("type") String type, Pageable pageable);

    /** 今日赠送总量（赠送日上限校验） */
    @Query("""
            SELECT COALESCE(SUM(-pt.delta), 0) FROM PointsTransaction pt
            WHERE pt.userId = :userId AND pt.delta < 0
              AND pt.createdAt >= :since AND pt.createdAt < :until
            """)
    long sumGiftedToday(@Param("userId") Long userId,
                        @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    /** 今日挣取总量（概览页"今日已挣"，正 delta 口径） */
    @Query("""
            SELECT COALESCE(SUM(pt.delta), 0) FROM PointsTransaction pt
            WHERE pt.userId = :userId AND pt.delta > 0
              AND pt.createdAt >= :since AND pt.createdAt < :until
            """)
    long sumEarnedToday(@Param("userId") Long userId,
                        @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    /** 今日对某目标的赠送总量（单目标日上限校验） */
    @Query("""
            SELECT COALESCE(SUM(-pt.delta), 0) FROM PointsTransaction pt
            WHERE pt.userId = :userId AND pt.delta < 0
              AND pt.targetType = :targetType AND pt.targetId = :targetId
              AND pt.createdAt >= :since AND pt.createdAt < :until
            """)
    long sumGiftedToTargetToday(@Param("userId") Long userId,
                                @Param("targetType") PointsTargetType targetType, @Param("targetId") Long targetId,
                                @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    /** 目标收到积分（全量/窗口——热度公式积分项 / 详情展示 / 趋势序列同源口径） */
    @Query("""
            SELECT COALESCE(SUM(-pt.delta), 0) FROM PointsTransaction pt
            WHERE pt.targetType = :targetType AND pt.targetId = :targetId AND pt.delta < 0
            """)
    long sumReceivedTotal(@Param("targetType") PointsTargetType targetType, @Param("targetId") Long targetId);

    @Query("""
            SELECT COALESCE(SUM(-pt.delta), 0) FROM PointsTransaction pt
            WHERE pt.targetType = :targetType AND pt.targetId = :targetId AND pt.delta < 0
              AND pt.createdAt >= :since AND pt.createdAt < :until
            """)
    long sumReceivedSince(@Param("targetType") PointsTargetType targetType, @Param("targetId") Long targetId,
                          @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    /** 目标收到积分的按日趋势（近30天，供 countDailyTrends mega-query 之外的独立查询备用） */
    @Query("""
            SELECT CAST(pt.createdAt AS date) AS day, SUM(-pt.delta) AS points
            FROM PointsTransaction pt
            WHERE pt.targetType = :targetType AND pt.targetId = :targetId AND pt.delta < 0
              AND pt.createdAt >= :since AND pt.createdAt < :until
            GROUP BY CAST(pt.createdAt AS date)
            """)
    List<Object[]> sumReceivedByDay(@Param("targetType") PointsTargetType targetType, @Param("targetId") Long targetId,
                                    @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
}
