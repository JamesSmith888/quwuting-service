package org.quwuting.quwutingservice.venuepresence.repository;

import org.quwuting.quwutingservice.venuepresence.entity.VenuePresencePing;
import org.springframework.data.domain.Pageable;
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
     * 窗口内 + 排除内部账号的 (venueId, userId, 最近命中时刻)（2026-10-06 新增，V38）：
     * <b>热度排序口径</b>的唯一取数源，供定时刷新任务
     * （{@code VenueVisitMetricsScheduler}）写入 {@code qwt_venue_visit_metrics}。
     * <p>
     * <b>为什么不能复用 {@link #findVisitorLastSeen}</b>（三条都与 admin 展示口径有意分叉）：
     * <ul>
     *   <li><b>时间窗</b>：admin 需要"从无到访"的真实判断，故全量扫描；排序只需要 30 天窗口内
     *       的命中——本方法把下界推到 SQL 里，(venue_id, created_at) 索引前缀可直接裁掉
     *       绝大多数行（这正是物化表要解决的量级问题）；</li>
     *   <li><b>排除内部账号</b>：到访是低基数信号（2026-10-06 现网 51 条 ping 中 ADMIN 一人
     *       占 26 条 = 51%），不排除等于平台自己人直接刷分。admin 展示**不排除**；</li>
     *   <li><b>返回原始 (店,人,时刻) 而非计数</b>：本方法的结果还要经同址组归因与
     *       <b>1/k 分摊</b>（同一用户在组内两家都有 ping 只算 1 人并分摊），
     *       只能在用户粒度上合并，不能由 COUNT 相加得到。</li>
     * </ul>
     */
    @Query(VISITOR_LAST_SEEN_SELECT + "WHERE " + HIT_PREDICATE
            + " AND p.createdAt >= :since AND p.userId NOT IN :excludedUserIds"
            + " GROUP BY p.venueId, p.userId")
    List<Object[]> findVisitorLastSeenSinceExcluding(@Param("since") LocalDateTime since,
                                                    @Param("radiusM") int radiusM,
                                                    @Param("maxAccuracyM") int maxAccuracyM,
                                                    @Param("excludedUserIds") Collection<Long> excludedUserIds);

    /**
     * 窗口内 + 排除内部账号的<b>去重到访日</b>明细（2026-10-06，V39「到访次数」新增）：
     * {@code (venueId, userId, visitDay)}，每行 = 一位用户在一家店的<b>一个到店日</b>
     * （{@code DISTINCT} 已吃掉当天重复命中）。
     * <p>
     * <b>为什么返回「日明细」而不是 COUNT 聚合值</b>（与人数查询的形态差异是刻意的）：
     * 同址归因要求<b>并集</b>而非相加—— 人数侧是"用户在组内任一家到过即算 1 人"
     * （{@code unionLastSeen}）；次数侧同理必须是"用户在这几家店的<b>去重日并集</b>
     * 的大小"。若在 SQL 里先GROUP BY 聚成每店每天数，Java 侧只能把各店的 count 相加
     * ⇒ 同一用户同一天在同址两家都被命中时会<b>被重复计一次</b>（人数侧不会），
     * 两个数字之间凭空出现口径裂缝。返回日明细才能在内存里做真正的并集
     * （{@code unionVisitDays}），与人数侧同构。
     * <p>
     * <b>口径 = 每 (人, 店) 一组、去重自然日</b>（2026-10-06 用户定案）：
     * <ul>
     *   <li>用户某天到某店 ⇒ 那天记 1 次（<b>一天内去多次只记 1 次</b>，由 DISTINCT 保证）；</li>
     *   <li>连去 3 天 ⇒ 3 次；</li>
     *   <li>去重键含 {@code venue_id} ⇒ 同一天去两家不同门店，两家各记 1 次。</li>
     * </ul>
     * ⚠️ <b>与人数口径刻意不同</b>（52 号 §4 已登记）：人数用 {@code MAX(createdAt)} 时间窗去重，
     * <b>跨零点连场算 1 次</b>（舞厅 22:00 进 02:00 出不会被拆成两天）；本查询按自然日去重，
     * 跨零点连场会算 2 次。二者对"同一次到访"的判断本就不同，故同源计算、分别落列，
     * <b>禁止互相推算</b>。
     * <p>
     * ⚠️ <b>{@code DATE()} 的时区语义</b>：{@code created_at} 由 Java 写入
     * （JVM = 北京时间，见 {@link #upsertInBucket} 红线），MySQL 的 {@code DATE()}
     * 按会话时区切分该 datetime ⇒ RDS 会话时区非北京时间时会把凌晨的到访算到前一天。
     * 生产连接串已固定 {@code serverTimezone=Asia/Shanghai}（14 号部署文档），
     * 实际按北京时间切分。⛔ 改连接串时区前先复核本段。
     * <p>
     * 量级 = Σ(每位用户 × 每家店 × 每个到店日)。到访是稀疏信号
     * （2026-10-06 现网全网 52 条 ping / 9 家店），远小于 ping 表行数；
     * {@code (venue_id, created_at)} 索引前缀可裁掉窗口外行。
     */
    @Query("SELECT DISTINCT p.venueId, p.userId, DATE(p.createdAt) FROM VenuePresencePing p "
            + "WHERE " + HIT_PREDICATE
            + " AND p.createdAt >= :since AND p.userId NOT IN :excludedUserIds")
    List<Object[]> findVisitorDaysSinceExcluding(@Param("since") LocalDateTime since,
                                                 @Param("radiusM") int radiusM,
                                                 @Param("maxAccuracyM") int maxAccuracyM,
                                                 @Param("excludedUserIds") Collection<Long> excludedUserIds);

    /**
     * 指定门店集合上的<b>去重到访日</b>（2026-10-06，admin「到访用户名单」的次数列数据源）：
     * {@code (venueId, userId, visitDay)}，形态与 {@link #findVisitorDaysSinceExcluding} 同构。
     * <p>
     * <b>与 {@link #findVisitorDaysSinceExcluding} 的两处分叉（都是刻意的，勿"顺手统一"）</b>：
     * <ul>
     *   <li><b>不排除内部账号</b>：admin 展示要看到全量（现网 51 条 ping 里 ADMIN 一人占 26 条，
     *       名单页打「内部」标签区分，见 {@code AdminVenueVisitorItem#internalAccount}）；
     *       排序口径才需要排除（{@code findVisitorDaysSinceExcluding}）；</li>
     *   <li><b>按门店集合过滤</b>：名单是单店下钻，{@code (venue_id, created_at)} 索引前缀可直接裁剪，
     *       不必全表 GROUP BY。</li>
     * </ul>
     * 同址共享时消费方须对多个证据门店的 (user, day) 取<b>并集</b>再计数（与人数侧
     * {@code unionLastSeen} 同构，⛔ 不能把各店 count 相加——52 号 §4.2 第 1 条）。
     */
    @Query("SELECT DISTINCT p.venueId, p.userId, DATE(p.createdAt) FROM VenuePresencePing p "
            + "WHERE p.venueId IN :venueIds AND " + HIT_PREDICATE + " AND p.createdAt >= :since")
    List<Object[]> findVisitorDaysByVenueIdsSince(@Param("venueIds") Collection<Long> venueIds,
                                                 @Param("since") LocalDateTime since,
                                                 @Param("radiusM") int radiusM,
                                                 @Param("maxAccuracyM") int maxAccuracyM);

    /**
     * 单用户的<b>逐桶命中明细</b>（2026-10-06，admin「用户到访足迹」列表的数据源）：
     * {@code (venueId, writeBucket, createdAt, updatedAt, distanceM, accuracyM)}，按门店 + 桶升序。
     * <p>
     * <b>为什么返回原始桶而不是聚合行</b>：用户要看的「具体到访记录」是<b>一次次到店</b>
     * （到店时间 / 停留多久 / 采样几次），而这些只能从桶序列还原——
     * {@code MAX(createdAt)} 形态（{@link #findVisitorLastSeenByVenueIds}）一天多次到店会并成一条，
     * 「来过几天」的信息不可恢复（同 V39 的理由）。
     * <p>
     * <b>一次到店 = 连续桶的合并</b>，合并规则（间隔阈值）在
     * {@code VenuePresenceService#VISIT_SESSION_GAP_BUCKETS}，本方法只负责把桶交出去。
     * <p>
     * 量级 = Σ(该用户 × 每店 × 每个命中桶)；{@code (user_id, created_at)} 索引前缀可裁掉窗口外行
     * （与 {@link #findVisitorDaysSinceExcluding} 同索引，登记于 52 号文档）。
     */
    @Query("SELECT p.venueId, p.writeBucket, p.createdAt, p.updatedAt, p.distanceM, p.accuracyM "
            + "FROM VenuePresencePing p "
            + "WHERE p.userId = :userId AND " + HIT_PREDICATE + " AND p.createdAt >= :since "
            + "ORDER BY p.venueId ASC, p.writeBucket ASC")
    List<Object[]> findHitsByUserIdSince(@Param("userId") Long userId,
                                         @Param("since") LocalDateTime since,
                                         @Param("radiusM") int radiusM,
                                         @Param("maxAccuracyM") int maxAccuracyM);

    /**
     * 单用户的<b>坐标轨迹明细</b>（2026-10-08 V46，admin「位置轨迹」卡的数据源）：
     * {@code (id, venueId, distanceM, accuracyM, latitude, longitude, createdAt)}，
     * 按时间<b>倒序</b> + id 倒序（同刻并列确定），由 {@code Pageable} 施加「最近 N 条」上限。
     * <p>
     * <b>为什么倒序取</b>：截断时保留的必须是<b>最近</b>的点（同 {@code findHitsByUserIdSince}
     * 的截断方向纪律——升序截断会把最近的全丢、只剩历史）；消费方反转成升序绘制。
     * <p>
     * <b>与命中查询刻意不同</b>：不过命中谓词——轨迹要的是<b>全部</b>采样点，
     * 含 150m 外的「附近 / 留痕」带（2026-10-08 判例：user 210 的 295m 样本不进到访统计，
     * 但必须能在轨迹里看到）。坐标缺失的历史行也一并取回，由消费方计数展示
     * （⛔ 禁静默丢弃）。
     * <p>
     * 量级 = 该用户窗口内 ping 数（每人每次打开至多 1 条 + 店内 15 分钟补采）；
     * {@code (user_id, created_at)} 索引前缀可裁掉窗口外行。
     */
    @Query("SELECT p.id, p.venueId, p.distanceM, p.accuracyM, p.latitude, p.longitude, p.createdAt "
            + "FROM VenuePresencePing p "
            + "WHERE p.userId = :userId AND p.deleted = false AND p.createdAt >= :since "
            + "ORDER BY p.createdAt DESC, p.id DESC")
    List<Object[]> findTrackByUserIdSince(@Param("userId") Long userId,
                                          @Param("since") LocalDateTime since,
                                          Pageable pageable);

    /**
     * 幂等写入（15 分钟桶）：INSERT 新行 / 桶冲突时仅刷新 updated_at——
     * <b>不改写 distance_m / accuracy_m / latitude / longitude / created_at</b>：
     * 桶内首见时刻与首证（距离、精度、坐标）是「该窗口的原始事实」，后到的重复
     * 采样（onShow 抖动）不覆盖首证。
     * <p>
     * ⚠️ 时间口径（对齐热度上报同款约束）：created_at/updated_at 必须由 Java 传
     * LocalDateTime.now()（JVM 时区 = 北京时间），禁止 DB now()——RDS 会话时区
     * 为 UTC，混用会让时间窗统计错位。
     * <p>
     * ⚠️ 坐标列（V46，2026-10-08）：null = 旧端（协议向后兼容，服务层已校验
     * 「成对或全缺」）。拼接点两侧补空格是硬约束（文本块拼接漏空格事故，10-06）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_presence_pings "
            + "(created_at, updated_at, deleted, user_id, venue_id, write_bucket, distance_m, accuracy_m, latitude, longitude) "
            + "VALUES (:now, :now, false, :userId, :venueId, :writeBucket, :distanceM, :accuracyM, :latitude, :longitude) "
            + "ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at)",
            nativeQuery = true)
    void upsertInBucket(@Param("userId") Long userId,
                        @Param("venueId") Long venueId,
                        @Param("writeBucket") long writeBucket,
                        @Param("distanceM") int distanceM,
                        @Param("accuracyM") Integer accuracyM,
                        @Param("latitude") Double latitude,
                        @Param("longitude") Double longitude,
                        @Param("now") LocalDateTime now);
}
