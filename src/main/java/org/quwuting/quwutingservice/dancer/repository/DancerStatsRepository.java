package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerView;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 舞伴统计聚合仓库（2026-08-14 舞伴统计图第一期）。
 * <p>
 * 独立于 {@code DancerRepository}（单职责：只承载统计趋势 mega-query）。
 * 继承空标记 {@link Repository} 而非 {@code JpaRepository}——本仓库只提供只读
 * 趋势查询，不为 DancerView 生成标准 CRUD（避免与 DancerViewRepository 职责重叠）。
 * 完全对齐门店 {@code VenueRepository#countDailyTrends} 的骨架与口径：
 * <ul>
 *   <li>generate_series 生成连续日期骨架（天然补零），各源表 GROUP BY day 后
 *       LEFT JOIN 骨架——一条 DB 往返取回全部时间序列（认可/收藏/礼物/分享/
 *       浏览含来源分列，共 8 个计数字段）；</li>
 *   <li>时区链修复（门店 2026-08-08 实机复现教训）：骨架必须显式
 *       {@code ::timestamp} 重载 + {@code ::date} 收口——generate_series(date, date,
 *       interval) 会被 PG 解析到 timestamptz 重载（datetime 类别 preferred type），
 *       依赖 session timezone，session/JVM 时区不一致时骨架整体偏移一天、与源表
 *       DATE 列 LEFT JOIN 恒失配（计数全 0）。本写法与 session/JVM 时区无关；</li>
 *   <li>窗口语义（对齐门店 2026-08-13 实时化）：day 骨架 = [sinceDate, asOfDate]
 *       （[今天-30, 今天]，共 31 天，含今日）；DATE 列（认可 recognition_date /
 *       浏览 view_date）按 [sinceDate, untilDate) 过滤（untilDate = 明天 0 点，
 *       覆盖今日全天）；timestamptz 列（收藏/礼物/分享 created_at）按
 *       [windowSince, windowUntil) 过滤（上界 = 请求时刻 now，实时）。</li>
 * </ul>
 */
public interface DancerStatsRepository extends Repository<DancerView, Long> {

    /**
     * 单日趋势行投影。注意：getDay() 列经骨架 {@code ::date} 收口后为 DATE 类型，
     * Hibernate 经 Jsr310Converters 正常转换为 LocalDate（勿移除该 cast，门店根因）。
     */
    interface DailyTrendRow {
        java.time.LocalDate getDay();
        /** 当日认可数（按 recognition_date 分组） */
        Long getRecognitioncount();
        /** 当日新增收藏数（按 created_at 分组，deleted=false） */
        Long getFavcount();
        /** 当日收到礼物价值（target_type='DANCER' 的 SUM(-delta)，已补零） */
        Long getPoints();
        /** 当日分享次数（event_type='SHARE' 主动分享事件） */
        Long getSharecount();
        /** 当日浏览数（含匿名，按日计数，全来源合计） */
        Long getViewcount();
        /** 当日来源=LIST 浏览数（「浏览来源」图主序列） */
        Long getViewlistcount();
        /** 当日来源=SHARE 浏览数（「浏览来源」图次序列） */
        Long getViewsharecount();
        /** 当日来源=SEARCH 浏览数（「浏览来源」图第三序列） */
        Long getViewsearchcount();
        /** 当日来源=VENUE 浏览数（门店详情页同城舞伴入口进入，2026-08-21 新增——「浏览来源」图第四序列） */
        Long getViewvenuecount();
    }

    /**
     * 舞伴「用户解锁信息」统计行投影（2026-08-21 追加，非时间序列——解锁低频
     * 离散事件，无按天趋势语义；按内容类型聚合累计值，驱动前端横向条形图）。
     * 注意：getter 名与 SQL alias（小写）逐字匹配（同 DailyTrendRow 惯例）。
     */
    interface UnlockStatRow {
        /** 内容类型（PointsGateTargetType.name()：DANCER_PHOTO / DANCER_CONTACT，可扩展） */
        String getTargettype();
        /** 累计解锁人次（qwt_points_unlocks 行数） */
        Long getUnlockcount();
        /** 累计解锁人数（按 user_id 去重） */
        Long getUniqueusers();
        /** 当前门槛积分（未软删且 cost>0 的被解锁目标的 MAX(cost)，无 = 0） */
        Integer getCost();
    }

    /**
     * 舞伴解锁信息聚合（2026-08-21 追加）：一条 DB 往返取回各内容类型的累计
     * 解锁人次/人数 + 当前门槛积分。
     * <ul>
     *   <li>照片（DANCER_PHOTO）target_id = qwt_dancer_photos.id，经子查询归集到
     *       当前舞伴；联系方式（DANCER_CONTACT）target_id = 舞伴 ID 直连；</li>
     *   <li>gate 只 join 未软删且 cost>0 的有效门槛（软删=清除门槛，历史解锁计数
     *       仍保留、cost 回落 0）；照片多张被解锁时 MAX(cost) = 最高解锁成本；</li>
     *   <li>仅返回解锁人次 &gt; 0 的类别（无解锁记录的类别不上行，前端空态=
     *       「暂无解锁记录」）；排序 = 人次降序（热度优先）。</li>
     * </ul>
     *
     * @param dancerId 舞伴 ID
     */
    @Query(value = """
            SELECT u.target_type AS targettype,
                   COUNT(*) AS unlockcount,
                   COUNT(DISTINCT u.user_id) AS uniqueusers,
                   COALESCE(MAX(g.cost), 0) AS cost
            FROM qwt_points_unlocks u
            LEFT JOIN qwt_points_gates g
                   ON g.target_type = u.target_type
                  AND g.target_id = u.target_id
                  AND g.deleted = false
                  AND g.cost > 0
            WHERE (u.target_type = 'DANCER_PHOTO'
                   AND u.target_id IN (SELECT p.id FROM qwt_dancer_photos p
                                       WHERE p.dancer_id = :dancerId))
               OR (u.target_type = 'DANCER_CONTACT' AND u.target_id = :dancerId)
            GROUP BY u.target_type
            ORDER BY unlockcount DESC
            """, nativeQuery = true)
    List<UnlockStatRow> countDancerUnlockStats(@Param("dancerId") Long dancerId);

    /**
     * 舞伴统计趋势 mega-query：一条 DB 往返取回 认可/收藏/礼物价值/分享/浏览
     * （含来源分列）八组按天时间序列。结构与门店 countDailyTrends 完全同构
     * （骨架 generate_series + 各源表 LEFT JOIN，天然补零）。
     *
     * @param dancerId     舞伴 ID
     * @param sinceDate    骨架起始（含，今天-30）
     * @param asOfDate     骨架结束（含，今天）
     * @param untilDate    DATE 列排他上界（今天+1，覆盖今日全天）
     * @param windowSince  timestamptz 列窗口下界（今天 0 点-30 天）
     * @param windowUntil  timestamptz 列窗口上界（请求时刻 now，实时）
     */
    @Query(value = """
            SELECT d.day,
                   COALESCE(r.cnt, 0) AS recognitioncount,
                   COALESCE(f.cnt, 0) AS favcount,
                   COALESCE(pt.cnt, 0) AS points,
                   COALESCE(s.cnt, 0) AS sharecount,
                   COALESCE(v.cnt, 0) AS viewcount,
                   COALESCE(v.list_cnt, 0) AS viewlistcount,
                   COALESCE(v.share_cnt, 0) AS viewsharecount,
                   COALESCE(v.search_cnt, 0) AS viewsearchcount,
                   COALESCE(v.venue_cnt, 0) AS viewvenuecount
            FROM (SELECT generate_series(CAST(:sinceDate AS timestamp), CAST(:asOfDate AS timestamp), interval '1 day')::date AS day) AS d
            LEFT JOIN (SELECT recognition_date AS day, COUNT(*) AS cnt
                       FROM qwt_dancer_recognitions
                       WHERE dancer_id = :dancerId AND deleted = false
                         AND recognition_date >= :sinceDate AND recognition_date < :untilDate
                       GROUP BY 1) r ON r.day = d.day
            LEFT JOIN (SELECT date_trunc('day', created_at)::date AS day, COUNT(*) AS cnt
                       FROM qwt_dancer_favorites
                       WHERE dancer_id = :dancerId AND deleted = false
                         AND created_at >= :windowSince AND created_at < :windowUntil
                       GROUP BY 1) f ON f.day = d.day
            LEFT JOIN (SELECT date_trunc('day', created_at)::date AS day, SUM(-delta) AS cnt
                       FROM qwt_points_transactions
                       WHERE target_type = 'DANCER' AND target_id = :dancerId AND delta < 0
                         AND created_at >= :windowSince AND created_at < :windowUntil
                       GROUP BY 1) pt ON pt.day = d.day
            LEFT JOIN (SELECT date_trunc('day', created_at)::date AS day, COUNT(*) AS cnt
                       FROM qwt_dancer_shares
                       WHERE dancer_id = :dancerId AND event_type = 'SHARE'
                         AND created_at >= :windowSince AND created_at < :windowUntil
                       GROUP BY 1) s ON s.day = d.day
            -- 2026-08-19：qwt_dancer_views 多来源（全量/LIST/SHARE/SEARCH/VENUE（2026-08-21 追加））从
            -- 多个独立子查询各扫一次收敛为单子查询 + FILTER 条件聚合（同一窗口 1 次扫描）——
            -- 来源是行内列，FILTER 语义与 COUNT 独立分组完全等价（视图扫描量大时省 IO）
            LEFT JOIN (
                SELECT view_date AS day,
                       COUNT(*) AS cnt,
                       COUNT(*) FILTER (WHERE source = 'LIST') AS list_cnt,
                       COUNT(*) FILTER (WHERE source = 'SHARE') AS share_cnt,
                       COUNT(*) FILTER (WHERE source = 'SEARCH') AS search_cnt,
                       COUNT(*) FILTER (WHERE source = 'VENUE') AS venue_cnt
                FROM qwt_dancer_views
                WHERE dancer_id = :dancerId
                  AND view_date >= :sinceDate AND view_date < :untilDate
                GROUP BY 1
            ) v ON v.day = d.day
            ORDER BY d.day
            """, nativeQuery = true)
    List<DailyTrendRow> countDancerDailyTrends(@Param("dancerId") Long dancerId,
                                               @Param("sinceDate") LocalDate sinceDate,
                                               @Param("asOfDate") LocalDate asOfDate,
                                               @Param("untilDate") LocalDate untilDate,
                                               @Param("windowSince") LocalDateTime windowSince,
                                               @Param("windowUntil") LocalDateTime windowUntil);
}
