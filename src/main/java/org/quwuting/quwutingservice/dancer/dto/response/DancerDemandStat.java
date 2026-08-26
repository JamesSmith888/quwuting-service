package org.quwuting.quwutingservice.dancer.dto.response;

/**
 * 舞伴「需求热度」统计项（2026-08-26 舞伴统计图二期追加，{@link DancerStatsResponse#demandStats()}）。
 * <p>
 * 语义 = "用户获取 TA 联系方式时最常选择哪些服务"（非口嗨量化：V42 起获取联系方式前
 * 必须选本次需求的服务，每次选择落库 {@code qwt_demand_records}）。按服务类别
 * （{@code DancerServiceCategory}）聚合，非时间序列——与「解锁信息」同语义，
 * 前端以横向条形图做分类对比。
 * <p>
 * 口径（见 {@code DancerStatsRepository#countDancerDemandStats}）：
 * <ul>
 *   <li>数据源 = {@code qwt_demand_records}（V42，风控留痕锚点记录，只写一次）；
 *       service_ids 逗号串拆解 JOIN {@code qwt_dancer_services} 取类别；</li>
 *   <li>{@code demandCount} = 该类别服务的需求<b>次数</b>（服务被选中次数；
 *       业务写路径强制每次需求恰好 1 项服务，历史脏数据多值时按服务逐条计数）；</li>
 *   <li>{@code uniqueUsers} = 提出需求的<b>去重人数</b>（按 user_id，表达覆盖用户）。</li>
 * </ul>
 * label 由服务类别默认标签派生（按时段 / 舞厅跳舞 / 线上聊天 / 其他），
 * 新增类别免前端改动（仅后端加枚举值，本映射自动覆盖）。
 */
public record DancerDemandStat(
        /** 服务类别（DancerServiceCategory.name()：PACKAGE / DANCE / ONLINE_CHAT / OTHER） */
        String category,
        /** 类别展示名（后端权威：类别默认标签；未知类别回退枚举名） */
        String label,
        /** 需求次数（服务被选中次数；业务写路径每需求恰好 1 项服务） */
        long demandCount,
        /** 提出需求的去重人数（按 user_id） */
        long uniqueUsers
) {
}
