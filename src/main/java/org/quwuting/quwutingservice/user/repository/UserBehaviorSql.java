package org.quwuting.quwutingservice.user.repository;

/**
 * 管理端「用户行为」的<b>事件级事实集</b>载体（2026-09-15，仅 ADMIN 消费；
 * docs/agents/35-dashboard-stats.md「用户行为轨迹与行为分析」节）。
 *
 * <h2>它是什么 / 与 {@link UserStatsSql} 的分工</h2>
 * <ul>
 *   <li>{@link UserStatsSql}：<b>用户范围谓词</b>（谁算真实用户）+ <b>日级事实集</b>
 *       （{@code (user_id, day)}——只回答「这天活跃没活跃」）；</li>
 *   <li>{@code UserBehaviorSql}（本类）：<b>事件级事实集</b>（{@code (user_id, event_type,
 *       event_day, event_time[, ref_id, detail_text])}——回答「做了什么、对谁做、什么时候做」）。
 *       轨迹、行为类型分布、活跃时段、行为宽度等一切「事件维度」的消费方都用这里。</li>
 * </ul>
 * 拆成两个类而不是塞进一个：两者的<b>消费方与列形状都不同</b>（日级只需去重计数、事件级要按
 * 类型/时刻分组），混在一起会让 {@link UserStatsSql} 变成一条谁都不敢改的巨型 SQL；
 * 但两者<b>同源</b>——都由 {@link UserBehaviorEvent} 声明、生成，门禁断言逐字相等。
 *
 * <h2>⚠️ 本类是「生成物」，不是手写 SQL（严禁在此即兴改表清单）</h2>
 * 三条常量的<b>唯一声明处</b>是 {@link UserBehaviorEvent}（行为事件目录：表 / 日列 / 口径档 /
 * 标签 / 关联对象一次声明）。本类只是它们落在 {@code @Query} 上所需的字面量载体
 * （注解值必须是编译期常量，无法运行时拼接，见 {@link UserBehaviorEvent} 类注释）。
 * <ul>
 *   <li><b>要加一个行为表</b>：改 {@link UserBehaviorEvent}，跑
 *       {@code ./mvnw -Dtest=UserBehaviorCatalogMirrorTest test}，按失败信息给出的期望文本
 *       更新本类（门禁是逐字 {@code equals} 断言，会直接指认哪一条不一致）；</li>
 *   <li><b>不要</b>在本类单独增删分支——只改这里会让门禁红，且新事件不会出现在轨迹里。</li>
 * </ul>
 *
 * <h2>列约定（消费方按此取值；投影接口 getter 名与 alias 逐字匹配）</h2>
 * <ul>
 *   <li>{@code event_type}：事件码（{@link UserBehaviorEvent#code()}），字面量直接写进 SQL，
 *       故运行时无需 join 目录表；</li>
 *   <li>{@code event_day}：<b>权威日</b>（业务日列优先，否则 {@code DATE(created_at)}）——
 *       与 {@link UserStatsSql#ACTIVE_FACT_UNION} 的 {@code day} 同源同义，可直接交叉验算；</li>
 *   <li>{@code event_time}：{@code created_at} 原值（<b>可空</b>）。仅供轨迹排序与时段分布；
 *       日列型事件的历史脏行可能没有时间戳，<b>禁把 null 折叠成 0 点</b>（否则凌晨会出现一条
 *       假尖峰），消费方按「时段分布只统计 {@code event_time IS NOT NULL} 的事件」处理；</li>
 *   <li>{@code ref_id} / {@code detail_text}：仅 {@link #EVENT_DETAIL_UNION} 携带——关联对象
 *       id（门店/舞伴/招工/公告，名称由服务层批量解析）与明细原文（渠道/标签/上报类型…）。
 *       <b>敏感列红线</b>：目录禁止把手机号 / 真实姓名 / 微信号 / openId 声明为这两列
 *       （门禁反向断言），轨迹回答「做过什么」，不回答「用户是谁的档案」。</li>
 * </ul>
 *
 * <h2>窗口与归因</h2>
 * 入参恒为 {@code :sinceDay}（{@code LocalDate}），并同时用作<b>事件时间下界</b>——
 * 所有分支的判据都在「日」的粒度上，故同一片段可服务「近 N 天轨迹」「≤90 天分布」等一切窗口。
 * 游客行（{@code user_id IS NULL}）一律排除（活跃是用户口径，游客浏览属流量）。
 * <p>
 * <b>MySQL 8 方言</b>（生产 RDS MySQL），勿在 PG 环境执行。
 */
public final class UserBehaviorSql {

    private UserBehaviorSql() {
    }

    /**
     * 全部事件的<b>事件级事实集</b>（主动 + 协作 + 信号 + 被动，18 个事件）——
     * 用户行为轨迹的完整性来源。
     * <p>
     * 为什么轨迹要含非活跃档：轨迹回答的是<b>叙事问题</b>「这个账号经历了什么」，
     * 而口径问题「他算不算活跃」由 {@code event_type} 对应的
     * {@link UserBehaviorEvent.Nature} 显式回答——把非活跃事件排除在<b>轨迹之外</b>，
     * 只会让「天天打开却从不互动」这类形态（审核/巡检号的典型画像）从界面上消失。
     * <b>口径不受影响</b>：一切活跃/留存统计只消费 {@link UserStatsSql#ACTIVE_FACT_UNION}
     * 或本类的 {@link #ACTIVE_EVENT_FACT_UNION}。
     */
    public static final String EVENT_FACT_UNION = """
            SELECT user_id, 'VENUE_VIEW' AS event_type, view_date AS event_day, created_at AS event_time FROM qwt_venue_views WHERE user_id IS NOT NULL AND view_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'DANCER_VIEW' AS event_type, view_date AS event_day, created_at AS event_time FROM qwt_dancer_views WHERE user_id IS NOT NULL AND view_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'VENUE_SHARE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_venue_shares WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'DANCER_SHARE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_dancer_shares WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_REACTION' AS event_type, reaction_date AS event_day, created_at AS event_time FROM qwt_venue_reactions WHERE user_id IS NOT NULL AND reaction_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'VENUE_FAVORITE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_favorites WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'DANCER_FAVORITE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_dancer_favorites WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'DANCER_DEMAND' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_demand_records WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_WATCH' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_venue_status_watchers WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'CROWD_REPORT' AS event_type, report_date AS event_day, created_at AS event_time FROM qwt_venue_crowd_reports WHERE user_id IS NOT NULL AND report_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'VENUE_FEEDBACK' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_venue_feedbacks WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_TAG' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_tag_interactions WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_CLAIM' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_venue_claims WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'CHECKIN' AS event_type, checkin_date AS event_day, created_at AS event_time FROM qwt_daily_checkins WHERE user_id IS NOT NULL AND checkin_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'MESSAGE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_messages WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'STATUS_REPORT' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_venue_status_reports WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'RECRUITMENT_CONTACT' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_recruitment_contacts WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'ANNOUNCEMENT_READ' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_announcement_reads WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)""";

    /**
     * 仅<b>主动行为</b>的事件级事实集（{@link UserBehaviorEvent.Nature#ACTIVE}，12 个）——
     * 与 {@link UserStatsSql#ACTIVE_FACT_UNION} <b>同成员、同日列</b>，差别只是多带
     * {@code event_type} 与 {@code event_time} 两列。
     * <p>
     * 一切「活跃口径下的事件维度统计」（活跃时段分布 / 人均活跃天数 / 行为宽度 / 类型分布）
     * 都必须用本常量，<b>不得</b>改用 {@link #EVENT_FACT_UNION} 再过滤——后者的成员集合含
     * 打卡等非活跃事件，一旦有人「顺手」用它做活跃统计，就会出现同屏两套活跃口径
     * （这正是 2026-09-15 修复过的那类错误决策）。门禁锁定两者成员差异。
     */
    public static final String ACTIVE_EVENT_FACT_UNION = """
            SELECT user_id, 'VENUE_VIEW' AS event_type, view_date AS event_day, created_at AS event_time FROM qwt_venue_views WHERE user_id IS NOT NULL AND view_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'DANCER_VIEW' AS event_type, view_date AS event_day, created_at AS event_time FROM qwt_dancer_views WHERE user_id IS NOT NULL AND view_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'VENUE_SHARE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_venue_shares WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'DANCER_SHARE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_dancer_shares WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_REACTION' AS event_type, reaction_date AS event_day, created_at AS event_time FROM qwt_venue_reactions WHERE user_id IS NOT NULL AND reaction_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'VENUE_FAVORITE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_favorites WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'DANCER_FAVORITE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_dancer_favorites WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'DANCER_DEMAND' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_demand_records WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_WATCH' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_venue_status_watchers WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'CROWD_REPORT' AS event_type, report_date AS event_day, created_at AS event_time FROM qwt_venue_crowd_reports WHERE user_id IS NOT NULL AND report_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'VENUE_FEEDBACK' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_venue_feedbacks WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_TAG' AS event_type, DATE(created_at) AS event_day, created_at AS event_time FROM qwt_tag_interactions WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)""";

    /**
     * 轨迹专用事实集 = {@link #EVENT_FACT_UNION} + 两列展示信息
     * （{@code ref_id} 关联对象 id、{@code detail_text} 明细原文）。
     * <p>
     * 单独一条而不是给 {@link #EVENT_FACT_UNION} 加列：聚合消费方（分布/时段/天数）不需要
     * 这两列，让它们跟着 UNION 一起扫是白花钱；而轨迹要的是「一条能读懂的时间线」。
     * 两条都由目录生成，成员集合天然一致（门禁断言）。
     */
    public static final String EVENT_DETAIL_UNION = """
            SELECT user_id, 'VENUE_VIEW' AS event_type, view_date AS event_day, created_at AS event_time, venue_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_venue_views WHERE user_id IS NOT NULL AND view_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'DANCER_VIEW' AS event_type, view_date AS event_day, created_at AS event_time, dancer_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_dancer_views WHERE user_id IS NOT NULL AND view_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'VENUE_SHARE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, venue_id AS ref_id, CAST(channel AS CHAR) AS detail_text FROM qwt_venue_shares WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'DANCER_SHARE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, dancer_id AS ref_id, CAST(channel AS CHAR) AS detail_text FROM qwt_dancer_shares WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_REACTION' AS event_type, reaction_date AS event_day, created_at AS event_time, venue_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_venue_reactions WHERE user_id IS NOT NULL AND reaction_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'VENUE_FAVORITE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, venue_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_favorites WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'DANCER_FAVORITE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, dancer_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_dancer_favorites WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'DANCER_DEMAND' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, dancer_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_demand_records WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_WATCH' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, venue_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_venue_status_watchers WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'CROWD_REPORT' AS event_type, report_date AS event_day, created_at AS event_time, venue_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_venue_crowd_reports WHERE user_id IS NOT NULL AND report_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'VENUE_FEEDBACK' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, venue_id AS ref_id, CAST(type AS CHAR) AS detail_text FROM qwt_venue_feedbacks WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_TAG' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, venue_id AS ref_id, CAST(tag AS CHAR) AS detail_text FROM qwt_tag_interactions WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'VENUE_CLAIM' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, venue_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_venue_claims WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'CHECKIN' AS event_type, checkin_date AS event_day, created_at AS event_time, CAST(NULL AS SIGNED) AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_daily_checkins WHERE user_id IS NOT NULL AND checkin_date >= CAST(:sinceDay AS DATE)
            UNION ALL SELECT user_id, 'MESSAGE' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, CAST(NULL AS SIGNED) AS ref_id, CAST(title AS CHAR) AS detail_text FROM qwt_messages WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'STATUS_REPORT' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, venue_id AS ref_id, CAST(type AS CHAR) AS detail_text FROM qwt_venue_status_reports WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'RECRUITMENT_CONTACT' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, recruitment_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_recruitment_contacts WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)
            UNION ALL SELECT user_id, 'ANNOUNCEMENT_READ' AS event_type, DATE(created_at) AS event_day, created_at AS event_time, announcement_id AS ref_id, CAST(NULL AS CHAR) AS detail_text FROM qwt_announcement_reads WHERE user_id IS NOT NULL AND created_at >= CAST(:sinceDay AS DATETIME)""";
}
