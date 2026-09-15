package org.quwuting.quwutingservice.user.repository;

import org.quwuting.quwutingservice.user.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理端「用户留存分析」统计仓库（2026-09-15，docs/agents/35-dashboard-stats.md；
 * 仅 ADMIN 消费）。
 * <p>
 * 独立于 {@code UserRepository} / {@code UserDailyStatsRepository}（单职责：只承载
 * 留存只读聚合，参考 {@code DancerStatsRepository} / {@code SpendStatsRepository}
 * 独立统计仓先例）。继承空标记 {@link Repository} 而非 {@code JpaRepository}——
 * 不生成标准 CRUD，避免职责重叠。
 * <p>
 * <b>口径（唯一权威 = {@link UserStatsSql}，禁止在本类重新定义）</b>：
 * <ul>
 *   <li><b>有效用户</b>：{@link UserStatsSql#USER_SCOPE}（真实舞友，剔 ADMIN /
 *       {@code test_} 开发号 / 微信审核账号）；</li>
 *   <li><b>活跃</b>（留存判定）= 当日出现在 {@link UserStatsSql#ACTIVE_FACT_UNION}
 *       （12 表用户主动行为）——<b>刻意不含每日打卡</b>：打卡是登录后自动触发
 *       （{@code services/autoCheckIn.ts}），只代表「打开过」，把它当活跃正是本次
 *       修复的错误决策（见 docs/agents/35「口径单一事实源」）；</li>
 *   <li><b>留存窗口</b>：本仓只返回「事实」——批次规模、批次 × 偏移的留存人数、
 *       逐日新老活跃人数；比率与「窗口未到期」判定在
 *       {@code AdminUserRetentionService}（纯算术，避免把魔法除法散进 SQL）。</li>
 * </ul>
 * <p>
 * <b>为什么不做成一条 mega-query</b>：留存需要三类形状不同的结果（批次规模 /
 * 批次×偏移 / 逐日拆分），硬塞进一条 SQL 只能靠 {@code UNION} 拼装人造列，
 * 可读性与可测试性都会崩。此处按 {@code SpendStatsRepository} 先例拆成 4 条
 * <b>同参同窗口</b>的聚合，服务层一次调用只产生一次 HTTP 往返。
 * <p>
 * <b>MySQL 8 方言</b>（WITH RECURSIVE 骨架补零；生产 RDS MySQL），<b>勿在 PG 环境
 * 执行</b>（同 {@code UserDailyStatsRepository}）。
 */
public interface UserRetentionRepository extends Repository<User, Long> {

    /**
     * 单批次规模行投影。注意：getter 名与 SQL alias（小写）逐字匹配
     * （同仓惯例 {@code UserDailyStatsRepository.DailyStatsRow}）。
     */
    interface CohortSizeRow {
        /** 批次日（注册日，Asia/Shanghai 自然日） */
        LocalDate getCohortDay();
        /** 该批次有效用户数（留存率分母） */
        Long getSize();
    }

    /** 批次 × 留存偏移行投影 */
    interface CohortRetentionRow {
        /** 批次日 */
        LocalDate getCohortDay();
        /** 距注册日的天数偏移（≥1，0 = 注册当日不参与留存） */
        Integer getDayOffset();
        /** 该批次在 D{dayOffset} 有有效活跃的去重用户数 */
        Long getRetained();
    }

    /** 逐日新老活跃拆分行投影 */
    interface DailyActivityRow {
        /** 统计日 */
        LocalDate getDay();
        /** 当日「新活跃」= 注册当日即活跃的去重用户数 */
        Long getNewActive();
        /** 当日「老用户活跃」= 注册日早于当日、当日仍活跃的去重用户数 */
        Long getReturningActive();
    }

    /** 近 N 日活跃汇总行投影 */
    interface ActivitySummaryRow {
        /** 近 N 日有有效活跃的去重用户数 */
        Long getActiveUsers();
        /** 其中「回访型」= 活跃日早于注册日的去重用户数（老用户活跃） */
        Long getReturningUsers();
    }

    /** 口径自证行投影（账号盘子漏斗） */
    interface ScopeAuditRow {
        /** 全部未软删账号数（含被排除的运营/开发号与审核号） */
        Long getTotalAccounts();
        /** 其中运营/开发号数（role=ADMIN 或 open_id 以 test_ 开头） */
        Long getOpsExcluded();
        /** 其余中被标记微信审核的账号数（与上一项互斥，保证三项相加 = 全部账号） */
        Long getReviewExcluded();
    }

    /**
     * 批次规模：窗口内每个注册日的有效用户数（近 N 天批次的分母）。
     * <p>
     * 只返回有注册的日期（无注册日天然缺席，不是 0——批次不存在与批次为 0 人
     * 是两件事，服务层按「批次不存在 = 无此批次」处理）。
     *
     * @param sinceDay 批次窗口起始（today-(days-1)，Service 层现算）
     */
    @Query(value = """
            SELECT DATE(u.created_at) AS cohortDay, COUNT(*) AS size
            FROM qwt_users u
            WHERE """ + " " + UserStatsSql.USER_SCOPE + " " + """
              AND u.created_at >= CAST(:sinceDay AS DATETIME)
            GROUP BY DATE(u.created_at)
            ORDER BY cohortDay
            """, nativeQuery = true)
    List<CohortSizeRow> listCohortSizes(@Param("sinceDay") LocalDate sinceDay);

    /**
     * 批次 × 偏移留存人数：窗口内批次在 D1、D2…（{@code day > cohortDay} 的所有偏移）
     * 有有效活跃的去重用户数。只回「有数据的 (批次, 偏移)」组合，缺失组合 = 该偏移
     * 留存为 0（服务层补零）——<b>但不区分「到期无人回访」与「窗口未到期」</b>，
     * 未到期判定由服务层按 {@code cohortDay + offset > today} 现算（禁把未到期当 0%，
     * 那是留存报表最常见的错误决策来源）。
     *
     * @param sinceDay 批次窗口起始（同时是活跃事实的窗口下界，见 {@link UserStatsSql}）
     */
    @Query(value = """
            SELECT c.cohortDay AS cohortDay,
                   DATEDIFF(a.day, c.cohortDay) AS dayOffset,
                   COUNT(DISTINCT a.user_id) AS retained
            FROM (SELECT u.id AS user_id, DATE(u.created_at) AS cohortDay
                  FROM qwt_users u
                  WHERE """ + " " + UserStatsSql.USER_SCOPE + " " + """
                    AND u.created_at >= CAST(:sinceDay AS DATETIME)) c
            JOIN (SELECT DISTINCT f.user_id, f.day FROM (""" + " " + UserStatsSql.ACTIVE_FACT_UNION + " " + """
                  ) f
                  JOIN qwt_users u ON u.id = f.user_id
                  WHERE """ + " " + UserStatsSql.USER_SCOPE + " " + """
                 ) a ON a.user_id = c.user_id AND a.day > c.cohortDay
            GROUP BY c.cohortDay, DATEDIFF(a.day, c.cohortDay)
            ORDER BY c.cohortDay, dayOffset
            """, nativeQuery = true)
    List<CohortRetentionRow> listCohortRetention(@Param("sinceDay") LocalDate sinceDay);

    /**
     * 逐日活跃拆分（近 N 天含今日，WITH RECURSIVE 骨架补零）：当日「新活跃」
     * （注册当日即活跃）与「老用户活跃」（注册日早于当日仍活跃）。
     * <p>
     * 这一条回答运营真正的问题——「存量还在不在」：日总量曲线把新人与存量混在
     * 一起，早期注册量大时「看起来在涨」而<b>存量流失被新增掩盖</b>。
     * 拆分后老用户线才是留在平台上的那批人。
     * <p>
     * 活跃用户全集不受批次窗口限制（窗口前注册的老用户同样计入）——故本查询的
     * 用户侧 <b>JOIN qwt_users 而非复用 batches 子查询</b>。
     *
     * @param sinceDay 统计窗口起始（含，Service 层现算）
     */
    @Query(value = """
            WITH RECURSIVE date_series AS (
                SELECT CAST(:sinceDay AS DATE) AS day
                UNION ALL
                SELECT day + INTERVAL 1 DAY FROM date_series WHERE day < CURDATE()
            )
            SELECT d.day AS day,
                   COALESCE(s.newActive, 0) AS newActive,
                   COALESCE(s.returningActive, 0) AS returningActive
            FROM date_series d
            LEFT JOIN (SELECT a.day AS day,
                              COUNT(DISTINCT CASE WHEN DATE(u.created_at) = a.day THEN a.user_id END) AS newActive,
                              COUNT(DISTINCT CASE WHEN DATE(u.created_at) < a.day THEN a.user_id END) AS returningActive
                       FROM (SELECT DISTINCT f.user_id, f.day FROM (""" + " " + UserStatsSql.ACTIVE_FACT_UNION + " " + """
                             ) f) a
                       JOIN qwt_users u ON u.id = a.user_id
                       WHERE """ + " " + UserStatsSql.USER_SCOPE + " " + """
                       GROUP BY a.day) s ON s.day = d.day
            ORDER BY d.day
            """, nativeQuery = true)
    List<DailyActivityRow> listDailyActivity(@Param("sinceDay") LocalDate sinceDay);

    /**
     * 近 N 日活跃汇总：窗口内有有效活跃的去重用户数，以及其中「活跃日早于注册日」
     * 的回访型（老用户）用户数。与 {@link #listDailyActivity} 同窗口同口径，
     * 差别只是「跨日去重」——两者可交叉验算（逐日老用户活跃的去重上限 ≥ 本汇总）。
     *
     * @param sinceDay 统计窗口起始（含，Service 层现算）
     */
    @Query(value = """
            SELECT COUNT(DISTINCT a.user_id) AS activeUsers,
                   COUNT(DISTINCT CASE WHEN DATE(u.created_at) < a.day THEN a.user_id END) AS returningUsers
            FROM (SELECT DISTINCT f.user_id, f.day FROM (""" + " " + UserStatsSql.ACTIVE_FACT_UNION + " " + """
                  ) f) a
            JOIN qwt_users u ON u.id = a.user_id
            WHERE """ + " " + UserStatsSql.USER_SCOPE, nativeQuery = true)
    ActivitySummaryRow sumActivity(@Param("sinceDay") LocalDate sinceDay);

    /**
     * <b>口径自证（账号盘子漏斗）</b>：全部未软删账号 = 有效用户 + 运营/开发号 + 微信审核号。
     * <p>
     * 为什么需要它：管理端口径「剔除管理员与微信审核账号」是一条**不可见**的约定——页面上的
     * 数字是否正确剔除，运营只能靠信任。本查询把被剔除的两类账号数摆到台面上，与
     * {@code summary.totalUsers} 相加应恰好等于全部账号（互斥划分，见 {@link ScopeAuditRow}），
     * 于是「有没有剔除」变成可当场验算的一行等式。
     * <p>
     * 谓词引用 {@link UserStatsSql#OPS_ACCOUNT_PREDICATE} /
     * {@link UserStatsSql#REVIEW_ACCOUNT_PREDICATE}（与 {@link UserStatsSql#USER_SCOPE}
     * 是同一套规则的正反两面，必须同步演进——门禁 {@code UserStatsSqlMirrorTest} 锁定）。
     * 本查询刻意<b>不</b>引用 USER_SCOPE：它统计的正是被 USER_SCOPE 排除的那部分。
     */
    @Query(value = "SELECT COUNT(*) AS totalAccounts,"
            + " COALESCE(SUM(" + UserStatsSql.OPS_ACCOUNT_PREDICATE + "), 0) AS opsExcluded,"
            + " COALESCE(SUM(NOT (" + UserStatsSql.OPS_ACCOUNT_PREDICATE + ")"
            + " AND " + UserStatsSql.REVIEW_ACCOUNT_PREDICATE + "), 0) AS reviewExcluded"
            + " FROM qwt_users u"
            + " WHERE u.deleted = false", nativeQuery = true)
    ScopeAuditRow sumScopeAudit();
}
