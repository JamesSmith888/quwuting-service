package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 管理端「用户行为统计分析」响应（2026-09-15，
 * {@code GET /admin/users/behavior-analysis?days=}；仅 ADMIN）。
 * admin-web「行为分析」页数据源（挂在资料协作域下，从用户列表页进入）——
 * 回答运营的三个问题：<b>用户在做什么</b>（类型分布）、<b>什么时候在做</b>（时段）、
 * <b>谁还在做、谁已经不动了</b>（活跃分层 / 行为宽度）。
 *
 * <h2>口径（唯一权威 = {@code UserStatsSql} + {@code UserBehaviorEvent}）</h2>
 * <ul>
 *   <li><b>分母与用户范围</b>：全部统计只覆盖 {@code USER_SCOPE} 的真实用户——
 *       已剔除 ADMIN 运营号 / {@code test_} 开发联号 / 微信审核账号；</li>
 *   <li><b>「活跃」= 主动行为</b>（{@code Nature#ACTIVE}，12 表），<b>不含登录自动打卡</b>；
 *       {@link #byType} 里打卡会以「系统信号」档显式出现，而不是被算进活跃；</li>
 *   <li>{@link #summary}.{@link Summary#activeDaysSum} 是「逐用户活跃天数之和」——
 *       人均活跃天数 = 它 / 活跃用户数，<b>由前端派生</b>（比率不下发）。</li>
 * </ul>
 *
 * <h2>活跃分层的分母归一（关键口径决策，勿回退）</h2>
 * 分层不用「绝对活跃天数」而用「<b>占可用天数的比例</b>」：{@code 可用天数 =
 * min(窗口天数, 注册至今)}。理由与留存报表「未到期 ≠ 0%」完全同族（2026-09-15）：
 * 按绝对值切档会把窗口内<b>刚注册</b>的用户系统性地判成「沉默/低频」——
 * 注册两天的人不可能有八个活跃日，那不是用户不活跃，是观测期不够。
 * 故可用天数 ≤ {@code NEW_USER_AVAILABLE_DAYS} 的用户单列一层，不参与比例分档。
 *
 * <h2>口径自证</h2>
 * {@link #scopeAudit}（全部账号 = 有效用户 + 运营/开发号 + 微信审核号，三项互斥）与
 * 留存分析页同源（复用同一查询），把「已剔除管理员与审核号」这条不可见约定
 * 变成页面上可当场验算的一行等式。
 */
public record AdminUserBehaviorAnalysisResponse(
        /** 生效窗口天数（回显服务端钳制后的值，7~90） */
        int days,
        /** 汇总盘子 */
        Summary summary,
        /** 行为类型统计（<b>全目录</b>，含窗口内 0 次的类型——保证类型清单稳定可发现，前端过滤） */
        List<TypeStat> byType,
        /** 活跃分层（互斥且完备：所有真实用户恰好落入一层） */
        List<Segment> segments,
        /** 行为宽度分布（用户参与了几类主动行为） */
        List<WidthBucket> width,
        /** 活跃时段直方图：固定 24 格（下标 = 小时 0~23），值为主动行为条数 */
        List<Long> hourly,
        /** 活跃时段：同上，值为去重用户数 */
        List<Long> hourlyUsers,
        /** 主动行为中「无精确时刻」的条数（未计入 {@link #hourly}；宁缺勿造，见 UserBehaviorSql） */
        long timedOutActiveEvents,
        /** 口径自证（账号盘子漏斗；与留存分析页同一查询，展示层渲染为一行加法） */
        AdminUserRetentionResponse.ScopeAudit scopeAudit
) {

    /**
     * 汇总（全部为原始计数；比率/人均由前端派生）。
     */
    public record Summary(
            /** 真实用户总数（分母，全量历史注册） */
            long totalUsers,
            /** 窗口内有主动行为的去重用户数 */
            long activeUsers,
            /** 窗口内主动行为条数 */
            long activeEvents,
            /** 窗口内逐用户「活跃天数」之和（人均活跃天数的分子） */
            long activeDaysSum
    ) {
    }

    /**
     * 单类型平台统计（<b>全目录下发</b>，0 也保留一行）。
     */
    public record TypeStat(
            /** 事件码 */
            String code,
            /** 事件中文名 */
            String label,
            /** 分类中文名 */
            String categoryLabel,
            /** 口径档码 */
            String nature,
            /** 口径档中文名 */
            String natureLabel,
            /** 口径档释义（解释为何计入/不计入活跃） */
            String natureHint,
            /** 窗口内做过该事件的去重用户数 */
            long users,
            /** 窗口内该事件条数 */
            long events
    ) {
    }

    /**
     * 活跃分层（互斥完备；判据在服务层，前端只渲染）。
     * <p>
     * {@code ratioLabel} 之类的阈值**不在前端**：分层是口径，不是展示选择——
     * 前端若自己按比例分档，改一次阈值就会与后端统计图互相矛盾。
     */
    public record Segment(
            /** 分层码（HIGH / REGULAR / LOW / OPEN_ONLY / DORMANT / NEW） */
            String code,
            /** 分层名（如「高频活跃」） */
            String label,
            /** 判据说明（含阈值，便于运营当场核对） */
            String hint,
            /** 落入该层的用户数 */
            long users
    ) {
    }

    /**
     * 行为宽度分布：窗口内参与了几类主动行为（口径 = 主动行为事件类型数）。
     * <p>
     * 桶界（1 / 2 / 3-4 / 5+）是口径而非展示选择，故由服务端定档、只下发文案与人数
     * （前端不得自行按阈值分桶，否则改阈值时图表之间会自相矛盾）。
     */
    public record WidthBucket(
            /** 桶名（如「1 类」「5 类及以上」） */
            String label,
            /** 落入该桶的用户数（只统计窗口内有主动行为的用户；无行为者见分层「沉默」层） */
            long users
    ) {
    }
}
