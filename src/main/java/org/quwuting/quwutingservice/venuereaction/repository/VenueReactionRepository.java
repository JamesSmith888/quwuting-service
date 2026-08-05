package org.quwuting.quwutingservice.venuereaction.repository;

import org.quwuting.quwutingservice.venuereaction.entity.VenueReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VenueReactionRepository extends JpaRepository<VenueReaction, Long> {

    /**
     * 精确命中"当日记录"（toggle 用，每日一记模型的核心查询）。
     * 唯一约束 (userId, venueId, reactionCode, reactionDate) 保证至多一行：
     * 命中 → 取消（物理删除该行）；未命中 → 插入今日新行。
     */
    Optional<VenueReaction> findByUserIdAndVenueIdAndReactionCodeAndReactionDate(
            Long userId, Long venueId, String reactionCode, LocalDate reactionDate);

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
