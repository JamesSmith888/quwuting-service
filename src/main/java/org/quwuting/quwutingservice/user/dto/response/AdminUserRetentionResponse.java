package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理端「用户留存分析」响应（2026-09-15，GET /admin/users/retention?days=30；
 * 仅 ADMIN）。admin-web「留存分析」页数据源，一次往返返回汇总 + 逐日拆分 +
 * 留存曲线 + 批次矩阵四块。
 * <p>
 * <b>口径权威 = {@code UserStatsSql} + {@code UserRetentionRepository} 类注释</b>
 * （docs/agents/35-dashboard-stats.md）：
 * <ul>
 *   <li>有效用户 = 真实舞友（剔 ADMIN / {@code test_} 开发号 / 微信审核账号）；</li>
 *   <li>活跃 = 当日出现在 12 表用户主动行为（<b>不含每日打卡</b>——打卡是登录自动
 *       触发，只代表「打开过」；把它当活跃正是本次修复的错误决策）；</li>
 *   <li>批次 = 注册日；Dk 留存 = 注册后第 k 天<u>当天</u>有活跃（经典当日留存，
 *       非「k 天及以后」）；注册当日不计入留存（D0 恒 1，无信息量）。</li>
 * </ul>
 * <p>
 * <b>「窗口未到期」的表示（重要，勿改成 0）</b>：{@code d1/d3/d7/d14/d30} 是
 * {@code Long} 可空——{@code null} = 该批次到该偏移的天数还没走完，<b>不是</b>留存为 0。
 * 把未到期当 0% 会让最近几个批次在图上人为「崩盘」，是留存报表最经典的错误决策来源。
 * 前端对 null 渲染为「—」。
 * <p>
 * <b>比率不在本节下发</b>：留存率 = {@code retained / base}（曲线）、
 * {@code dK / size}（矩阵）——与 admin-web 既有先例一致（渗透率/占比等展示态比率
 * 由前端从原始计数派生，服务端只提供事实与「是否存在」的判定）。
 * <p>
 * <b>⚠️ 未到期字段必须显式序列化（2026-09-15 修复）</b>：本项目全局配置
 * {@code spring.jackson.default-property-inclusion=non_null}，会把 null 字段<b>整个从 JSON
 * 里删掉</b>；而「未到期 = null」正是本响应唯一的空值语义，一旦被删掉，前端读到
 * {@code undefined} 就会算出 {@code NaN%}（实测现象：矩阵显示「NaN%」，与「未到期」
 * 和「0% 无人回访」都无法区分）。故 {@link CohortItem} 显式标注
 * {@code @JsonInclude(ALWAYS)}，让 null 成为**协议的一部分**；前端另有兜底归一化
 * （缺失/null 一律按未到期渲染「—」），两侧同时成立才算修好。
 */
public record AdminUserRetentionResponse(
        /** 生效窗口天数（回显服务端钳制后的值，7~90） */
        int days,
        /** 近 7 日活跃汇总 */
        Summary summary,
        /** 口径自证：账号盘子漏斗（各项互斥，相加 = 全部未软删账号） */
        ScopeAudit scopeAudit,
        /** 近 N 天逐日活跃拆分（含今日，骨架补零） */
        List<DailyPoint> daily,
        /** 留存曲线（加权：所有已到期批次合并；dayOffset 由 1 递增到已到期上限） */
        List<CurvePoint> curve,
        /** 批次留存矩阵（按批次日升序；只含有注册的批次） */
        List<CohortItem> cohorts
) {

    /**
     * 口径自证（账号盘子漏斗）：把「剔除管理员与微信审核账号」这条不可见约定变成可当场验算的
     * 一行等式——{@code totalAccounts = summary.totalUsers + opsExcluded + reviewExcluded}
     * （三项互斥，审查号中属运营/开发号的归入 {@code opsExcluded}）。
     */
    public record ScopeAudit(
            /** 全部未软删账号数 */
            long totalAccounts,
            /** 其中被剔除的运营/开发号数（role=ADMIN 或 open_id 以 test_ 开头） */
            long opsExcluded,
            /** 其中被剔除的微信审核账号数（与上一项互斥） */
            long reviewExcluded
    ) {
    }

    /** 近 7 日活跃汇总（窗口固定 7 天，与大盘顶卡「近 7 日活跃」同口径可交叉验算） */
    public record Summary(
            /** 有效用户总量（真实舞友，全量历史，矩阵与占比的自然分母） */
            long totalUsers,
            /** 近 7 日有有效活跃的去重用户数 */
            long activeUsers7d,
            /** 其中「回访型」= 活跃日早于注册日的去重用户数（老用户仍在用） */
            long returningUsers7d
    ) {
    }

    /** 逐日活跃拆分（回答「存量在不在」：日总量把新人混进来会掩盖存量流失） */
    public record DailyPoint(
            /** 统计日（Asia/Shanghai 自然日） */
            LocalDate day,
            /** 当日新活跃 = 注册当日即活跃的去重用户数 */
            long newActive,
            /** 当日老用户活跃 = 注册日早于当日、当日仍活跃的去重用户数 */
            long returningActive
    ) {
    }

    /** 留存曲线点（加权曲线：retained = 已到期批次在 D{dayOffset} 的留存人数之和） */
    public record CurvePoint(
            /** 距注册日的天数偏移（≥1） */
            int dayOffset,
            /** 已到期批次在 D{dayOffset} 有活跃的去重人数之和（分子） */
            long retained,
            /** 已到期批次人数之和（分母，恒 > 0——base 为 0 的偏移不出点） */
            long base
    ) {
    }

    /**
     * 批次留存矩阵行（列 = D1/D3/D7/D14/D30，null = 窗口未到期）。
     * <p>
     * <b>{@code @JsonInclude(ALWAYS)} 是协议要求，勿删</b>：全局 non_null 会把 null 字段
     * 从 JSON 里删掉，而「未到期」正是 null——删掉后前端无法把「未到期」与「0%」区分开，
     * 且会算出 NaN%（见类注释）。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CohortItem(
            /** 批次日（注册日） */
            LocalDate cohortDay,
            /** 批次规模（留存率分母） */
            long size,
            /** D1 留存人数（null = 未到期） */
            Long d1,
            /** D3 留存人数（null = 未到期） */
            Long d3,
            /** D7 留存人数（null = 未到期） */
            Long d7,
            /** D14 留存人数（null = 未到期） */
            Long d14,
            /** D30 留存人数（null = 未到期；days≤30 时本列通常整列为 null） */
            Long d30
    ) {
    }
}
