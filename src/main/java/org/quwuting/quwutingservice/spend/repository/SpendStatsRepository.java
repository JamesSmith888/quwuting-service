package org.quwuting.quwutingservice.spend.repository;

import org.quwuting.quwutingservice.spend.entity.SpendEntryEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端「计时器 & 计时账本使用情况」统计仓库（2026-09-14，docs/agents/35-dashboard-stats.md；
 * 仅 ADMIN 消费）。
 * <p>
 * 独立于 {@code SpendEntryRepository}（后者全部 user-scoped，userId 恒在 WHERE
 * 首位；本仓库是跨用户聚合的只读管理面）——单职责独立仓库，参考大盘
 * {@code UserDailyStatsRepository} 先例。继承空标记 {@link Repository} 而非
 * {@code JpaRepository}，不生成标准 CRUD。
 * <p>
 * <b>口径（与大盘 35 号完全同族，禁止散落再定义）</b>：全部聚合
 * {@code JOIN qwt_users} 过滤——{@code deleted=false AND role='USER'
 * AND open_id NOT LIKE 'test\_%' AND wechat_review=false}，即剔除
 * ADMIN 运营号、test_ 开发联调号、微信审核账号（V17 标记体系）。
 * 软删账目（deleted=1）不入任何计数。
 * <p>
 * <b>MySQL 8 方言</b>（WITH RECURSIVE 骨架补零；生产 RDS MySQL，
 * <b>勿在 PG 环境执行</b>，同 {@code UserDailyStatsRepository}）。
 */
public interface SpendStatsRepository extends Repository<SpendEntryEntity, Long> {

    /** 汇总行投影（getter 名与 SQL alias逐字匹配，同仓惯例） */
    interface SummaryRow {
        Long getTotalUsers();

        Long getTimerUsers();

        Long getActive7d();

        Long getTotalEntries();

        Long getDanceEntries();

        Long getManualEntries();

        BigDecimal getExpenseTotal();

        BigDecimal getIncomeTotal();
    }

    /** 按日点投影 */
    interface DailyRow {
        LocalDate getDay();

        Long getDanceEntries();

        Long getManualEntries();

        Long getActiveUsers();
    }

    /** 支出分类切片投影 */
    interface CategoryRow {
        String getCategory();

        Long getEntryCount();

        BigDecimal getTotal();
    }

    /** 门店 TOP 投影 */
    interface VenueRow {
        Long getVenueId();

        String getVenueName();

        Long getEntryCount();

        BigDecimal getTotal();
    }

    /** 记账用户聚合行投影 */
    interface UsageUserRow {
        Long getUserId();

        String getNickname();

        String getAvatarUrl();

        Long getEntryCount();

        Long getDanceEntries();

        Long getManualEntries();

        BigDecimal getExpenseTotal();

        BigDecimal getIncomeTotal();

        LocalDateTime getLastEntryAt();
    }

    /** 单用户账目汇总投影 */
    interface UserSummaryRow {
        Long getEntryCount();

        Long getDanceEntries();

        Long getManualEntries();

        BigDecimal getExpenseTotal();

        BigDecimal getIncomeTotal();
    }

    /** 单用户账目流水行投影 */
    interface UserEntryRow {
        Long getId();

        LocalDateTime getTs();

        BigDecimal getAmount();

        String getDirection();

        String getSource();

        String getCategory();

        Long getVenueId();

        String getVenueName();

        Integer getDurationSeconds();
    }

    /**
     * 累计汇总（全量历史，不受 days 窗口限制）：记账用户 / 计时用户（有 ≥1 条
     * 计时结算账目）/ 近 7 日活跃记账用户 / 条目数（计时结算 vs 手动补记）/
     * 收支金额（方向口径，金额恒正由方向决定归属）。
     *
     * @param activeSince 近 7 日活跃下界（today-6，Service 层现算）
     */
    @Query(value = """
            SELECT COUNT(DISTINCT e.user_id) AS totalUsers,
                   COUNT(DISTINCT CASE WHEN e.source = 'DANCE' THEN e.user_id END) AS timerUsers,
                   COUNT(DISTINCT CASE WHEN e.ts >= :activeSince THEN e.user_id END) AS active7d,
                   COUNT(*) AS totalEntries,
                   COALESCE(SUM(e.source = 'DANCE'), 0) AS danceEntries,
                   COALESCE(SUM(e.source = 'MANUAL'), 0) AS manualEntries,
                   COALESCE(SUM(CASE WHEN e.direction = 'EXPENSE' THEN e.amount ELSE 0 END), 0) AS expenseTotal,
                   COALESCE(SUM(CASE WHEN e.direction = 'INCOME' THEN e.amount ELSE 0 END), 0) AS incomeTotal
            FROM qwt_spend_entries e
            JOIN qwt_users u ON u.id = e.user_id
            WHERE e.deleted = 0
              AND u.deleted = false AND u.role = 'USER'
              AND u.open_id NOT LIKE 'test\\_%'
              AND u.wechat_review = false
            """, nativeQuery = true)
    SummaryRow sumSummary(@Param("activeSince") LocalDate activeSince);

    /**
     * 近 N 天（含今日）按日序列：计时结算条目（source=DANCE，计时器结算自动
     * 入账）/ 手动补记条目（source=MANUAL）/ 当日活跃记账用户（去重）。
     * WITH RECURSIVE 骨架补零，无数据日天然为 0（同大盘三线趋势骨架）。
     */
    @Query(value = """
            WITH RECURSIVE date_series AS (
                SELECT CAST(:sinceDay AS DATE) AS day
                UNION ALL
                SELECT day + INTERVAL 1 DAY FROM date_series WHERE day < CURDATE()
            )
            SELECT d.day AS day,
                   COALESCE(s.danceEntries, 0) AS danceEntries,
                   COALESCE(s.manualEntries, 0) AS manualEntries,
                   COALESCE(s.activeUsers, 0) AS activeUsers
            FROM date_series d
            LEFT JOIN (SELECT DATE(e.ts) AS day,
                              SUM(e.source = 'DANCE') AS danceEntries,
                              SUM(e.source = 'MANUAL') AS manualEntries,
                              COUNT(DISTINCT e.user_id) AS activeUsers
                       FROM qwt_spend_entries e
                       JOIN qwt_users u ON u.id = e.user_id
                       WHERE e.deleted = 0
                         AND e.ts >= CAST(:sinceDay AS DATETIME)
                         AND u.deleted = false AND u.role = 'USER'
                         AND u.open_id NOT LIKE 'test\\_%'
                         AND u.wechat_review = false
                       GROUP BY DATE(e.ts)) s ON s.day = d.day
            ORDER BY d.day
            """, nativeQuery = true)
    List<DailyRow> countDaily(@Param("sinceDay") LocalDate sinceDay);

    /**
     * 支出分类分布（direction='EXPENSE'——与小程序统计页「消费分析」口径一致，
     * GUEST 收入向分类不入图；收入在汇总行体现）。只返回有数据行，前端按固定
     * 7 类补零渲染（分类集 = 服务端 SpendCategory 枚举权威）。
     */
    @Query(value = """
            SELECT e.category AS category,
                   COUNT(*) AS entryCount,
                   COALESCE(SUM(e.amount), 0) AS total
            FROM qwt_spend_entries e
            JOIN qwt_users u ON u.id = e.user_id
            WHERE e.deleted = 0 AND e.direction = 'EXPENSE'
              AND u.deleted = false AND u.role = 'USER'
              AND u.open_id NOT LIKE 'test\\_%'
              AND u.wechat_review = false
            GROUP BY e.category
            ORDER BY entryCount DESC
            """, nativeQuery = true)
    List<CategoryRow> sumByCategory();

    /**
     * 门店 TOP（按关联账目条数降序——「使用情况」口径以行为计数为主、金额进
     * tooltip；venue_name 为账目行快照，取 MAX 规避同名门店多快照分裂成多行）。
     */
    @Query(value = """
            SELECT e.venue_id AS venueId,
                   MAX(e.venue_name) AS venueName,
                   COUNT(*) AS entryCount,
                   COALESCE(SUM(e.amount), 0) AS total
            FROM qwt_spend_entries e
            JOIN qwt_users u ON u.id = e.user_id
            WHERE e.deleted = 0 AND e.venue_id IS NOT NULL
              AND u.deleted = false AND u.role = 'USER'
              AND u.open_id NOT LIKE 'test\\_%'
              AND u.wechat_review = false
            GROUP BY e.venue_id
            ORDER BY entryCount DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<VenueRow> sumByVenueTop(@Param("limit") int limit);

    /**
     * 记账用户列表（按最近记账降序）：有账目的去重用户 + 逐用户聚合
     * （笔数 / 计时结算笔数 / 手动笔数 / 收支金额 / 最近记账时刻），昵称随行。
     * 口径与上方聚合完全同族（剔 ADMIN / test_ / wechat_review + 未软删）。
     */
    @Query(value = """
            SELECT e.user_id AS userId,
                   u.nickname AS nickname,
                   u.avatar_url AS avatarUrl,
                   COUNT(*) AS entryCount,
                   COALESCE(SUM(e.source = 'DANCE'), 0) AS danceEntries,
                   COALESCE(SUM(e.source = 'MANUAL'), 0) AS manualEntries,
                   COALESCE(SUM(CASE WHEN e.direction = 'EXPENSE' THEN e.amount ELSE 0 END), 0) AS expenseTotal,
                   COALESCE(SUM(CASE WHEN e.direction = 'INCOME' THEN e.amount ELSE 0 END), 0) AS incomeTotal,
                   MAX(e.ts) AS lastEntryAt
            FROM qwt_spend_entries e
            JOIN qwt_users u ON u.id = e.user_id
            WHERE e.deleted = 0
              AND u.deleted = false AND u.role = 'USER'
              AND u.open_id NOT LIKE 'test\\_%'
              AND u.wechat_review = false
            GROUP BY e.user_id, u.nickname, u.avatar_url
            ORDER BY lastEntryAt DESC
            """, nativeQuery = true)
    List<UsageUserRow> listUsageUsers();

    /** 单用户账目汇总（全量历史，不受流水条数上限影响） */
    @Query(value = """
            SELECT COUNT(*) AS entryCount,
                   COALESCE(SUM(e.source = 'DANCE'), 0) AS danceEntries,
                   COALESCE(SUM(e.source = 'MANUAL'), 0) AS manualEntries,
                   COALESCE(SUM(CASE WHEN e.direction = 'EXPENSE' THEN e.amount ELSE 0 END), 0) AS expenseTotal,
                   COALESCE(SUM(CASE WHEN e.direction = 'INCOME' THEN e.amount ELSE 0 END), 0) AS incomeTotal
            FROM qwt_spend_entries e
            WHERE e.user_id = :userId AND e.deleted = 0
            """, nativeQuery = true)
    UserSummaryRow sumUserSummary(@Param("userId") Long userId);

    /**
     * 单用户账目流水（按业务时刻降序，最新 limit 条；只回未软删——管理端
     * 展示口径与统计口径一致，软删行不上屏）。此为指定用户明细读取，
     * 不做用户表口径过滤（入口列表已过滤；用户详情本身保留可见性）。
     */
    @Query(value = """
            SELECT e.id AS id,
                   e.ts AS ts,
                   e.amount AS amount,
                   e.direction AS direction,
                   e.source AS source,
                   e.category AS category,
                   e.venue_id AS venueId,
                   e.venue_name AS venueName,
                   e.duration_seconds AS durationSeconds
            FROM qwt_spend_entries e
            WHERE e.user_id = :userId AND e.deleted = 0
            ORDER BY e.ts DESC, e.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<UserEntryRow> listUserEntries(@Param("userId") Long userId, @Param("limit") int limit);
}
