package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 管理端「足迹用户名单」分页响应（GET /admin/venues/{venueId}/visitors；仅 ADMIN；
 * 2026-10-06 建，<b>2026-10-13 改为展示口径一源</b>）。
 * <p>
 * <b>名单 = 门店列表行 / 详情卡 / 小程序文案上那个数字的「展开」</b>：同一次
 * {@code NearbyFootprint} 计算、同一个用户并集、同一个窗口判据，所以
 * <ul>
 *   <li>{@code windowDays=30} 时 {@link #totalElements} = 卡片「30 天 N 人」= 小程序「N 位舞友」；</li>
 *   <li>{@code windowDays=7} 时 {@link #totalElements} = 卡片「7 天 N 人」；</li>
 *   <li>{@code windowDays=30} 时 {@link #totalVisitTimes} = 卡片「30 天 N 次」= 小程序「N 次」。</li>
 * </ul>
 * 窗口因此<b>不再钳到 ≥30</b>：旧约束（「小于 30 会与列表行冲突」）的前提是行里只有 30 天一个数字；
 * 现在 7 天也是可点的数字，点进来必须恰好是那 7 天的人。
 * <p>
 * <b>为什么名单也要带口径参数</b>（同 {@link VenuePresenceStats} 的纪律「统计量不带口径 = 邀请误读」）：
 * 名单里的 {@code visitTimes} 是<b>有窗口的</b>（近 N 天去重到店日），窗口与半径随响应回显。
 * <p>
 * ⛔ 全字段 {@code @JsonInclude(ALWAYS)}（全局 non_null 会删 null，35 号教训）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminVenueVisitorPage(
        /** 行数据（按最近足迹倒序；并列按 userId 升序保证翻页确定） */
        List<AdminVenueVisitorItem> content,
        /** 总条数（= 窗口内去重用户数，与对应窗口的卡片数字同源同值） */
        long totalElements,
        /** 总页数 */
        int totalPages,
        /** 当前页码（0 基，与 Spring Page 一致） */
        int number,
        /** 每页条数 */
        int size,
        /** 是否最后一页（admin-web 分页据此判定 finished） */
        boolean last,
        /** 本次统计的时间窗（天；服务端钳制到 [1, 3650]，缺省 30） */
        int windowDays,
        /** 窗口内全部用户的到访次数合计（(用户, 自然日) 并集大小；30 天时 = 卡片「N 次」，不是本页行数之和） */
        long totalVisitTimes,
        /** 足迹取数半径（米，服务端权威；展示必须与数值同屏） */
        int nearbyRadiusM,
        /** 邻域半径（米：此范围内的门店显示同一组足迹，名单人数含邻域内其他门店的足迹） */
        int vicinityRadiusM,
        /** 窗口内因「排除账号集合」未计入名单的用户数（0 = 无；非 0 时展示必须明说） */
        long excludedUsers,
        /** 邻域内的其他门店及营业状态（名单可能含「其实在隔壁店」的人——不说明会被读成本店独立客流） */
        List<VenuePresenceStats.VicinityVenue> vicinityVenues) {
}
