package org.quwuting.quwutingservice.points.repository;

import org.quwuting.quwutingservice.points.entity.PointsTransaction;
import org.quwuting.quwutingservice.points.enums.PointsSourceType;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PointsTransactionRepository extends JpaRepository<PointsTransaction, Long> {

    /** 挣取幂等查询：同一来源是否已发分（唯一索引冲突前的软检查，冲突时仍靠 23505 兜底） */
    Optional<PointsTransaction> findByUserIdAndSourceTypeAndSourceId(Long userId, PointsSourceType sourceType, Long sourceId);

    /**
     * 挣取流水的<b>确定性原子写入</b>（2026-08-20 根因修复：替代「saveAndFlush +
     * catch 23505 + 同事务回查」的不可靠并发幂等——PG 语句失败后事务中止（25P02），
     * catch 内回查必然 HTTP 500；且旧注释"异常整体回滚"在 catch 内不成立，回滚只
     * 发生在异常向外传播时）。
     * <p>
     * 命中挣取幂等部分唯一索引 {@code qwt_uk_pts_tx_earn_dedup}（(user_id,
     * source_type, source_id) WHERE delta &gt; 0 AND source_id IS NOT NULL）时
     * DO NOTHING，返回 0 行——调用方按受影响行数判定：0 = 已发过（幂等，回查该来源
     * 流水的余额快照返回），1 = 真实发放。冲突目标 = 列清单 + 完整索引谓词（部分
     * 唯一索引推断要求，禁止省略）。
     * <p>
     * <b>enum 参数必须传 name() 字符串（2026-08-20 根因修复）</b>：原生 SQL 绑定
     * enum 无 JPA 元数据 → 默认 {@code EnumType.ORDINAL}（{@code EnumJavaType.sqlType}
     * 回退分支）→ {@code source_type} 列落库序号而非枚举名，而实体派生回查
     * {@link #findByUserIdAndSourceTypeAndSourceId}（{@code @Enumerated(STRING)}）
     * 按 name() 匹配——两侧不一致必然「回查 0 条」（与
     * {@code VenueFeedbackRepository.upsertPending*} 同源，详见 15-governance 错误表）。
     * 调用方传 {@code sourceType.name()}。
     *
     * @return 受影响行数：1 = 发放成功；0 = 该来源已发过（幂等跳过）
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_points_transactions " +
                   "(created_at, user_id, delta, balance_after, source_type, source_id, target_type, target_id, remark) " +
                   "VALUES (:now, :userId, :delta, :balanceAfter, :sourceType, :sourceId, NULL, NULL, :remark) " +
                   "ON CONFLICT (user_id, source_type, source_id) " +
                   "WHERE delta > 0 AND source_id IS NOT NULL DO NOTHING",
           nativeQuery = true)
    int upsertEarn(@Param("userId") Long userId,
                   @Param("delta") long delta,
                   @Param("balanceAfter") long balanceAfter,
                   @Param("sourceType") String sourceType,
                   @Param("sourceId") Long sourceId,
                   @Param("remark") String remark,
                   @Param("now") LocalDateTime now);

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

    /**
     * 赠送事务级串行化（2026-08-19 根因修复，与 unlock()/checkIn() 同并发范式）：
     * 「日上限/单目标日上限读检查 → 原子扣减 → 写流水」若并发交错执行，两个请求可同时
     * 通过上限检查并各自扣减——上限（app.points.gift.max-per-day / max-per-target-day）
     * 在并发下失守。按 user 粒度 pg_advisory_xact_lock 串行化整个赠送事务（同一用户
     * 的连续赠送本就应顺序执行，串行正确且无性能损失）。锁在全部校验之前获取，
     * 事务提交/回滚自动释放。
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
    void lockUserGift(@Param("lockKey") String lockKey);

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

    /**
     * 批量统计：指定用户集 × 来源类型的流水条数（2026-08-27 贡献档案/管理端用户
     * 列表聚合，docs/agents/23）：一次 GROUP BY 覆盖一页用户，避免 N+1。
     * 返回 Object[]{userId, count}；无流水用户不出现在结果（调用方按 0 兜底）。
     * 幂等唯一键保证每个来源至多一条流水，条数 = 行为次数。
     */
    @Query("SELECT t.userId, COUNT(t) FROM PointsTransaction t " +
           "WHERE t.userId IN :userIds AND t.sourceType IN :types GROUP BY t.userId")
    List<Object[]> countByUserIdsAndSourceTypes(@Param("userIds") Collection<Long> userIds,
                                                @Param("types") Collection<PointsSourceType> types);
}
