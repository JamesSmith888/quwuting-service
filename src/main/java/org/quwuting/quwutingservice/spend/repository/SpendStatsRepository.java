package org.quwuting.quwutingservice.spend.repository;

import org.quwuting.quwutingservice.spend.entity.SpendEntryEntity;
import org.quwuting.quwutingservice.user.repository.UserStatsSql;
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
 * <b>双口径（2026-10-07 根因修复，单一权威 = {@link SpendStatsSql}）</b>：
 * 旧实现对本仓库 7 条查询统一内联 {@code e.deleted = 0}，导致「用户删除一条账目」
 * 会连带抹掉 admin 侧的使用数据——而软删行其实完整保留在库中（无硬删路径），
 * 丢的只是可见性。根因与机制详见 {@link SpendStatsSql}：<b>计数走事实口径（含软删，
 * 用户撤回数据撤不回行为）、金额走账面口径（仅未删，撤回的金额不算消费）</b>。
 * 两列回答不同问题，同屏出现差异是设计意图；差异量由 {@code retractedEntries}
 * 显式暴露，避免明细条数与汇总对不上时无人能解释。
 * <p>
 * <b>本仓库消费方一律引用 {@link SpendStatsSql} 常量</b>，门禁
 * {@code SpendStatsScopeMirrorTest} 断言「计数走事实口径 / 金额走账面口径 /
 * 无内联 {@code deleted} 抄写」——内联抄写能过编译，但会在门禁处失败。
 * <p>
 * <b>用户范围口径</b>仍恒引用 {@link UserStatsSql#USER_SCOPE}（剔 ADMIN 运营号 /
 * {@code test_} 开发联调号 / 微信审核账号）——此处曾逐条抄写该谓词 5 遍，任一处漏改
 * 即与大盘口径漂移，故收敛为编译期常量 + 门禁 {@code UserStatsSqlMirrorTest}。
 * <p>
 * <b>注意口径边界</b>：{@link SpendStatsSql} 只管 admin 的「使用盘子 / 账面」，
 * <b>不适用于</b>用户自己的 {@code /spend/overview}、{@code /spend/entries}
 * （那里是"我的账本"，用户撤回后理应不可见）。
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

        /** 已撤回（软删）条目数——让「计数含软删、金额不含」的口径差异可被解释 */
        Long getRetractedEntries();
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

        /** 已撤回（软删）笔数（供消费方区分「删过账」与「明细被截断」） */
        Long getRetractedEntries();
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
     * 收支金额（方向口径，金额恒正由方向决定归属）/ 已撤回条目数。
     *
     * <p><b>双口径实现（2026-10-07）</b>：计数走事实口径 {@link SpendStatsSql#FACT_ENTRY}
     * （含软删——用户撤回数据撤不回使用行为），金额走账面口径
     * {@link SpendStatsSql#LEDGER_ENTRY}（仅未删——撤回的金额不算消费）。
     * 两条聚合在<b>同一语句的两个派生表</b>里各走各的口径，避免"先过滤再统计"
     * 让某一侧被另一侧的谓词污染。
     * <p><b>此处 CROSS JOIN 安全</b>：两个派生表都是<b>无 GROUP BY 的标量聚合</b>
     * （{@code COUNT(*)} / {@code COALESCE(SUM(...))}），恒各返回一行，不会出现
     * "某一侧无行 ⇒ 整行消失"。分组的分类/门店查询则必须 LEFT JOIN（见各自注释）。
     *
     * @param activeSince 近 7 日活跃下界（today-6，Service 层现算）
     */
    @Query(value = """
            SELECT c.totalUsers AS totalUsers,
                   c.timerUsers AS timerUsers,
                   c.active7d AS active7d,
                   c.totalEntries AS totalEntries,
                   c.danceEntries AS danceEntries,
                   c.manualEntries AS manualEntries,
                   m.expenseTotal AS expenseTotal,
                   m.incomeTotal AS incomeTotal,
                   c.totalEntries - m.ledgerEntries AS retractedEntries
            FROM (SELECT COUNT(DISTINCT e.user_id) AS totalUsers,
                         COUNT(DISTINCT CASE WHEN e.source = 'DANCE' THEN e.user_id END) AS timerUsers,
                         COUNT(DISTINCT CASE WHEN e.ts >= :activeSince THEN e.user_id END) AS active7d,
                         COUNT(*) AS totalEntries,
                         COALESCE(SUM(e.source = 'DANCE'), 0) AS danceEntries,
                         COALESCE(SUM(e.source = 'MANUAL'), 0) AS manualEntries
                  FROM qwt_spend_entries e
                  JOIN qwt_users u ON u.id = e.user_id
                  WHERE """ + " " + SpendStatsSql.FACT_ENTRY + " " + """
                    AND""" + " " + UserStatsSql.USER_SCOPE + " " + """
            ) c
            CROSS JOIN (SELECT COUNT(*) AS ledgerEntries,
                               COALESCE(SUM(CASE WHEN e.direction = 'EXPENSE' THEN e.amount ELSE 0 END), 0) AS expenseTotal,
                               COALESCE(SUM(CASE WHEN e.direction = 'INCOME' THEN e.amount ELSE 0 END), 0) AS incomeTotal
                        FROM qwt_spend_entries e
                        JOIN qwt_users u ON u.id = e.user_id
                        WHERE """ + " " + SpendStatsSql.LEDGER_ENTRY + " " + """
                          AND""" + " " + UserStatsSql.USER_SCOPE + " " + """
            ) m
            """, nativeQuery = true)
    SummaryRow sumSummary(@Param("activeSince") LocalDate activeSince);

    /**
     * 近 N 天（含今日）按日序列：计时结算条目（source=DANCE，计时器结算自动
     * 入账）/ 手动补记条目（source=MANUAL）/ 当日活跃记账用户（去重）。
     * WITH RECURSIVE 骨架补零，无数据日天然为 0（同大盘三线趋势骨架）。
     * <p>纯计数聚合 ⇒ 走事实口径（含软删）：用户删掉当天的账目，不代表他当天没用过。
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
                       WHERE """ + " " + SpendStatsSql.FACT_ENTRY + " " + """
                         AND e.ts >= CAST(:sinceDay AS DATETIME)
                         AND""" + " " + UserStatsSql.USER_SCOPE + " " + """
                       GROUP BY DATE(e.ts)) s ON s.day = d.day
            ORDER BY d.day
            """, nativeQuery = true)
    List<DailyRow> countDaily(@Param("sinceDay") LocalDate sinceDay);

    /**
     * 支出分类分布（direction='EXPENSE'——与小程序统计页「消费分析」口径一致，
     * GUEST 收入向分类不入图；收入在汇总行体现）。只返回有数据行，前端按固定
     * 7 类补零渲染（分类集 = 服务端 SpendCategory 枚举权威）。
     * <p><b>双口径</b>：{@code entryCount} 走事实口径（含软删——笔数回答"分类被用过几次"），
     * {@code total} 走账面口径（仅未删——金额回答"这个分类当前实际花了多少"）。
     * 两条聚合在同一语句的两个派生表里各走各的口径。
     * <p><b>必须 LEFT JOIN 而非 CROSS JOIN</b>：账面派生表以账面行为准，若某分类的
     * 条目<b>全部</b>被软删，该分类在 {@code m} 里没有行——CROSS JOIN 会让这个
     * "用过但已全部撤回"的分类<b>整行消失</b>，正是本次要修的丢失现象换个形式复发。
     * LEFT JOIN 下它仍以「笔数有数、金额 0」出现，如实反映"发生过、账面已撤回"。
     */
    @Query(value = """
            SELECT f.category AS category,
                   f.entryCount AS entryCount,
                   COALESCE(m.total, 0) AS total
            FROM (SELECT e.category AS category,
                         COUNT(*) AS entryCount
                  FROM qwt_spend_entries e
                  JOIN qwt_users u ON u.id = e.user_id
                  WHERE """ + " " + SpendStatsSql.FACT_ENTRY + " " + """
                    AND e.direction = 'EXPENSE'
                    AND""" + " " + UserStatsSql.USER_SCOPE + " " + """
                  GROUP BY e.category) f
            LEFT JOIN (SELECT e.category AS category,
                              COALESCE(SUM(e.amount), 0) AS total
                       FROM qwt_spend_entries e
                       JOIN qwt_users u ON u.id = e.user_id
                       WHERE """ + " " + SpendStatsSql.LEDGER_ENTRY + " " + """
                         AND e.direction = 'EXPENSE'
                         AND""" + " " + UserStatsSql.USER_SCOPE + " " + """
                       GROUP BY e.category) m ON m.category = f.category
            ORDER BY f.entryCount DESC
            """, nativeQuery = true)
    List<CategoryRow> sumByCategory();

    /**
     * 门店 TOP（按关联账目条数降序——「使用情况」口径以行为计数为主、金额进
     * tooltip；venue_name 为账目行快照，取 MAX 规避同名门店多快照分裂成多行）。
     * <p><b>双口径 + LEFT JOIN</b>：{@code entryCount} 走事实口径（含软删），
     * {@code total} 走账面口径（仅未删）。LEFT JOIN 的理由同 {@link #sumByCategory()}：
     * 某门店条目若全部被软删，它在账面派生表里没有行，CROSS JOIN 会让这个门店
     * 从 TOP 榜上整个消失——即本次要修的现象在门店维度复发。
     */
    @Query(value = """
            SELECT f.venueId AS venueId,
                   f.venueName AS venueName,
                   f.entryCount AS entryCount,
                   COALESCE(m.total, 0) AS total
            FROM (SELECT e.venue_id AS venueId,
                         MAX(e.venue_name) AS venueName,
                         COUNT(*) AS entryCount
                  FROM qwt_spend_entries e
                  JOIN qwt_users u ON u.id = e.user_id
                  WHERE """ + " " + SpendStatsSql.FACT_ENTRY + " " + """
                    AND e.venue_id IS NOT NULL
                    AND""" + " " + UserStatsSql.USER_SCOPE + " " + """
                  GROUP BY e.venue_id) f
            LEFT JOIN (SELECT e.venue_id AS venueId,
                              COALESCE(SUM(e.amount), 0) AS total
                       FROM qwt_spend_entries e
                       JOIN qwt_users u ON u.id = e.user_id
                       WHERE """ + " " + SpendStatsSql.LEDGER_ENTRY + " " + """
                         AND e.venue_id IS NOT NULL
                         AND""" + " " + UserStatsSql.USER_SCOPE + " " + """
                       GROUP BY e.venue_id) m ON m.venueId = f.venueId
            ORDER BY f.entryCount DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<VenueRow> sumByVenueTop(@Param("limit") int limit);

    /**
     * 记账用户列表（按最近记账降序）：有账目的去重用户 + 逐用户聚合
     * （笔数 / 计时结算笔数 / 手动笔数 / 收支金额 / 最近记账时刻），昵称随行。
     * <p><b>双口径</b>：用户列表的<b>存在性本身</b>就是使用事实——用户删光了自己的
     * 账目后，他仍是"记账用户"（否则整个用户从列表里消失，运营会以为该用户流失）。
     * 故 {@code entryCount} / {@code danceEntries} / {@code manualEntries} /
     * {@code lastEntryAt} 走事实口径，{@code expenseTotal} / {@code incomeTotal}
     * 走账面口径。LEFT JOIN 理由同 {@link #sumByCategory()}。
     */
    @Query(value = """
            SELECT f.userId AS userId,
                   f.nickname AS nickname,
                   f.avatarUrl AS avatarUrl,
                   f.entryCount AS entryCount,
                   f.danceEntries AS danceEntries,
                   f.manualEntries AS manualEntries,
                   COALESCE(m.expenseTotal, 0) AS expenseTotal,
                   COALESCE(m.incomeTotal, 0) AS incomeTotal,
                   f.lastEntryAt AS lastEntryAt
            FROM (SELECT e.user_id AS userId,
                         u.nickname AS nickname,
                         u.avatar_url AS avatarUrl,
                         COUNT(*) AS entryCount,
                         COALESCE(SUM(e.source = 'DANCE'), 0) AS danceEntries,
                         COALESCE(SUM(e.source = 'MANUAL'), 0) AS manualEntries,
                         MAX(e.ts) AS lastEntryAt
                  FROM qwt_spend_entries e
                  JOIN qwt_users u ON u.id = e.user_id
                  WHERE """ + " " + SpendStatsSql.FACT_ENTRY + " " + """
                    AND""" + " " + UserStatsSql.USER_SCOPE + " " + """
                  GROUP BY e.user_id, u.nickname, u.avatar_url) f
            LEFT JOIN (SELECT e.user_id AS userId,
                              COALESCE(SUM(CASE WHEN e.direction = 'EXPENSE' THEN e.amount ELSE 0 END), 0) AS expenseTotal,
                              COALESCE(SUM(CASE WHEN e.direction = 'INCOME' THEN e.amount ELSE 0 END), 0) AS incomeTotal
                       FROM qwt_spend_entries e
                       JOIN qwt_users u ON u.id = e.user_id
                       WHERE """ + " " + SpendStatsSql.LEDGER_ENTRY + " " + """
                         AND""" + " " + UserStatsSql.USER_SCOPE + " " + """
                       GROUP BY e.user_id) m ON m.userId = f.userId
            ORDER BY f.lastEntryAt DESC
            """, nativeQuery = true)
    List<UsageUserRow> listUsageUsers();

    /**
     * 单用户账目汇总（全量历史，不受流水条数上限影响）。
     * <p><b>双口径</b>：与 {@link #sumSummary()} 同族——计数字段走事实口径、
     * 金额字段走账面口径。标量聚合故 CROSS JOIN 安全。
     * <p>此为<b>指定用户</b>明细读取，不引用 {@link UserStatsSql#USER_SCOPE}
     * （入口列表已过滤；用户详情本身保留可见性），同 {@link #listUserEntries()}。
     */
    @Query(value = """
            SELECT c.entryCount AS entryCount,
                   c.danceEntries AS danceEntries,
                   c.manualEntries AS manualEntries,
                   m.expenseTotal AS expenseTotal,
                   m.incomeTotal AS incomeTotal,
                   c.entryCount - m.ledgerEntries AS retractedEntries
            FROM (SELECT COUNT(*) AS entryCount,
                         COALESCE(SUM(e.source = 'DANCE'), 0) AS danceEntries,
                         COALESCE(SUM(e.source = 'MANUAL'), 0) AS manualEntries
                  FROM qwt_spend_entries e
                  WHERE e.user_id = :userId
                    AND""" + SpendStatsSql.FACT_ENTRY + """
            ) c
            CROSS JOIN (SELECT COUNT(*) AS ledgerEntries,
                               COALESCE(SUM(CASE WHEN e.direction = 'EXPENSE' THEN e.amount ELSE 0 END), 0) AS expenseTotal,
                               COALESCE(SUM(CASE WHEN e.direction = 'INCOME' THEN e.amount ELSE 0 END), 0) AS incomeTotal
                        FROM qwt_spend_entries e
                        WHERE e.user_id = :userId
                          AND""" + SpendStatsSql.LEDGER_ENTRY + """
            ) m
            """, nativeQuery = true)
    UserSummaryRow sumUserSummary(@Param("userId") Long userId);

    /**
     * 单用户账目流水（按业务时刻降序，最新 limit 条；<b>只回未软删</b>）。
     * <p><b>为何明细仍是账面口径，而汇总已是双口径</b>：本方法回答的是
     * "这个用户当前账面上还有哪些条目"——它是<b>明细读取</b>，不是"使用情况"聚合。
     * 汇总里的计数回答"他用没用过"（事实，含软删），本方法回答"他账上现在有什么"
     * （账面，仅未删）——两者语义本就不同，合并反而会让列表与汇总互相矛盾。
     * <p>此为指定用户明细读取，不做用户表口径过滤（入口列表已过滤）。
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
            WHERE e.user_id = :userId
              AND""" + SpendStatsSql.LEDGER_ENTRY + """
            ORDER BY e.ts DESC, e.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<UserEntryRow> listUserEntries(@Param("userId") Long userId, @Param("limit") int limit);
}
