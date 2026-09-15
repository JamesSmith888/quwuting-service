package org.quwuting.quwutingservice.user.repository;

import jakarta.persistence.LockModeType;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByOpenIdAndDeletedFalse(String openId);

    /** Web 管理后台密码登录用：取平台管理员账号（2026-08-31） */
    Optional<User> findFirstByRoleAndDeletedFalse(UserRole role);

    Optional<User> findByIdAndDeletedFalse(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id AND u.deleted = false")
    Optional<User> findByIdAndDeletedFalseForUpdate(@Param("id") Long id);

    /**
     * 批量查昵称（消除 N+1，2026-08-28 意见反馈管理端列表使用；与
     * VenueRepository.findByIdInAndDeletedFalse 同模式）。
     */
    List<User> findByIdInAndDeletedFalse(Collection<Long> ids);

    /**
     * 批量取「用户 ID → 昵称」映射（管理端列表批量回填昵称统一入口，消除 N+1）。
     * <p>
     * <b>2026-09-12 根因修复</b>：调用方原先各自写
     * {@code ids.isEmpty() ? Map.of() : ...Collectors.toMap(User::getId, User::getNickname, (a,b) -> a)}，
     * 内含两个确定性 NPE 陷阱，且都在「列表有数据」时才触发：
     * <ol>
     *   <li>{@code Map.of()} 是不可变集合，<b>查询 null key 直接抛 NPE</b>
     *       （{@code ImmutableCollections.MapN.get} 对空表走 {@code Objects.requireNonNull}）。
     *       上报列表里存在匿名记录（{@code user_id} 为 null）时，只要当前页<b>整页皆匿名</b>，
     *       映射即为 {@code Map.of()}，回填 {@code map.get(null)} 必然 500——
     *       现象是管理端上报列表「只要有（匿名）数据就打不开」，且整页有无实名数据决定
     *       是否复现（故表现为时好时坏、疑似玄学）。</li>
     *   <li>{@code Collectors.toMap} 底层走 {@code HashMap.merge}，<b>拒绝 null value</b>：
     *       昵称字段可空（用户未授权昵称 {@code nickname} 为 null），只要列表里出现一个
     *       未设昵称的上报者，同样整接口 500。</li>
     * </ol>
     * 本方法统一用 {@link HashMap} 承接（允许 null key 查询，安全返回 null），
     * 并跳过空昵称——调用方对缺失值自行兜底展示文案（「匿名」/「舞友」）。
     */
    default Map<Long, String> findNicknameMapByIds(Collection<Long> ids) {
        Map<Long, String> nicknameMap = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return nicknameMap;
        }
        findByIdInAndDeletedFalse(ids).forEach(user -> {
            String nickname = user.getNickname();
            if (nickname != null && !nickname.isBlank()) {
                nicknameMap.put(user.getId(), nickname);
            }
        });
        return nicknameMap;
    }

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
     * 管理端用户列表（最近露面降序，2026-08-27 用户管理增强）：原生 SQL——
     * <b>「最近露面」= 用户资料更新（updated_at）/ 积分流水 / 邀约 / <u>打卡</u>
     * 四源 MAX(created_at) 的 GREATEST</b>，与 {@link AdminUserStatsService}
     * 的 {@code lastSeenFor} 定义同源（单一口径，前端展示与排序一致）。
     * <p>
     * <b>命名契约（2026-09-15）</b>：「露面」≠「活跃」。本口径含<b>登录自动打卡</b>
     * （{@code app.ts onLaunch} → {@code autoCheckIn}），语义 = 「这个账号最后一次
     * 出现」，用于运营找「最近来过的号」；它<b>刻意不</b>作为活跃/留存指标——
     * 管理端一切「活跃」一律指 {@link UserStatsSql#ACTIVE_FACT_UNION}（用户主动行为）
     * 口径，字面与语义都不得混用（这正是 2026-09-15 修复的那类错误决策，见
     * docs/agents/35）。
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

    /**
     * <b>平台真实用户数</b>（2026-09-15）：{@link UserStatsSql#USER_SCOPE} 口径——
     * 未软删、{@code role='USER'}、非 {@code test_} 开发号、非微信审核账号。
     * <p>
     * 这是管理端一切「用户盘子」类分母的<b>唯一</b>来源（数据看板「累计注册」、
     * 账本渗透率、留存分析分母、公告触达率分母），与大盘按日趋势的注册序列、
     * 与「近 7 日活跃」同分母——2026-09-15 前此处为「全部未软删非审核账号」
     * （含 ADMIN 运营号与 {@code test_} 开发号），与趋势线口径不一致：图上 30 天
     * 注册之和 ≠ 顶卡累计注册，用户对不上账。
     */
    @Query(value = "SELECT COUNT(*) FROM qwt_users u WHERE " + UserStatsSql.USER_SCOPE, nativeQuery = true)
    long countRealUsers();

    /** 指定角色用户数（未软删且非微信审核；管理端统计概览——管理员数，与真实用户口径正交） */
    long countByDeletedFalseAndWechatReviewFalseAndRole(UserRole role);

    /**
     * 指定时间后注册的<b>真实用户</b>数（2026-09-15）：
     * {@link UserStatsSql#USER_SCOPE} 口径 + {@code created_at >= :since}
     * ——数据看板「今日新增」，与大盘注册序列同口径。
     */
    @Query(value = "SELECT COUNT(*) FROM qwt_users u WHERE " + UserStatsSql.USER_SCOPE
            + " AND u.created_at >= :since", nativeQuery = true)
    long countRealUsersCreatedSince(@Param("since") LocalDateTime since);

    /**
     * <b>近 N 日活跃用户数</b>（有效活跃口径；2026-09-15 重写，仅 ADMIN 消费）。
     * <p>
     * 口径 = 当日出现在 {@link UserStatsSql#ACTIVE_FACT_UNION}（12 表用户主动行为）
     * 的去重用户数，与大盘「真实互动」序列、留存分析同一事实源。
     * <p>
     * <b>2026-09-15 根因修复（勿回退）</b>：旧实现为「四源 MAX ≥ 阈值」——
     * 四源含 {@code qwt_daily_checkins}（登录<b>自动</b>打卡）与 {@code u.updated_at}，
     * 于是数据看板顶卡「近 7 日活跃」实际统计的是「近 7 日打开过的号」，把审核/巡检/
     * 打卡型噪音全算作活跃，与同屏「真实互动」曲线自相矛盾（同一名词两套定义）。
     * 现口径只认用户主动行为；「最后露面」另有其口径（{@link #findPageByFiltersOrderByLastActive}），
     * 二者不得互相替代。
     *
     * @param sinceDay 窗口起始（含，Service 层现算）
     */
    @Query(value = """
            SELECT COUNT(DISTINCT f.user_id)
            FROM (""" + " " + UserStatsSql.ACTIVE_FACT_UNION + " " + """
            ) f
            JOIN qwt_users u ON u.id = f.user_id
            WHERE """ + " " + UserStatsSql.USER_SCOPE, nativeQuery = true)
    long countActiveUsers(@Param("sinceDay") LocalDate sinceDay);
}
