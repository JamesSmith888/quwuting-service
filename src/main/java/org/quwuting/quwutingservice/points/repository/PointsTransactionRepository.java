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

    /**
     * 目标收到礼物聚合（"收获的支持"礼物墙，2026-08-12 礼物化）。
     * 口径：delta &lt; 0（赠送）且 gift_code 非空（V13 之后的新流水；存量 V2
     * 积分赠送无载体，天然排除）；按 code → 件数聚合，count 降序、同数按 code
     * 声明序（GiftCatalog 枚举序，后端排序稳定、前端零逻辑）。
     */
    @Query("""
            SELECT pt.giftCode AS giftCode, COUNT(pt) AS cnt
            FROM PointsTransaction pt
            WHERE pt.targetType = :targetType AND pt.targetId = :targetId
              AND pt.delta < 0 AND pt.giftCode IS NOT NULL
            GROUP BY pt.giftCode
            ORDER BY COUNT(pt) DESC, pt.giftCode ASC
            """)
    List<Object[]> sumGiftsReceived(@Param("targetType") PointsTargetType targetType, @Param("targetId") Long targetId);

    /**
     * 某礼物的赠送者列表（礼物墙点击弹层/详情页，2026-08-12）。
     * 口径：delta &lt; 0（赠送）且 gift_code = 指定礼物；按 user 聚合件数 + 最近赠送
     * 时间，再 JOIN 用户表取公开资料（昵称/头像），软删用户排除；
     * 件数降序、最近赠送降序、用户 id 升序兜底（后端稳定排序，前端零逻辑）。
     * <p>
     * 性能（2026-08-12 V17 优化）：<b>先按 user_id 聚合再 JOIN 用户</b>——
     * 热路径（索引扫描 + GROUP BY user_id，走 qwt_idx_pts_tx_target_gift_code
     * 部分索引精确匹配 gift_code）不触碰用户表；用户 JOIN 只发生在聚合后的小结果集
     * 上（而非每条流水先 JOIN 用户再聚合）。返回 Object[]：
     * {user_id, nickname, avatar_url, count, last_gifted_at}。
     */
    @Query("""
            SELECT u.id, u.nickname, u.avatarUrl, g.cnt, g.lastGiftedAt
            FROM (
                SELECT pt.userId AS userId, COUNT(pt) AS cnt, MAX(pt.createdAt) AS lastGiftedAt
                FROM PointsTransaction pt
                WHERE pt.targetType = :targetType AND pt.targetId = :targetId
                  AND pt.delta < 0 AND pt.giftCode = :giftCode
                GROUP BY pt.userId
            ) g
            JOIN User u ON u.id = g.userId AND u.deleted = false
            ORDER BY g.cnt DESC, g.lastGiftedAt DESC, u.id ASC
            """)
    List<Object[]> findGifters(@Param("targetType") PointsTargetType targetType,
                               @Param("targetId") Long targetId,
                               @Param("giftCode") String giftCode);
}
