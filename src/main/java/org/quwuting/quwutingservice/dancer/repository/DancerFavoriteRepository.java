package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DancerFavoriteRepository extends JpaRepository<DancerFavorite, Long> {

    /**
     * 精确命中当前用户的收藏记录（toggle 用；唯一约束 (userId, dancerId) 保证至多一行，
     * 软删行可被重新收藏复用——restore 语义，同门店收藏恢复逻辑）。
     */
    Optional<DancerFavorite> findByUserIdAndDancerId(Long userId, Long dancerId);

    /**
     * 原子收藏 upsert（2026-08-19 根因修复：替代「find 判断 → save + 23505 异常吞掉」的
     * 不可靠并发幂等——Hibernate flush 失败后持久化上下文状态未定义，catch 后继续用同一
     * 事务提交可能抛 UnexpectedRollbackException（HTTP 500）或残留脏上下文）。本写法恒 1
     * 次 DB 往返、零异常，语义完整覆盖原三分支：
     * <ul>
     *   <li>无记录 → INSERT（deleted=false，created_at=now，新收藏趋势点）；</li>
     *   <li>软删行 → DO UPDATE SET deleted=false（restore 复用，created_at 不变——
     *       收藏趋势按 created_at 分组，恢复不新增趋势点，与 V27 决策一致）；</li>
     *   <li>活跃行 → DO UPDATE SET deleted=false（幂等 no-op）。</li>
     * </ul>
     * 冲突目标用列清单推断（qwt_uk_dancer_fav_user_dancer 为唯一索引，非约束）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_dancer_favorites (user_id, dancer_id, created_at, updated_at, deleted) " +
                   "VALUES (:userId, :dancerId, :now, :now, false) " +
                   "ON CONFLICT (user_id, dancer_id) " +
                   "DO UPDATE SET deleted = false, updated_at = EXCLUDED.updated_at",
           nativeQuery = true)
    int upsertFavorite(@Param("userId") Long userId,
                       @Param("dancerId") Long dancerId,
                       @Param("now") LocalDateTime now);

    /**
     * 当前用户收藏的舞伴列表（按收藏时间倒序，2026-08-14 舞伴收藏）。
     * <p>
     * 返回 Object[] 形状与 {@code DancerRepository#findPublicPage} 完全一致：
     * {id, nickname, avatar_url, bio, gender, city, cnt_all, cnt_today, cnt7,
     * verification_status}——服务层复用同一份摘要构建逻辑（buildSummaries），
     * 前端卡片 ViewModel 派生同源（toCardVM）。
     * <p>
     * <b>可见性过滤（V27 迁移决策）</b>：仅返回当前公开舞伴
     * （d.status='NORMAL' AND d.deleted=false）——HIDDEN 是可见性开关，被收藏后
     * 下架的舞伴自动淡出收藏列表（行保留，恢复 NORMAL 后自动重现）；
     * 排序 = 收藏时间倒序（f.created_at DESC），与门店收藏列表口径一致。
     */
    @Query(value = """
            SELECT d.id, d.nickname, d.avatar_url, d.bio, d.gender, d.city,
                   COALESCE(a.cnt_all, 0) AS cnt_all,
                   COALESCE(a.cnt_today, 0) AS cnt_today,
                   COALESCE(a.cnt7, 0) AS cnt7,
                   d.verification_status
            FROM qwt_dancer_favorites f
            JOIN qwt_dancers d ON d.id = f.dancer_id AND d.deleted = false AND d.status = 'NORMAL'
            LEFT JOIN (
                SELECT dancer_id, COUNT(*) AS cnt_all,
                       COUNT(*) FILTER (WHERE created_at >= :sinceToday) AS cnt_today,
                       COUNT(*) FILTER (WHERE created_at >= :since7d) AS cnt7
                FROM qwt_dancer_recognitions WHERE deleted = false
                GROUP BY dancer_id
            ) a ON a.dancer_id = d.id
            WHERE f.user_id = :userId AND f.deleted = false
            ORDER BY f.created_at DESC, d.id DESC
            """, nativeQuery = true)
    List<Object[]> findFavoriteDancersByUserId(@Param("userId") Long userId,
                                               @Param("sinceToday") LocalDateTime sinceToday,
                                               @Param("since7d") LocalDateTime since7d);

    /**
     * 批量统计：指定用户集的收藏位次（2026-08-27 贡献档案/管理端用户列表聚合，
     * docs/agents/23）：软删行不计（restore 语义，见 V27 注释）。
     * 返回 Object[]{userId, count}；无收藏用户不出现在结果（调用方按 0 兜底）。
     */
    @Query("SELECT f.userId, COUNT(f) FROM DancerFavorite f " +
           "WHERE f.userId IN :userIds AND f.deleted = false GROUP BY f.userId")
    List<Object[]> countGroupByUserIds(@Param("userIds") Collection<Long> userIds);
}
