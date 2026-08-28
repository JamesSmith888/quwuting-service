package org.quwuting.quwutingservice.user.repository;

import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByOpenIdAndDeletedFalse(String openId);

    Optional<User> findByIdAndDeletedFalse(Long id);

    /**
     * 批量查昵称（消除 N+1，2026-08-28 意见反馈管理端列表使用；与
     * VenueRepository.findByIdInAndDeletedFalse 同模式）。
     */
    List<User> findByIdInAndDeletedFalse(Collection<Long> ids);

    /**
     * 管理端用户分页列表（2026-08-27 用户管理增强，docs/agents/23；仅 ADMIN）：
     * keyword（昵称模糊，忽略大小写）/ role（角色筛选）/ city（城市精确匹配）
     * 三重过滤，<b>全部可空</b>（null/空串 = 该维度不限制）；默认 id 倒序
     * （最新加入在前）。无昵称用户（nickname null）在关键词过滤时自然不匹配。
     */
    @Query("SELECT u FROM User u WHERE (:keyword IS NULL OR :keyword = '' " +
            "OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:role IS NULL OR u.role = :role) " +
            "AND (:city IS NULL OR u.city = :city) " +
            "ORDER BY u.id DESC")
    Page<User> findPageByFilters(@Param("keyword") String keyword,
                                 @Param("role") UserRole role,
                                 @Param("city") String city,
                                 Pageable pageable);

    /**
     * 管理端用户列表（积分余额降序，2026-08-27 用户管理增强）：LEFT JOIN
     * qwt_points_accounts 按 COALESCE(balance, 0) 排序——无账户用户（从未参与
     * 积分活动）排最后；余额相同时 id 倒序（稳定次序）。过滤条件与
     * {@link #findPageByFilters} 同口径。
     */
    @Query("SELECT u FROM User u LEFT JOIN PointsAccount a ON a.userId = u.id " +
            "WHERE (:keyword IS NULL OR :keyword = '' " +
            "OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:role IS NULL OR u.role = :role) " +
            "AND (:city IS NULL OR u.city = :city) " +
            "ORDER BY COALESCE(a.balance, 0) DESC, u.id DESC")
    Page<User> findPageByFiltersOrderByPoints(@Param("keyword") String keyword,
                                              @Param("role") UserRole role,
                                              @Param("city") String city,
                                              Pageable pageable);

    /**
     * 管理端用户列表（最近活跃降序，2026-08-27 用户管理增强）：原生 SQL——"最近
     * 活跃" = 用户资料更新（updated_at）/ 积分流水（qwt_points_transactions）/
     * 邀约（qwt_demand_records）/ 打卡（qwt_daily_checkins）四源 MAX(created_at)
     * 的 GREATEST，与 {@link AdminUserStatsService} 的 lastActive 定义同源
     * （单一口径，前端展示与排序一致）。过滤条件与 findPageByFilters 同口径。
     * <p>
     * <b>role 必须传 name() 字符串（2026-08-20 根因修复先例）</b>：原生 SQL 绑定
     * enum 无 JPA 元数据 → 默认 ORDINAL，与 varchar 列比较必然错配——调用方
     * 传 {@code role == null ? null : role.name()}。
     */
    @Query(value = """
            SELECT u.* FROM qwt_users u
            WHERE u.deleted = false
              AND (:keyword IS NULL OR :keyword = '' OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:role IS NULL OR u.role = :role)
              AND (:city IS NULL OR u.city = :city)
            ORDER BY GREATEST(
                COALESCE(u.updated_at, u.created_at),
                COALESCE((SELECT MAX(t.created_at) FROM qwt_points_transactions t WHERE t.user_id = u.id), u.created_at),
                COALESCE((SELECT MAX(d.created_at) FROM qwt_demand_records d WHERE d.user_id = u.id), u.created_at),
                COALESCE((SELECT MAX(c.created_at) FROM qwt_daily_checkins c WHERE c.user_id = u.id), u.created_at)
            ) DESC, u.id DESC
            """,
            nativeQuery = true,
            countQuery = """
                    SELECT COUNT(*) FROM qwt_users u
                    WHERE u.deleted = false
                      AND (:keyword IS NULL OR :keyword = '' OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')))
                      AND (:role IS NULL OR u.role = :role)
                      AND (:city IS NULL OR u.city = :city)
                    """)
    Page<User> findPageByFiltersOrderByLastActive(@Param("keyword") String keyword,
                                                  @Param("role") String role,
                                                  @Param("city") String city,
                                                  Pageable pageable);

    /** 用户总数（未软删；管理端统计概览） */
    long countByDeletedFalse();

    /** 指定角色用户数（未软删；管理端统计概览——管理员数） */
    long countByDeletedFalseAndRole(UserRole role);

    /** 指定时间后注册的用户数（未软删；管理端统计概览——今日新增） */
    long countByDeletedFalseAndCreatedAtGreaterThanEqual(LocalDateTime since);

    /**
     * 近 N 日活跃用户数（2026-08-27 用户管理增强）：与
     * {@link #findPageByFiltersOrderByLastActive} 同源的四源 MAX >= 阈值。
     */
    @Query(value = """
            SELECT COUNT(*) FROM qwt_users u
            WHERE u.deleted = false
              AND GREATEST(
                COALESCE(u.updated_at, u.created_at),
                COALESCE((SELECT MAX(t.created_at) FROM qwt_points_transactions t WHERE t.user_id = u.id), u.created_at),
                COALESCE((SELECT MAX(d.created_at) FROM qwt_demand_records d WHERE d.user_id = u.id), u.created_at),
                COALESCE((SELECT MAX(c.created_at) FROM qwt_daily_checkins c WHERE c.user_id = u.id), u.created_at)
              ) >= :since
            """,
            nativeQuery = true)
    long countActiveSince(@Param("since") LocalDateTime since);
}
