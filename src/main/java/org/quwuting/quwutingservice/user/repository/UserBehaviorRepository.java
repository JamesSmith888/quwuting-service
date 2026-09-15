package org.quwuting.quwutingservice.user.repository;

import org.quwuting.quwutingservice.user.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端「用户行为」统计仓库（2026-09-15，docs/agents/35-dashboard-stats.md
 * 「用户行为轨迹与行为分析」节；仅 ADMIN 消费）。
 * <p>
 * 独立于 {@code UserRepository} / {@code UserDailyStatsRepository} /
 * {@code UserRetentionRepository}（单职责：只承载<b>事件维度</b>的只读聚合，参考
 * {@code SpendStatsRepository} 独立统计仓先例）。继承空标记 {@link Repository} 而非
 * {@code JpaRepository}——不生成标准 CRUD，避免职责重叠。
 *
 * <h2>口径（唯一权威 = {@link UserStatsSql} + {@link UserBehaviorEvent}，禁止本类再定义）</h2>
 * <ul>
 *   <li><b>事实集</b>：一律引用 {@link UserBehaviorSql} 的生成常量，<b>禁止</b>在本类内联
 *       抄写表清单（门禁 {@code UserBehaviorCatalogMirrorTest} 断言引用关系）；</li>
 *   <li><b>活跃</b>口径的查询（{@link #listPlatformUserActiveDays} / {@link #listPlatformHourly}）
 *       只用 {@link UserBehaviorSql#ACTIVE_EVENT_FACT_UNION}（12 表用户主动行为，
 *       <b>不含登录自动打卡</b>）；轨迹与类型分布用 {@link UserBehaviorSql#EVENT_FACT_UNION}
 *       （全档，口径由 {@link UserBehaviorEvent.Nature} 标注，服务层与前端都按档位展示）；</li>
 *   <li><b>用户范围</b>：平台级统计一律 {@code JOIN qwt_users} + {@link UserStatsSql#USER_SCOPE}
 *       （剔 ADMIN 运营号 / {@code test_} 开发号 / 微信审核账号）；</li>
 *   <li><b>单用户读取不做用户范围过滤</b>（{@link #listTimeline} / {@link #countTimeline} /
 *       {@link #listUserEvents}）：入口列表已按口径过滤，用户详情/轨迹本身保留可见性
 *       （与 2026-09-14「明细端点不做用户表口径过滤」判据同族）——运营需要能对任意账号
 *       做取证式查看，包括被标记的账号。</li>
 * </ul>
 *
 * <h2>时间语义（本仓最容易踩错的地方）</h2>
 * <ul>
 *   <li>{@code event_day} = <b>权威日</b>（业务日列优先，与 {@link UserStatsSql#ACTIVE_FACT_UNION}
 *       的 {@code day} 同源）——一切「天数」类统计按它分组；</li>
 *   <li>{@code event_time} = {@code created_at} 原值，<b>可空</b>（日列型事件的历史脏行没有
 *       时间戳）。轨迹用 {@code COALESCE(event_time, 日 0 点)} 排序与展示（降级但不丢行）；
 *       时段分布<b>只统计 {@code event_time IS NOT NULL}</b> 的事件——把 null 折叠成 0 点会
 *       在凌晨造出一条假尖峰（宁缺勿造）。</li>
 * </ul>
 *
 * <b>MySQL 8 方言</b>（{@code HOUR()} / {@code CAST(... AS SIGNED)}；生产 RDS MySQL），
 * <b>勿在 PG 环境执行</b>（同 {@code UserDailyStatsRepository}）。
 */
public interface UserBehaviorRepository extends Repository<User, Long> {

    // ── 投影（getter 名与 SQL alias 逐字匹配，同仓惯例） ─────────────────────────

    /** 轨迹行投影（单用户，事件级） */
    interface TimelineRow {
        /** 事件码（{@link UserBehaviorEvent#code()}） */
        String getEventType();
        /** 权威事件日 */
        LocalDate getEventDay();
        /** 事件时刻（可为 null——日列型事件的历史脏行） */
        LocalDateTime getEventTime();
        /** 关联对象 id（门店/舞伴/招工/公告；无 = null） */
        Long getRefId();
        /** 明细原文（渠道/标签/上报类型/消息标题；无 = null） */
        String getDetailText();
        /** 排序与展示时间 = COALESCE(event_time, 日 0 点)，恒非空 */
        LocalDateTime getHappenedAt();
    }

    /** 单用户「事件类型 × 日 × 小时」计数行（画像的分布/时段/逐日序列共用一条查询） */
    interface UserEventRow {
        /** 事件码 */
        String getEventType();
        /** 权威事件日 */
        LocalDate getEventDay();
        /** 事件小时（0~23；{@code event_time} 为 null 时也是 null——不入时段分布） */
        Integer getHour();
        /** 事件条数 */
        Long getCnt();
        /** 该组内最近一次事件时刻（可为 null） */
        LocalDateTime getLastAt();
    }

    /** 平台级「用户 × 事件类型」计数行（行为类型分布 + 行为宽度 + 人均频次的数据源） */
    interface UserTypeRow {
        Long getUserId();
        String getEventType();
        /** 事件条数 */
        Long getCnt();
        /** 该用户在该事件类型上的<b>去重天数</b> */
        Long getDays();
    }

    /** 平台级「用户 × 去重天数」行（活跃天数 / 打开天数） */
    interface UserDayRow {
        Long getUserId();
        Long getDays();
    }

    /** 平台级小时分布行 */
    interface HourRow {
        Integer getHour();
        /** 事件条数 */
        Long getEvents();
        /** 涉及的去重用户数 */
        Long getUsers();
    }

    /** 真实用户注册日行（分层归一的「可用天数」分母来源） */
    interface UserJoinedRow {
        Long getUserId();
        LocalDate getJoinedDay();
    }

    // ── 单用户：轨迹（一条时间线） ──────────────────────────────────────────────

    /**
     * 用户行为轨迹（时间倒序，单一 SQL 跨 18 个事件源合并）。
     * <p>
     * 事实集 = {@link UserBehaviorSql#EVENT_DETAIL_UNION}（全部口径档 + 关联对象/明细）；
     * 排序键 = {@code happenedAt}（非空，时间戳缺失的老行按其「日 0 点」降级定位，
     * <b>不丢行、不伪造成当前时间</b>）。
     * <p>
     * <b>ORDER BY 里为什么写 {@code e.event_type} 而不是 {@code e.eventType}（2026-09-15 修复）</b>：
     * 派生表 {@code e} 的列名取自内层 SELECT 的<b>列名</b>——事实集常量里全是下划线风格
     * （{@code event_type} / {@code event_day} / …），而 {@code AS eventType} 只是<b>本层</b>的
     * 投影别名。带表前缀的引用（{@code e.eventType}）只解析派生表的真实列名，<b>不参与</b>
     * select 别名的解析 ⇒ MySQL 报 {@code Unknown column 'e.eventType' in 'order clause'}（1054）。
     * 同理 {@code happenedAt} 能出现在 ORDER BY 里，正因为它<b>不带前缀</b>（无前缀 ⇒ 走别名解析）。
     * 该错误曾与「{@code reason} 列不存在」前后脚出现：前者在 field list 阶段就先炸，
     * 把本错误挡在后面，修掉前一个后才暴露（同一 SQL 的两个独立缺陷）。
     * 门禁 {@code UserBehaviorCatalogMirrorTest#aliasReferencesResolveToFactColumns} 断言
     * 本仓所有 {@code e.<列>} 引用都落在事实集产出列集合内。
     *
     * @param eventType 事件码过滤（null = 全部；由服务层用
     *                  {@link UserBehaviorEvent#byCode} 校验，非法码在服务层被拒绝而非静默忽略）
     * @param limit     返回上限（服务层钳制 20~200）
     */
    @Query(value = """
            SELECT e.event_type AS eventType,
                   e.event_day AS eventDay,
                   e.event_time AS eventTime,
                   e.ref_id AS refId,
                   e.detail_text AS detailText,
                   COALESCE(e.event_time, CAST(e.event_day AS DATETIME)) AS happenedAt
            FROM (""" + " " + UserBehaviorSql.EVENT_DETAIL_UNION + " " + """
            ) e
            WHERE e.user_id = :userId
              AND (:eventType IS NULL OR e.event_type = :eventType)
            ORDER BY happenedAt DESC, e.event_type
            LIMIT :limit
            """, nativeQuery = true)
    List<TimelineRow> listTimeline(@Param("userId") Long userId,
                                   @Param("sinceDay") LocalDate sinceDay,
                                   @Param("eventType") String eventType,
                                   @Param("limit") int limit);

    /**
     * 单用户「事件类型 × 日 × 小时」计数（画像一次取全）：
     * 服务层由这一条查询派生 —— 类型分布、逐日序列、活跃/打开天数、近 7 日与上一个 7 日对比、
     * 活跃时段直方图。刻意不做成 5 条查询：单用户体量小（≤ 类型数 × 窗口天数 行），
     * 一次往返换全部维度（性能第一约束 = 最少 DB 往返，见 29-performance）。
     * <p>
     * 它同时也是<b>轨迹条数的唯一来源</b>（{@code Σcnt} 即窗口内该用户的事件总数）——
     * 故不再单设 count 查询：同一事实集两条 SQL 各算一次总数，正是「两个数字慢慢对不上」的
     * 经典开端（与 {@link #listTimeline} 同参同窗口，两处必然自洽）。
     */
    @Query(value = """
            SELECT e.event_type AS eventType,
                   e.event_day AS eventDay,
                   HOUR(e.event_time) AS hour,
                   COUNT(*) AS cnt,
                   MAX(e.event_time) AS lastAt
            FROM (""" + " " + UserBehaviorSql.EVENT_FACT_UNION + " " + """
            ) e
            WHERE e.user_id = :userId
            GROUP BY e.event_type, e.event_day, HOUR(e.event_time)
            """, nativeQuery = true)
    List<UserEventRow> listUserEvents(@Param("userId") Long userId,
                                      @Param("sinceDay") LocalDate sinceDay);

    // ── 平台级：行为统计分析 ────────────────────────────────────────────────────

    /**
     * 平台级「用户 × 事件类型」计数（窗口内）——行为类型分布（参与人数/次数）、
     * 行为宽度（用户参与了几类事件）、人均行为次数的数据源。
     * <p>
     * 用 {@link UserBehaviorSql#EVENT_FACT_UNION}（全档）：<b>类型分布是叙事工具，
     * 档位由 {@code eventType} 在服务层映射回 {@link UserBehaviorEvent.Nature} 后再分组</b>
     * ——这样「打开/打卡」这类非活跃事件会显式出现在「打开信号」分组里，
     * 而不是被悄悄算进活跃（2026-09-15 的错误决策正是后者）。
     */
    @Query(value = """
            SELECT e.user_id AS userId,
                   e.event_type AS eventType,
                   COUNT(*) AS cnt,
                   COUNT(DISTINCT e.event_day) AS days
            FROM (""" + " " + UserBehaviorSql.EVENT_FACT_UNION + " " + """
            ) e
            JOIN qwt_users u ON u.id = e.user_id
            WHERE""" + " " + UserStatsSql.USER_SCOPE + " " + """
            GROUP BY e.user_id, e.event_type
            """, nativeQuery = true)
    List<UserTypeRow> listPlatformUserTypes(@Param("sinceDay") LocalDate sinceDay);

    /**
     * 平台级「用户 → 窗口内活跃天数」（跨事件类型去重）——活跃分层的分母。
     * <p>
     * 只能单独一条查询：按 ({@code user,type}) 分组的天数是<b>各类型内部</b>的去重，
     * 直接相加会把同一天做过两类事件的人重复计入（这正是「人均活跃天数」最容易算错的地方）。
     * 口径 = {@link UserBehaviorSql#ACTIVE_EVENT_FACT_UNION}（<b>不含登录自动打卡</b>）。
     */
    @Query(value = """
            SELECT e.user_id AS userId,
                   COUNT(DISTINCT e.event_day) AS days
            FROM (""" + " " + UserBehaviorSql.ACTIVE_EVENT_FACT_UNION + " " + """
            ) e
            JOIN qwt_users u ON u.id = e.user_id
            WHERE""" + " " + UserStatsSql.USER_SCOPE + " " + """
            GROUP BY e.user_id
            """, nativeQuery = true)
    List<UserDayRow> listPlatformUserActiveDays(@Param("sinceDay") LocalDate sinceDay);

    /**
     * 平台级「用户 → 窗口内某单一事件类型的天数」——通用单类型天数查询
     * （当前用途 = 传入 {@code CHECKIN} 取「打开天数」，支撑「仅打开无行为」分层的识别：
     * 这类账号是审核/巡检号的典型画像，也是运营最需要看见的一类）。
     *
     * @param eventType 事件码（必填；服务层用 {@link UserBehaviorEvent#code()} 而非手写字符串）
     */
    @Query(value = """
            SELECT e.user_id AS userId,
                   COUNT(DISTINCT e.event_day) AS days
            FROM (""" + " " + UserBehaviorSql.EVENT_FACT_UNION + " " + """
            ) e
            JOIN qwt_users u ON u.id = e.user_id
            WHERE""" + " " + UserStatsSql.USER_SCOPE + " " + """
              AND e.event_type = :eventType
            GROUP BY e.user_id
            """, nativeQuery = true)
    List<UserDayRow> listPlatformUserEventDays(@Param("sinceDay") LocalDate sinceDay,
                                               @Param("eventType") String eventType);

    /**
     * 平台级活跃时段分布（24 小时刻度，{@code hour} 为 0~23）。
     * <p>
     * <b>只统计 {@code event_time IS NOT NULL} 的事件</b>——日列型事件的历史脏行没有时间戳，
     * 折叠成 0 点会在凌晨造出假尖峰（宁缺勿造；缺的那部分在响应里以
     * {@code timedOutEvents} 显式交代，见 DTO）。口径 = 主动行为（不含打卡）。
     */
    @Query(value = """
            SELECT HOUR(e.event_time) AS hour,
                   COUNT(*) AS events,
                   COUNT(DISTINCT e.user_id) AS users
            FROM (""" + " " + UserBehaviorSql.ACTIVE_EVENT_FACT_UNION + " " + """
            ) e
            JOIN qwt_users u ON u.id = e.user_id
            WHERE""" + " " + UserStatsSql.USER_SCOPE + " " + """
              AND e.event_time IS NOT NULL
            GROUP BY HOUR(e.event_time)
            ORDER BY HOUR(e.event_time)
            """, nativeQuery = true)
    List<HourRow> listPlatformHourly(@Param("sinceDay") LocalDate sinceDay);

    /**
     * 全部真实用户的注册日（窗口内 + 窗口前注册的老用户都要，用于「可用天数」归一）。
     * <p>
     * <b>为什么需要它</b>：活跃分层若按「绝对活跃天数」切档，会把窗口内新注册的用户
     * 系统性地判成「低频/沉默」——注册 2 天的人不可能有 8 个活跃日。分层的分母必须是
     * 「该用户在窗口内<b>可用</b>的天数」，与留存报表「未到期 ≠ 0%」是同一类判据
     * （2026-09-15 已因此栽过一次，见 docs/agents/35）。
     */
    @Query(value = """
            SELECT u.id AS userId, DATE(u.created_at) AS joinedDay
            FROM qwt_users u
            WHERE""" + " " + UserStatsSql.USER_SCOPE + " ", nativeQuery = true)
    List<UserJoinedRow> listRealUserJoinedDays();
}
