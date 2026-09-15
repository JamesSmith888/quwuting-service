package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端「单用户行为统计画像」响应（2026-09-15，
 * {@code GET /admin/users/{id}/behavior-profile?days=}；仅 ADMIN）。
 * 用户详情页「行为统计」区块数据源——把「这个账号在这个窗口里到底做了什么」量化。
 *
 * <h2>口径（与大盘/留存严格同源）</h2>
 * <ul>
 *   <li><b>活跃</b>类数字（{@link #activeDays} / {@link #activeEventTotal} /
 *       {@link #recent7Active} / {@link #prev7Active} / {@link #hourly}）只统计
 *       {@code UserBehaviorEvent.Nature#ACTIVE}（用户主动行为，<b>不含登录自动打卡</b>）；</li>
 *   <li>{@link #openDays} 是<b>打开</b>序列（打卡去重天数），单独成字段——
 *       「打开 ≠ 活跃」是本能力的核心区分，两个数字并排展示才看得清
 *       （天天打开却从不互动 = 审核/巡检号的典型画像）；</li>
 *   <li>{@link #eventTotal} 含全部口径档（轨迹的完整性），{@link #breakdown} 每行都带
 *       {@code nature}，故「总数里有多少是主动行为」可当场对账。</li>
 * </ul>
 *
 * <h2>比率不下发</h2>
 * 只给原始计数（次数 / 天数 / 条数），占比与人均一律由前端派生（同
 * {@code AdminSpendUsagePanel} 渗透率先例）；{@link #recent7Active} 与
 * {@link #prev7Active} 也刻意给两个<b>计数</b>而不是「趋势百分比」，
 * 让前端在分母为 0 时能自己决定显示「—」而不是算出一个 NaN。
 *
 * <h2>空值语义（{@link JsonInclude.Include#ALWAYS} 是协议的一部分，勿删）</h2>
 * {@link #firstActiveAt} / {@link #lastActiveAt} 为 {@code null} = <b>窗口内没有任何主动行为</b>
 * （不是「很久以前」）。本仓全局 {@code default-property-inclusion=non_null} 会把 null 字段
 * <b>整个从 JSON 删掉</b>，前端便无法区分「无行为」与「字段缺失」——2026-09-15 已因同类
 * 配置踩过 NaN% 的坑（见 {@code AdminUserRetentionResponse}），故此处显式声明。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminUserBehaviorProfileResponse(
        /** 生效窗口天数（回显服务端钳制后的值，7~90） */
        int days,
        /** 窗口内事件总条数（含全部口径档） */
        long eventTotal,
        /** 其中「主动行为」条数（活跃口径） */
        long activeEventTotal,
        /** 活跃天数 = 窗口内有主动行为的<b>去重自然日</b>数（跨事件类型去重，与大盘「活跃」同源） */
        long activeDays,
        /** 打开天数 = 窗口内打卡去重天数（登录自动触发，不代表使用） */
        long openDays,
        /** 近 7 日主动行为条数（窗口不足 7 天时按实际窗口计） */
        long recent7Active,
        /** 此前 7 日（第 8~14 天）主动行为条数（与 {@link #recent7Active} 同口径，供前端派生趋势） */
        long prev7Active,
        /** 主动行为中「无精确时刻」的条数（未计入 {@link #hourly}；日列型历史脏行） */
        long timedOutActiveEvents,
        /** 窗口内首次主动行为时刻（null = 窗口内无主动行为） */
        LocalDateTime firstActiveAt,
        /** 窗口内最近主动行为时刻（null = 窗口内无主动行为） */
        LocalDateTime lastActiveAt,
        /** 行为类型分布（只含窗口内有事件的类型，按条数降序；平台全目录见 timeline 的 typeOptions） */
        List<TypeCount> breakdown,
        /** 活跃时段直方图：固定 24 格（下标 = 小时 0~23），值为主动行为条数（无观测恒为 0，非「未知」） */
        List<Long> hourly
) {

    /**
     * 单类型计数（窗口内）。
     * <p>
     * <b>{@code @JsonInclude(ALWAYS)} 勿删</b>：{@link #lastAt} 的 null 有语义
     * （该类型全部记录都没有时间戳）——全局 {@code non_null} 会把它从 JSON 删掉，
     * 前端便无法区分「无时刻」与「字段缺失」（外层 record 上的注解<b>不会</b>继承给嵌套类型，
     * 故这里必须单列）。count/days 恒为计数，0 = 窗口内真的没有。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TypeCount(
            /** 事件码 */
            String code,
            /** 事件中文名 */
            String label,
            /** 分类中文名 */
            String categoryLabel,
            /** 口径档码 */
            String nature,
            /** 口径档中文名（前端据此打标签、分色） */
            String natureLabel,
            /** 该类型事件条数 */
            long count,
            /** 该类型覆盖的去重天数（回答「零星一次」还是「持续在做」） */
            long days,
            /** 该类型最近一次发生时刻（该类型全部记录都无时间戳时为 null） */
            LocalDateTime lastAt
    ) {
    }
}
