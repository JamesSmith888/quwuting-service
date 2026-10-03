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
 * 查询侧判定**（写宽松读严格，阈值调整无需重采）。谓词文本是口径的唯一实现
 * （{@link #HIT_PREDICATE}），到访 / 附近两种统计、单页 / 全量两种范围共用它，仅参数不同。
 */
public interface VenuePresencePingRepository extends JpaRepository<VenuePresencePing, Long> {

    /**
     * 命中谓词（唯一实现）：distance ≤ :radiusM 且精度达标。
     * <ul>
     *   <li>accuracy_m IS NULL 视为达标：精度缺失（老端/低版本）不能当「证据不足」
     *       拒绝——写侧已限幅（≤500m），NULL 语义 = 「无法判定」而非「超标」；</li>
     *   <li>不含时间窗：窗口由消费方对 {@code MAX(createdAt)} 判定（见下方查询注释）。</li>
     * </ul>
     */
    String HIT_PREDICATE = "p.deleted = false AND p.distanceM <= :radiusM "
            + "AND (p.accuracyM IS NULL OR p.accuracyM <= :maxAccuracyM)";

    /** 聚合形态（两查询共用）：每个 (门店, 用户) 一行 + 该用户在该店最近一次命中时刻 */
    String VISITOR_LAST_SEEN_SELECT = "SELECT p.venueId, p.userId, MAX(p.createdAt) FROM VenuePresencePing p ";

    /**
     * 指定门店集合上的 (venueId, userId, 最近命中时刻)（admin 单页列表 / 详情的数据源）：一次 IN
     * 覆盖整页 + 同址邻居，防 N+1。
     * <p>
     * <b>为什么返回「(店, 人, 最近时刻)」而不是每店 COUNT</b>：
     * <ul>
     *   <li>同址门店共享 / 让渡到访证据（52 号 §4.2 / §4.4）——某店的到访人数 = 归因证据门店上
     *       ping 的<b>用户并集</b>，并集不能由 COUNT 相加得到（同一用户在组内两家都有 ping
     *       会被重复计），只能在用户粒度上合并；</li>
     *   <li>一次查询同时回答 7 天 / 30 天两个窗口与「最近到访」：用户在窗口内到访过 ⟺ 他的
     *       最近命中时刻 ≥ 窗口起点。<b>时间窗去重、非自然日去重</b>——舞厅营业跨零点，
     *       日粒度会把同一次到访拆成两天两次（22:00 进 02:00 出被计 2）。</li>
     * </ul>
     * 数据量 = 入参门店 × 去重访客（百级），内存合并无压力。返回 Object[]{venueId, userId, lastSeenAt}。
     */
    @Query(VISITOR_LAST_SEEN_SELECT + "WHERE p.venueId IN :venueIds AND " + HIT_PREDICATE
            + " GROUP BY p.venueId, p.userId")
    List<Object[]> findVisitorLastSeenByVenueIds(
            @Param("venueIds") Collection<Long> venueIds,
            @Param("radiusM") int radiusM,
            @Param("maxAccuracyM") int maxAccuracyM);

    /**
     * 全量 (venueId, userId, 最近命中时刻)（2026-10-03 admin「按足迹排序 / 只看有足迹」的数据源，
     * 52 号 §6.1）：到访证据是稀疏的（2026-10-03 现网 21 条 ping / 11 家店），排序只需「有证据的门店」，
     * 不必为一千多家门店逐一求值。
     * <p>
     * 量级边界：全表 GROUP BY，命中 {@code (venue_id, created_at)} 索引之外无专用索引；
     * ping 表到 10^6 行级（约 5 年现有增速）前无需优化，届时改为定时物化汇总表——见 52 号 §6.1。
     */
    @Query(VISITOR_LAST_SEEN_SELECT + "WHERE " + HIT_PREDICATE + " GROUP BY p.venueId, p.userId")
    List<Object[]> findVisitorLastSeen(@Param("radiusM") int radiusM,
                                       @Param("maxAccuracyM") int maxAccuracyM);

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
