package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DancerFavoriteRepository extends JpaRepository<DancerFavorite, Long> {

    /**
     * 精确命中当前用户的收藏记录（toggle 用；唯一约束 (userId, dancerId) 保证至多一行，
     * 软删行可被重新收藏复用——restore 语义，同门店收藏恢复逻辑）。
     */
    Optional<DancerFavorite> findByUserIdAndDancerId(Long userId, Long dancerId);

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
}
