package org.quwuting.quwutingservice.venuereaction.repository;

import org.quwuting.quwutingservice.venuereaction.entity.VenueReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VenueReactionRepository extends JpaRepository<VenueReaction, Long> {

    /**
     * 今日 Reaction 记录的<b>确定性原子写入</b>（2026-08-20 根因修复：替代
     * 「save + catch 23505 + entityManager.clear()」——PG 语句失败后事务中止
     * （25P02），catch 后同事务继续执行（或提交被当作回滚）均依赖 JPA 不可靠行为；
     * 且旧 catch 分支吞掉冲突后本事务整体静默回滚，赢家记录虽在但语义依赖巧合）。
     * <p>
     * 命中 V1 唯一索引 {@code qwt_uk_vr_user_venue_code_date}（同一用户同一场所
     * 同一 code 同一日）时 DO NOTHING 返回 0 行——幂等视为已参与（每日一记模型的
     * 并发竞态收口；每日一票的"一人一日一票"不变量仍由应用层
     * {@link #lockDailyTicket} 咨询锁承载，V22 决策，本 upsert 不改变）。
     *
     * @return 受影响行数：1 = 新记录；0 = 当日该 code 已参与（幂等跳过）
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_reactions " +
                   "(user_id, venue_id, reaction_code, reaction_date, created_at, updated_at, deleted) " +
                   "VALUES (:userId, :venueId, :code, :reactionDate, :now, :now, false) " +
                   "ON CONFLICT (user_id, venue_id, reaction_code, reaction_date) DO NOTHING",
           nativeQuery = true)
    int upsertReaction(@Param("userId") Long userId,
                       @Param("venueId") Long venueId,
                       @Param("code") String code,
                       @Param("reactionDate") LocalDate reactionDate,
                       @Param("now") LocalDateTime now);

    /**
     * 精确命中"当日记录"（toggle 用，每日一记模型的核心查询）。
     * 唯一约束 (userId, venueId, reactionCode, reactionDate) 保证至多一行：
     * 命中 → 取消（物理删除该行）；未命中 → 插入今日新行。
     */
    Optional<VenueReaction> findByUserIdAndVenueIdAndReactionCodeAndReactionDate(
            Long userId, Long venueId, String reactionCode, LocalDate reactionDate);

    /**
     * 当日该用户在该场所的任意 Reaction 记录（每日一票模式用，2026-08-14 新增）。
     * <p>
     * 每日一票（应用层语义）下"当日是否已有票 + 票面 code"是换票决策的输入。
     * 用 {@code findFirstBy...OrderByIdAsc} 而非普通 Optional 查询：V22 迁移已清理
     * 历史"同日同用户同场所多行"（保留 max(id)），但防御性保持不抛
     * NonUniqueResultException——历史残留多行时取 id 最小一行（最早票），
     * 后续换票逻辑按行收敛（删该行 + 插新行，残留行由下次 V22 类清理或
     * 人工处理；不影响一票语义的持续推进）。
     */
    Optional<VenueReaction> findFirstByUserIdAndVenueIdAndReactionDateOrderByIdAsc(
            Long userId, Long venueId, LocalDate reactionDate);

    /**
     * 事务级 Postgres 咨询锁：串行化同 (userId, venueId, reactionDate) 的并发换票
     * （2026-08-14 每日一票模式用）。
     * <p>
     * 每日一票为<b>应用层语义</b>（无 DB 唯一约束兜底，见 V22 migration 注释）——
     * 并发下"查当日票 → 删旧 → 插新"若不串行化，两个请求可能同日插入不同 code，
     * 破坏一人一店一日一票不变量。本锁在事务内获取、事务提交/回滚自动释放
     * （pg_advisory_xact_lock 语义），lockKey 构造见 {@code VenueReactionService}。
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
    void lockDailyTicket(@Param("lockKey") String lockKey);

    /**
     * 当前用户在该场所"今日已参与"的 Reaction 代码集合（个人状态，实时查询不缓存）。
     * 每日一记模型下"已参与"语义 = 今日存在该记录——次日自动恢复可点击状态（新增一行而非恢复旧行）。
     */
    @Query("SELECT r.reactionCode FROM VenueReaction r " +
           "WHERE r.userId = :userId AND r.venueId = :venueId AND r.reactionDate = :date")
    List<String> findTodayCodesByUserAndVenue(@Param("userId") Long userId,
                                              @Param("venueId") Long venueId,
                                              @Param("date") LocalDate date);

    /**
     * 单场所全部 Reaction 的四时间窗口聚合（详情页 /reactions/stats 用，场所级聚合缓存的 loader）。
     * 返回 Object[]{reactionCode, countAll, countToday, count7d, count30d}。
     * <p>
     * "今日/7天/30天"为真实时间窗口（不锚定"截至昨日"）——Reaction 是实时众包信号
     * （类似状态上报的 TTL 语义），与热度模块"近30天"这类需要跨天稳定对比的滚动窗口
     * 统计口径不同，不适用"统计口径：截至昨日"的排他上界约定，见 AGENTS.md 说明。
     * <p>
     * {@code deleted = false} 过滤保留：历史遗留软删行（迁移前模型）不应计入统计；
     * 每日一记模型下取消即物理删除，生效行恒为 deleted=false，过滤无副作用。
     */
    @Query(value = "SELECT r.reaction_code, " +
                   "COUNT(*) AS count_all, " +
                   "SUM(CASE WHEN r.created_at >= :sinceToday THEN 1 ELSE 0 END) AS count_today, " +
                   "SUM(CASE WHEN r.created_at >= :since7d THEN 1 ELSE 0 END) AS count_7d, " +
                   "SUM(CASE WHEN r.created_at >= :since30d THEN 1 ELSE 0 END) AS count_30d " +
                   "FROM qwt_venue_reactions r " +
                   "WHERE r.venue_id = :venueId AND r.deleted = false " +
                   "GROUP BY r.reaction_code",
           nativeQuery = true)
    List<Object[]> aggregateByVenue(@Param("venueId") Long venueId,
                                    @Param("sinceToday") LocalDateTime sinceToday,
                                    @Param("since7d") LocalDateTime since7d,
                                    @Param("since30d") LocalDateTime since30d);

    /**
     * 批量场所的 Reaction 计数（列表页 Top Reaction 徽标用），一次 IN 查询同时返回
     * {@code countAll}（全部历史记录数）、{@code count7d}（近7天）与 {@code count30d}（近30天）。
     * 返回 Object[]{venueId, reactionCode, countAll, count7d, count30d}。
     * <p>
     * 合并为单条 SQL 而非多条（窗口用条件 SUM 内联），保持与
     * TagInteractionService 既有"一次 IN 查询覆盖整页场所"的 N+1 规避约定。
     * 三个窗口计数全量下发——前端乐观更新下各窗口计数本地 ±1 均精确
     * （每日一记模型：取消只作用于当日记录，见 AGENTS.md「Reaction 快速反馈系统」），
     * 列表页窗口切换仅需重排序/过滤，无需为每个窗口重复请求。
     */
    @Query(value = "SELECT r.venue_id, r.reaction_code, " +
                   "COUNT(*) AS count_all, " +
                   "SUM(CASE WHEN r.created_at >= :since7d THEN 1 ELSE 0 END) AS count_7d, " +
                   "SUM(CASE WHEN r.created_at >= :since30d THEN 1 ELSE 0 END) AS count_30d " +
                   "FROM qwt_venue_reactions r " +
                   "WHERE r.venue_id IN :venueIds AND r.deleted = false " +
                   "GROUP BY r.venue_id, r.reaction_code",
           nativeQuery = true)
    List<Object[]> countByVenueIdsGroupByCode(@Param("venueIds") List<Long> venueIds,
                                              @Param("since7d") LocalDateTime since7d,
                                              @Param("since30d") LocalDateTime since30d);

    /**
     * 批量场所内当前用户"今日已参与"的 Reaction 代码（列表页个人状态，实时查询不缓存）。
     * 返回 Object[]{venueId, reactionCode}。仅登录用户调用；未登录场景由 Service 层跳过此查询。
     * <p>
     * 例外说明：列表层通常不查询个人状态（见 AGENTS.md「标签热度」章节），但 Reaction 列表卡片
     * 明确要求"点击即知当前是否已参与"（产品规则，见需求「列表页需求」），故此处专门为 Reaction
     * 打破该惯例——仅此一条 IN 查询，成本远低于逐场所查询。
     */
    @Query("SELECT r.venueId, r.reactionCode FROM VenueReaction r " +
           "WHERE r.userId = :userId AND r.venueId IN :venueIds AND r.reactionDate = :date")
    List<Object[]> findTodayCodesByUserAndVenueIds(@Param("userId") Long userId,
                                                   @Param("venueIds") List<Long> venueIds,
                                                   @Param("date") LocalDate date);
}
