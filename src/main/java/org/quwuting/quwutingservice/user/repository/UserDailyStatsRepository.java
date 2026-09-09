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
 * <b>口径说明（与 2026-09-06 生产库真实画像分析一致，docs/agents/35）</b>：
 * <ul>
 *   <li><b>注册数</b>：当日 qwt_users.created_at 落当日且未软删的真实舞友
 *       （role='USER'，剔除 role='ADMIN' 的运营/测试号与 open_id 以 test_ 开头的
 *       开发联调号）——与小程序端「我」页可见、与微信后台「累计用户」口径不同
 *       （微信含未登录游客，DB 只记登录建号用户）；</li>
 *   <li><b>打开数（打卡口径）</b>：当日 qwt_daily_checkins 去重用户——打卡是
 *       app.ts onLaunch 登录后自动触发（services/autoCheckIn.ts），仅代表「当天
 *       打开过小程序且登录成功」，<b>不代表真实使用</b>；</li>
 *   <li><b>真实互动数</b>：当日至少在任一互动行为表（门店/舞伴浏览、门店/舞伴
 *       分享、表情认可、门店/舞伴收藏、邀约、关注、热度上报、纠错、标签互动）有
 *       记录的去重用户——这是运营关注的「有效日活」口径；</li>
 *   <li><b>打卡型噪音注册</b>：当日注册且<u>注册后从未有任何真实互动</u>的
 *       用户数（互动全集 NOT EXISTS）——识别微信审核/自动巡检流量（2026-09-03 起
 *       占比骤升 60%+，深夜均匀注册等特征见 35 号文档），前端以「噪音占比」呈现。</li>
 * </ul>
 * <p>
 * <b>2026-09-09 V17 口径收紧</b>：全部四序列排除微信审核账号（qwt_users.
 * wechat_review=true，V17 存量名单 + admin-web 用户详情页手动标记）——注册/噪音
 * 在用户表子查询直接过滤；打开/互动在行为子查询外层
 * {@code user_id NOT IN (SELECT id FROM qwt_users WHERE wechat_review = true)}
 * 过滤（行为表无用户标记冗余，统一回查用户表）。
 * <p>
 * MySQL 8 方言（生产 RDS 已切 MySQL，2026-08-30；WITH RECURSIVE 骨架补零 +
 * DATE_SUB/INTERVAL 语法与 application-mysql.yaml 同族；<b>勿在 PG 环境执行</b>）。
 * 骨架 = [today-(days-1), today]（含今日，实时），各源 LEFT JOIN 天然补零。
 */
public interface UserDailyStatsRepository extends Repository<User, Long> {

    /**
     * 单日大盘行投影。注意：getter 名与 SQL alias（小写）逐字匹配
     * （同 DancerStatsRepository.DailyTrendRow 惯例）。
     */
    interface DailyStatsRow {
        /** 统计日 */
        LocalDate getDay();
        /** 当日注册数（role='USER' 且非 test_ 前缀，见类注释口径） */
        Long getRegistered();
        /** 当日打开数 = 打卡去重用户（登录自动打卡口径） */
        Long getOpened();
        /** 当日真实互动用户数（浏览/分享/收藏/表情/邀约/关注/上报等去重） */
        Long getInteractive();
        /** 当日注册中「打卡型噪音」数（注册后从未真实互动，疑似审核/巡检） */
        Long getNoisy();
    }

    /**
     * 大盘按日趋势 mega-query：一条 DB 往返取回 注册/打开/互动/噪音 四组按天
     * 序列（骨架 30 天含今日，天然补零）。噪音判定子查询扫互动全集（NOT EXISTS），
     * 表量级（数千行）下开销可接受；若未来互动表膨胀，可加 user_id 覆盖索引评估。
     *
     * @param days     窗口天数（含今日，1~90，Service 层钳制）
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
            LEFT JOIN (SELECT DATE(created_at) AS day, COUNT(*) AS cnt
                       FROM qwt_users
                       WHERE deleted = false AND role = 'USER'
                         AND open_id NOT LIKE 'test\\_%'
                         AND wechat_review = false
                         AND created_at >= CAST(:sinceDay AS DATETIME)
                       GROUP BY day) r ON r.day = d.day
            LEFT JOIN (SELECT checkin_date AS day, COUNT(DISTINCT c.user_id) AS cnt
                       FROM qwt_daily_checkins c
                       WHERE c.checkin_date >= CAST(:sinceDay AS DATE)
                         AND c.user_id NOT IN (SELECT id FROM qwt_users WHERE wechat_review = true)
                       GROUP BY day) o ON o.day = d.day
            LEFT JOIN (SELECT t.day AS day, COUNT(DISTINCT t.user_id) AS cnt
                       FROM (
                           SELECT user_id, view_date AS day FROM qwt_venue_views WHERE user_id IS NOT NULL AND view_date >= CAST(:sinceDay AS DATE)
                           UNION ALL SELECT user_id, view_date AS day FROM qwt_dancer_views WHERE user_id IS NOT NULL AND view_date >= CAST(:sinceDay AS DATE)
                           UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_venue_shares WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
                           UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_dancer_shares WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
                           UNION ALL SELECT user_id, reaction_date AS day FROM qwt_venue_reactions WHERE user_id IS NOT NULL AND deleted = false AND reaction_date >= CAST(:sinceDay AS DATE)
                           UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_favorites WHERE user_id IS NOT NULL AND deleted = false AND unfavorited_at IS NULL AND created_at >= CAST(:sinceDay AS DATETIME)
                           UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_dancer_favorites WHERE user_id IS NOT NULL AND deleted = false AND created_at >= CAST(:sinceDay AS DATETIME)
                           UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_demand_records WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
                           UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_venue_status_watchers WHERE user_id IS NOT NULL AND deleted = false AND created_at >= CAST(:sinceDay AS DATETIME)
                           UNION ALL SELECT user_id, report_date AS day FROM qwt_venue_crowd_reports WHERE user_id IS NOT NULL AND deleted = false AND report_date >= CAST(:sinceDay AS DATE)
                           UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_venue_feedbacks WHERE user_id IS NOT NULL AND deleted = false AND created_at >= CAST(:sinceDay AS DATETIME)
                           UNION ALL SELECT user_id, DATE(created_at) AS day FROM qwt_tag_interactions WHERE user_id IS NOT NULL AND deleted = false AND created_at >= CAST(:sinceDay AS DATETIME)
                       ) t
                       WHERE t.user_id NOT IN (SELECT id FROM qwt_users WHERE wechat_review = true)
                       GROUP BY t.day) i ON i.day = d.day
            LEFT JOIN (SELECT DATE(u.created_at) AS day, COUNT(*) AS cnt
                       FROM qwt_users u
                       WHERE u.deleted = false AND u.role = 'USER'
                         AND u.open_id NOT LIKE 'test\\_%'
                         AND u.wechat_review = false
                         AND u.created_at >= CAST(:sinceDay AS DATETIME)
                         AND NOT EXISTS (SELECT 1 FROM (
                               SELECT user_id FROM qwt_venue_views WHERE user_id IS NOT NULL
                               UNION SELECT user_id FROM qwt_dancer_views WHERE user_id IS NOT NULL
                               UNION SELECT user_id FROM qwt_venue_shares WHERE user_id IS NOT NULL
                               UNION SELECT user_id FROM qwt_dancer_shares WHERE user_id IS NOT NULL
                               UNION SELECT user_id FROM qwt_venue_reactions WHERE user_id IS NOT NULL AND deleted = false
                               UNION SELECT user_id FROM qwt_favorites WHERE user_id IS NOT NULL AND deleted = false
                               UNION SELECT user_id FROM qwt_dancer_favorites WHERE user_id IS NOT NULL AND deleted = false
                               UNION SELECT user_id FROM qwt_demand_records WHERE user_id IS NOT NULL
                               UNION SELECT user_id FROM qwt_venue_status_watchers WHERE user_id IS NOT NULL AND deleted = false
                               UNION SELECT user_id FROM qwt_venue_crowd_reports WHERE user_id IS NOT NULL AND deleted = false
                               UNION SELECT user_id FROM qwt_venue_feedbacks WHERE user_id IS NOT NULL AND deleted = false
                               UNION SELECT user_id FROM qwt_tag_interactions WHERE user_id IS NOT NULL AND deleted = false
                               UNION SELECT user_id FROM qwt_messages WHERE user_id IS NOT NULL AND deleted = false
                               UNION SELECT user_id FROM qwt_venue_status_reports WHERE user_id IS NOT NULL AND deleted = false
                               UNION SELECT user_id FROM qwt_recruitment_contacts WHERE user_id IS NOT NULL AND deleted = false
                               UNION SELECT user_id FROM qwt_announcement_reads WHERE user_id IS NOT NULL
                           ) act WHERE act.user_id = u.id)
                       GROUP BY day) n ON n.day = d.day
            ORDER BY d.day
            """, nativeQuery = true)
    List<DailyStatsRow> countDailyStats(@Param("sinceDay") LocalDate sinceDay);
}
