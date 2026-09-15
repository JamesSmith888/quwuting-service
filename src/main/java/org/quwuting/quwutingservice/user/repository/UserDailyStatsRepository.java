package org.quwuting.quwutingservice.user.repository;

import org.quwuting.quwutingservice.user.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理端「运营大盘」按日统计仓库（2026-09-06，docs/agents/35-dashboard-stats.md；
 * 仅 ADMIN 消费）。
 * <p>
 * 独立于 {@code UserRepository}（单职责：只承载大盘只读趋势 mega-query，参考
 * 舞伴 {@code DancerStatsRepository} 独立统计仓库先例）。继承空标记
 * {@link Repository} 而非 {@code JpaRepository}——不生成标准 CRUD，避免职责重叠。
 * <p>
 * <b>口径权威 = {@link UserStatsSql}（2026-09-15 起，本类不再自带口径正文）</b>：
 * 用户范围谓词与有效活跃事实集全部引用该类的编译期常量，配套门禁
 * {@code UserStatsSqlMirrorTest} 阻止内联抄写回潮。
 * <ul>
 *   <li><b>注册数</b>：当日注册的<b>真实舞友</b>（{@link UserStatsSql#USER_SCOPE}：
 *       剔 ADMIN 运营号 / {@code test_} 开发号 / 微信审核账号）——与小程序「我」页
 *       可见口径一致，与微信后台「累计用户」口径不同（微信含未登录游客）；</li>
 *   <li><b>打开数（打卡口径）</b>：当日 {@code qwt_daily_checkins} 去重用户。打卡由
 *       {@code app.ts onLaunch} 登录后<b>自动</b>触发（{@code services/autoCheckIn.ts}），
 *       仅代表「当天打开过」，<b>不代表真实使用</b>——本序列保留是因为它能反映触达/唤醒，
 *       但它<b>不得</b>作为「活跃」用于任何活跃或留存指标（判据见
 *       {@link UserStatsSql#ACTIVE_FACT_UNION}）；</li>
 *   <li><b>真实互动数</b>：当日出现在有效活跃事实集（12 表用户主动行为）的去重用户
 *       ——「有效日活」唯一口径，与顶卡「近 7 日活跃」、留存分析同一事实源；</li>
 *   <li><b>打卡型噪音注册</b>：当日注册且<b>注册后从未有任何痕迹</b>的用户数
 *       （{@link UserStatsSql#TRACE_FACT_UNION} NOT EXISTS，含被动痕迹，故意比
 *       有效活跃宽：宁可漏判噪音，不可误判真实用户）——识别微信审核/自动巡检流量。</li>
 * </ul>
 * <p>
 * 三条序列的用户范围<b>完全一致</b>（都走 {@link UserStatsSql#USER_SCOPE}）；
 * 2026-09-15 前「打开/互动」只剔审核号、注册剔 ADMIN+开发号，同图不同分母，已收敛。
 * <p>
 * MySQL 8 方言（WITH RECURSIVE 骨架补零 + INTERVAL 语法；生产 RDS MySQL，
 * <b>勿在 PG 环境执行</b>）。骨架 = [today-(days-1), today]（含今日，实时），
 * 各源 LEFT JOIN 天然补零。
 */
public interface UserDailyStatsRepository extends Repository<User, Long> {

    /**
     * 单日大盘行投影。注意：getter 名与 SQL alias（小写）逐字匹配
     * （同 DancerStatsRepository.DailyTrendRow 惯例）。
     */
    interface DailyStatsRow {
        /** 统计日 */
        LocalDate getDay();
        /** 当日注册数（真实舞友，见 {@link UserStatsSql#USER_SCOPE}） */
        Long getRegistered();
        /** 当日打开数 = 打卡去重用户（登录自动打卡口径，仅代表打开过） */
        Long getOpened();
        /** 当日有效活跃用户数（用户主动行为去重 = 有效日活） */
        Long getInteractive();
        /** 当日注册中「打卡型噪音」数（注册后从未有任何痕迹，疑似审核/巡检） */
        Long getNoisy();
    }

    /**
     * 大盘按日趋势 mega-query：一条 DB 往返取回 注册/打开/互动/噪音 四组按天序列
     * （骨架含今日，天然补零）。
     * <p>
     * 噪音判定的 NOT EXISTS 扫痕迹全集（{@link UserStatsSql#TRACE_FACT_UNION}）——
     * 表量级（数千行）下开销可接受；若互动表显著膨胀，再评估 user_id 覆盖索引。
     *
     * @param sinceDay 骨架起始（today-(days-1)，Service 层现算传入）
     */
    @Query(value = """
            WITH RECURSIVE date_series AS (
                SELECT CAST(:sinceDay AS DATE) AS day
                UNION ALL
                SELECT day + INTERVAL 1 DAY FROM date_series WHERE day < CURDATE()
            )
            SELECT d.day AS day,
                   COALESCE(r.cnt, 0) AS registered,
                   COALESCE(o.cnt, 0) AS opened,
                   COALESCE(i.cnt, 0) AS interactive,
                   COALESCE(n.cnt, 0) AS noisy
            FROM date_series d
            LEFT JOIN (SELECT DATE(u.created_at) AS day, COUNT(*) AS cnt
                       FROM qwt_users u
                       WHERE """ + " " + UserStatsSql.USER_SCOPE + " " + """
                         AND u.created_at >= CAST(:sinceDay AS DATETIME)
                       GROUP BY DATE(u.created_at)) r ON r.day = d.day
            LEFT JOIN (SELECT c.checkin_date AS day, COUNT(DISTINCT c.user_id) AS cnt
                       FROM qwt_daily_checkins c
                       JOIN qwt_users u ON u.id = c.user_id
                       WHERE """ + " " + UserStatsSql.USER_SCOPE + " " + """
                         AND c.checkin_date >= CAST(:sinceDay AS DATE)
                       GROUP BY c.checkin_date) o ON o.day = d.day
            LEFT JOIN (SELECT f.day AS day, COUNT(DISTINCT f.user_id) AS cnt
                       FROM (""" + " " + UserStatsSql.ACTIVE_FACT_UNION + " " + """
                       ) f
                       JOIN qwt_users u ON u.id = f.user_id
                       WHERE """ + " " + UserStatsSql.USER_SCOPE + " " + """
                       GROUP BY f.day) i ON i.day = d.day
            LEFT JOIN (SELECT DATE(u.created_at) AS day, COUNT(*) AS cnt
                       FROM qwt_users u
                       WHERE """ + " " + UserStatsSql.USER_SCOPE + " " + """
                         AND u.created_at >= CAST(:sinceDay AS DATETIME)
                         AND NOT EXISTS (SELECT 1 FROM (""" + " " + UserStatsSql.TRACE_FACT_UNION + " " + """
                               ) act WHERE act.user_id = u.id)
                       GROUP BY DATE(u.created_at)) n ON n.day = d.day
            ORDER BY d.day
            """, nativeQuery = true)
    List<DailyStatsRow> countDailyStats(@Param("sinceDay") LocalDate sinceDay);
}
