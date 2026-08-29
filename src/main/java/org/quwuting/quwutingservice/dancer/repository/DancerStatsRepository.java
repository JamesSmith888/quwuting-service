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
        /** 当日联系方式需求数（qwt_demand_records 按 created_at 分组，2026-08-26 追加——「需求趋势」图用） */
        Long getDemandcount();
    }

    /**
     * 舞伴「用户解锁信息」统计行投影（2026-08-21 追加；2026-08-26 补免费解锁细分，
     * 非时间序列——解锁低频离散事件，无按天趋势语义；按内容类型聚合累计值，
     * 驱动前端横向条形图）。注意：getter 名与 SQL alias（小写）逐字匹配
     * （同 DailyTrendRow 惯例）。
     */
    interface UnlockStatRow {
        /** 内容类型（PointsGateTargetType.name()：DANCER_PHOTO / DANCER_VIDEO / DANCER_CONTACT，可扩展） */
        String getTargettype();
        /** 累计解锁人次（qwt_points_unlocks 行数） */
        Long getUnlockcount();
        /** 累计解锁人数（按 user_id 去重） */
        Long getUniqueusers();
        /** 当前门槛积分（未软删且 cost>0 的被解锁目标的 MAX(cost)，无 = 0） */
        Integer getCost();
        /** 免费解锁人次（transaction_id IS NULL——无门槛免费 / 每日首免，2026-08-26 追加） */
        Long getFreecount();
    }

    /**
     * 舞伴全量历史累计指标行投影（2026-08-22 追加；2026-08-26 补需求计数，
     * 非时间序列——「累计数据」汇总卡用：总收藏数/总浏览数等常见指标一览）。
     * getter 名与 SQL alias（小写）逐字匹配（同 DailyTrendRow 惯例）。
     */
    interface TotalsRow {
        /** 累计认可数（每日一记，deleted=false） */
        Long getRecognitioncount();
        /** 总收藏数（deleted=false） */
        Long getFavoritecount();
        /** 累计浏览数（PV 含匿名） */
        Long getViewcount();
        /** 累计分享数（event_type='SHARE' 主动分享事件） */
        Long getSharecount();
        /** 收到礼物价值累计（SUM(-delta)） */
        Long getPointstotal();
        /** 累计需求人次（qwt_demand_records 行数，2026-08-26 追加） */
        Long getDemandcount();
    }

    /**
     * 舞伴解锁信息聚合（2026-08-21 追加；2026-08-26 补短视频分支）：一条 DB
     * 往返取回各内容类型的累计解锁人次/人数 + 当前门槛积分。
     * <ul>
     *   <li>照片（DANCER_PHOTO）target_id = qwt_dancer_photos.id（kind='PHOTO'），
     *       短视频（DANCER_VIDEO）target_id = qwt_dancer_photos.id（kind='VIDEO'，
     *       2026-08-26 修复：旧 SQL 缺视频分支导致短视频解锁不入统计）；
     *       二者均经子查询归集到当前舞伴；联系方式（DANCER_CONTACT）target_id =
     *       舞伴 ID 直连；</li>
     *   <li>gate 只 join 未软删且 cost>0 的有效门槛（软删=清除门槛，历史解锁计数
     *       仍保留、cost 回落 0）；照片多张被解锁时 MAX(cost) = 最高解锁成本；</li>
     *   <li>freecount = transaction_id IS NULL 的免费解锁人次（无门槛免费 / 每日
     *       首免——V42 起 DROP NOT NULL，免费解锁不写扣费流水，2026-08-26 追加）；</li>
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
                   COALESCE(MAX(g.cost), 0) AS cost,
                   COUNT(*) FILTER (WHERE u.transaction_id IS NULL) AS freecount
            FROM qwt_points_unlocks u
            LEFT JOIN qwt_points_gates g
                   ON g.target_type = u.target_type
                  AND g.target_id = u.target_id
                  AND g.deleted = false
                  AND g.cost > 0
            WHERE (u.target_type = 'DANCER_PHOTO'
                   AND u.target_id IN (SELECT p.id FROM qwt_dancer_photos p
                                       WHERE p.dancer_id = :dancerId AND p.kind = 'PHOTO'))
               OR (u.target_type = 'DANCER_VIDEO'
                   AND u.target_id IN (SELECT p.id FROM qwt_dancer_photos p
                                       WHERE p.dancer_id = :dancerId AND p.kind = 'VIDEO'))
               OR (u.target_type = 'DANCER_CONTACT' AND u.target_id = :dancerId)
            GROUP BY u.target_type
            ORDER BY unlockcount DESC
            """, nativeQuery = true)
    List<UnlockStatRow> countDancerUnlockStats(@Param("dancerId") Long dancerId);

    /**
     * 舞伴某内容类型的解锁记录明细（2026-08-26 新增，「解锁信息」条形点击 → 详情页）。
     * <ul>
     *   <li>JOIN qwt_users 取解锁用户公开资料（昵称/头像），软删用户排除
     *       （对齐 gifters 先例）；</li>
     *   <li>照片/视频 LEFT JOIN qwt_dancer_photos 取媒体描述列（sort_order 序号 /
     *       duration_seconds 时长；联系方式无媒体行，该列为 null）；</li>
     *   <li>LEFT JOIN qwt_points_transactions 取本次花费（免费解锁 transaction_id
     *       为 null → COALESCE 兜底 0；花费 = -delta）；</li>
     *   <li>target_type 归属口径与 {@link #countDancerUnlockStats} 完全一致
     *       （照片/视频经 kind 归集到舞伴相册、联系方式直连舞伴 ID）；</li>
     *   <li>排序 = 解锁时间倒序、id 倒序（最新解锁在前；解锁低频无分页）。</li>
     * </ul>
     * 返回 Object[]：{userId, nickname, avatarUrl, createdAt, sortOrder,
     * durationSeconds, cost}。
     *
     * @param dancerId   舞伴 ID
     * @param targetType PointsGateTargetType.name()（DANCER_PHOTO / DANCER_VIDEO / DANCER_CONTACT）
     */
    @Query(value = """
            SELECT u.id AS userId,
                   u.nickname AS nickname,
                   u.avatar_url AS avatarUrl,
                   ul.created_at AS createdAt,
                   p.sort_order AS sortOrder,
                   p.duration_seconds AS durationSeconds,
                   COALESCE(-t.delta, 0) AS cost
            FROM qwt_points_unlocks ul
            JOIN qwt_users u ON u.id = ul.user_id AND u.deleted = false
            LEFT JOIN qwt_dancer_photos p ON p.id = ul.target_id
                 AND ul.target_type IN ('DANCER_PHOTO', 'DANCER_VIDEO')
            LEFT JOIN qwt_points_transactions t ON t.id = ul.transaction_id
            WHERE ul.target_type = :targetType
              AND ((ul.target_type = 'DANCER_PHOTO'
                    AND ul.target_id IN (SELECT p2.id FROM qwt_dancer_photos p2
                                         WHERE p2.dancer_id = :dancerId AND p2.kind = 'PHOTO'))
                OR (ul.target_type = 'DANCER_VIDEO'
                    AND ul.target_id IN (SELECT p3.id FROM qwt_dancer_photos p3
                                         WHERE p3.dancer_id = :dancerId AND p3.kind = 'VIDEO'))
                OR (ul.target_type = 'DANCER_CONTACT' AND ul.target_id = :dancerId))
            ORDER BY ul.created_at DESC, ul.id DESC
            """, nativeQuery = true)
    List<Object[]> findDancerUnlocks(@Param("dancerId") Long dancerId,
                                     @Param("targetType") String targetType);

    /**
     * 舞伴全量历史累计指标聚合（2026-08-22 追加；2026-08-26 补需求计数，「累计
     * 数据」汇总卡用）：一条 DB 往返取回 认可/收藏/浏览/分享/礼物价值/需求 六类
     * 全量累计值。六组标量子查询各扫一次目标表（与趋势 mega-query 同源表同口径，
     * 仅去掉窗口过滤取全量）——统计页打开时该查询与趋势查询并行两趟，但均为索引
     * 覆盖扫描，条目量级下开销可接受。
     *
     * @param dancerId 舞伴 ID
     */
    @Query(value = """
            SELECT (SELECT COUNT(*) FROM qwt_dancer_recognitions
                    WHERE dancer_id = :dancerId AND deleted = false) AS recognitioncount,
                   (SELECT COUNT(*) FROM qwt_dancer_favorites
                    WHERE dancer_id = :dancerId AND deleted = false) AS favoritecount,
                   (SELECT COUNT(*) FROM qwt_dancer_views
                    WHERE dancer_id = :dancerId) AS viewcount,
                   (SELECT COUNT(*) FROM qwt_dancer_shares
                    WHERE dancer_id = :dancerId AND event_type = 'SHARE') AS sharecount,
                   (SELECT COALESCE(SUM(-delta), 0) FROM qwt_points_transactions
                    WHERE target_type = 'DANCER' AND target_id = :dancerId AND delta < 0) AS pointstotal,
                   (SELECT COUNT(*) FROM qwt_demand_records
                    WHERE dancer_id = :dancerId) AS demandcount
            """, nativeQuery = true)
    TotalsRow countDancerTotals(@Param("dancerId") Long dancerId);

    /**
     * 舞伴统计趋势 mega-query：一条 DB 往返取回 认可/收藏/礼物价值/分享/浏览
     * （含来源分列）+ 需求 九组按天时间序列。结构与门店 countDailyTrends 完全同构
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
                   COALESCE(v.venue_cnt, 0) AS viewvenuecount,
                   COALESCE(dm.cnt, 0) AS demandcount
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
            -- 2026-08-26：需求（qwt_demand_records.created_at 按天分组，timestamptz
            -- 上界 = 请求时刻 now，与收藏/礼物/分享同窗口）——「需求趋势」图数据源
            LEFT JOIN (SELECT date_trunc('day', created_at)::date AS day, COUNT(*) AS cnt
                       FROM qwt_demand_records
                       WHERE dancer_id = :dancerId
                         AND created_at >= :windowSince AND created_at < :windowUntil
                       GROUP BY 1) dm ON dm.day = d.day
            ORDER BY d.day
            """, nativeQuery = true)
    List<DailyTrendRow> countDancerDailyTrends(@Param("dancerId") Long dancerId,
                                               @Param("sinceDate") LocalDate sinceDate,
                                               @Param("asOfDate") LocalDate asOfDate,
                                               @Param("untilDate") LocalDate untilDate,
                                               @Param("windowSince") LocalDateTime windowSince,
                                               @Param("windowUntil") LocalDateTime windowUntil);

    /**
     * 舞伴「需求热度」统计行投影（2026-08-26 追加，非时间序列——需求按服务类别
     * 聚合累计值，驱动前端横向条形图）。getter 名与 SQL alias（小写）逐字匹配
     * （同 DailyTrendRow 惯例）。
     */
    interface DemandStatRow {
        /** 服务类别（DancerServiceCategory.name()：PACKAGE / DANCE / ONLINE_CHAT / OTHER） */
        String getCategory();
        /** 需求次数（该类别服务被选中次数） */
        Long getDemandcount();
        /** 提出需求的去重人数（按 user_id） */
        Long getUniqueusers();
    }

    /**
     * 舞伴「排名热度」输入计数器行投影（2026-08-29 追加，非时间序列——
     * 列表 HOT 排序公式（DancerHeatWeights）的单舞伴输入快照，
     * 驱动统计页「排名热度」卡 = 排序口径公开化）。getter 名与 SQL alias
     * （小写）逐字匹配（同 DailyTrendRow 惯例）。
     */
    interface HeatCountersRow {
        /** 近7天联系方式解锁数（排序主导信号输入） */
        Long getUnlockcontact7d();
        /** 近30天联系方式解锁数（排序 tie-break 一级输入） */
        Long getUnlockcontact30d();
        /** 近7天认可数（排序平滑项输入） */
        Long getRecognition7d();
        /** 近30天收藏数（排序 tie-break 二级输入） */
        Long getFav30d();
        /** 舞伴创建时刻（新舞伴保护期判定输入） */
        java.time.LocalDateTime getCreatedat();
        /** 最新 PUBLIC 媒体 created_at（资料新鲜度判定输入，未上传过 = null） */
        java.time.LocalDateTime getAlbummax();
        /** 联系方式最近更新时刻（资料新鲜度判定输入，未更新过 = null） */
        java.time.LocalDateTime getContactupdatedat();
    }

    /**
     * 舞伴「排名热度」输入计数器聚合（2026-08-29 追加，统计页「排名热度」卡数据源）：
     * 一条 DB 往返取回排序公式全部输入（子查询各扫一次目标表 + 舞伴主行两列）。
     * 口径与 {@code DancerRepository#findPublicPage} 排序子查询逐项一致
     * （窗口锚点由服务层现算传入：since7d/since30d），保证「排序 = 展示」同源——
     * 对齐门店「列表排序与热度页统一」2026-08-08 先例。
     *
     * @param dancerId 舞伴 ID
     * @param since7d  近7天窗口下界（now-7d，服务层现算）
     * @param since30d 近30天窗口下界（now-30d，服务层现算）
     */
    @Query(value = """
            SELECT (SELECT COUNT(*) FROM qwt_points_unlocks
                    WHERE target_type = 'DANCER_CONTACT' AND target_id = :dancerId
                      AND created_at >= :since7d) AS unlockcontact7d,
                   (SELECT COUNT(*) FROM qwt_points_unlocks
                    WHERE target_type = 'DANCER_CONTACT' AND target_id = :dancerId
                      AND created_at >= :since30d) AS unlockcontact30d,
                   (SELECT COUNT(*) FROM qwt_dancer_recognitions
                    WHERE dancer_id = :dancerId AND deleted = false
                      AND created_at >= :since7d) AS recognition7d,
                   (SELECT COUNT(*) FROM qwt_dancer_favorites
                    WHERE dancer_id = :dancerId AND deleted = false
                      AND created_at >= :since30d) AS fav30d,
                   d.created_at AS createdat,
                   (SELECT MAX(created_at) FROM qwt_dancer_photos
                    WHERE dancer_id = :dancerId AND status = 'PUBLIC'
                      AND deleted = false) AS albummax,
                   d.contact_updated_at AS contactupdatedat
            FROM qwt_dancers d
            WHERE d.id = :dancerId AND d.deleted = false
            """, nativeQuery = true)
    HeatCountersRow countDancerHeatCounters(@Param("dancerId") Long dancerId,
                                            @Param("since7d") LocalDateTime since7d,
                                            @Param("since30d") LocalDateTime since30d);

    /**
     * 舞伴需求热度聚合（2026-08-26 追加，「需求热度」横向条形图用）：一条 DB
     * 往返取回各服务类别的需求次数 / 去重人数。
     * <ul>
     *   <li>数据源 = qwt_demand_records（V42，获取联系方式前的需求选择，锚点记录
     *       只写一次）；service_ids 逗号串经 {@code string_to_array + unnest} 拆解
     *       JOIN qwt_dancer_services 取类别（业务写路径强制每需求恰好 1 项服务，
     *       历史脏数据多值时按服务逐条计数——同一需求跨类别的极端情况按条计，
     *       与「人次」语义一致）；</li>
     *   <li>服务恒存在（V42 起纯软删设计，无物理删除路径）；类别未知（脏数据）在
     *       service 层回退「其他」展示；</li>
     *   <li>排序 = 需求次数降序（热度优先），次数为 0 的类别不上行。</li>
     * </ul>
     *
     * @param dancerId 舞伴 ID
     */
    @Query(value = """
            SELECT s.category AS category,
                   COUNT(*) AS demandcount,
                   COUNT(DISTINCT d.user_id) AS uniqueusers
            FROM qwt_demand_records d
            CROSS JOIN LATERAL unnest(string_to_array(d.service_ids, ',')) AS sid
            JOIN qwt_dancer_services s ON s.id = CAST(sid AS bigint)
            WHERE d.dancer_id = :dancerId
            GROUP BY s.category
            ORDER BY demandcount DESC
            """, nativeQuery = true)
    List<DemandStatRow> countDancerDemandStats(@Param("dancerId") Long dancerId);
}
