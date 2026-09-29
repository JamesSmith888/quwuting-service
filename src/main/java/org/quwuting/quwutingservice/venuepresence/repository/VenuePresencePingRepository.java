package org.quwuting.quwutingservice.venuepresence.repository;

import org.quwuting.quwutingservice.venuepresence.entity.VenuePresencePing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 门店到访痕迹仓储（2026-09-29，V33；文档 = docs/agents/52-venue-presence.md）。
 * <p>
 * 写侧 = 15 分钟桶幂等 upsert（ON DUPLICATE KEY 不改写业务值，保留桶内首见事实）；
 * 读侧 = 「命中口径」的批量聚合——命中谓词（distance ≤ 半径 且 精度达标）**只在
 * 查询侧判定**（写宽松读严格，阈值调整无需重采），谓词表达式是口径的唯一实现，
 * 两个统计（到访 UV / 附近 UV）共用同一方法、仅参数不同。
 */
public interface VenuePresencePingRepository extends JpaRepository<VenuePresencePing, Long> {

    /**
     * 批量每店「距离 ≤ radiusM 且精度达标」的去重人数（2026-09-29 admin 列表/
     * 详情统计的数据源）：一次 IN + GROUP BY 覆盖整页，防 N+1（对齐
     * VenueCrowdReportRepository#countDistinctUsersByVenueIdsSince 批量模式）。
     * <p>
     * 口径判定说明：
     * <ul>
     *   <li>时间窗 = createdAt ≥ :since（**时间窗去重，非自然日去重**——舞厅营业
     *       跨零点，日粒度会把同一次到访拆成两天两次，22:00 进 02:00 出被计 2）；</li>
     *   <li>accuracy_m IS NULL 视为达标：精度缺失（老端/低版本）不能当「证据不足」
     *       拒绝——写侧已限幅（≤500m），NULL 语义 = 「无法判定」而非「超标」；</li>
     *   <li>JPQL 无 FROM 派生表能力 ⇒ 「按会话切分的到访次数」无法进 SQL
     *       （与热度复访项同约束）——本表只出 UV 口径，不做会话切分统计。</li>
     * </ul>
     * 返回 Object[]{venueId, uv}。
     */
    @Query("SELECT p.venueId, COUNT(DISTINCT p.userId) FROM VenuePresencePing p " +
            "WHERE p.venueId IN :venueIds AND p.createdAt >= :since AND p.deleted = false " +
            "AND p.distanceM <= :radiusM " +
            "AND (p.accuracyM IS NULL OR p.accuracyM <= :maxAccuracyM) " +
            "GROUP BY p.venueId")
    List<Object[]> countDistinctUsersByVenueIdsSince(
            @Param("venueIds") Collection<Long> venueIds,
            @Param("since") LocalDateTime since,
            @Param("radiusM") int radiusM,
            @Param("maxAccuracyM") int maxAccuracyM);

    /** 某店最近一条到访痕迹（admin 详情「最近到访时间」；无记录 = empty） */
    java.util.Optional<VenuePresencePing> findFirstByVenueIdAndDeletedFalseOrderByCreatedAtDesc(
            @Param("venueId") Long venueId);

    /**
     * 幂等写入（15 分钟桶）：INSERT 新行 / 桶冲突时仅刷新 updated_at——
     * <b>不改写 distance_m / accuracy_m / created_at</b>：桶内首见时刻与首证距离
     * 是「该窗口的原始事实」，后到的重复采样（onShow 抖动）不覆盖首证。
     * <p>
     * ⚠️ 时间口径（对齐热度上报同款约束）：created_at/updated_at 必须由 Java 传
     * LocalDateTime.now()（JVM 时区 = 北京时间），禁止 DB now()——RDS 会话时区
     * 为 UTC，混用会让时间窗统计错位。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_presence_pings " +
            "(created_at, updated_at, deleted, user_id, venue_id, write_bucket, distance_m, accuracy_m) " +
            "VALUES (:now, :now, false, :userId, :venueId, :writeBucket, :distanceM, :accuracyM) " +
            "ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at)",
            nativeQuery = true)
    void upsertInBucket(@Param("userId") Long userId,
                        @Param("venueId") Long venueId,
                        @Param("writeBucket") long writeBucket,
                        @Param("distanceM") int distanceM,
                        @Param("accuracyM") Integer accuracyM,
                        @Param("now") LocalDateTime now);
}
