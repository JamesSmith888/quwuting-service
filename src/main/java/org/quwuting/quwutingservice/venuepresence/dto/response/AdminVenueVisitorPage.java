package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.quwuting.quwutingservice.venuepresence.enums.CoLocatedAttribution;

import java.util.List;

/**
 * 管理端「到访用户名单」分页响应（2026-10-06，GET /admin/venues/{venueId}/visitors；仅 ADMIN）。
 * <p>
 * <b>为什么名单也要带口径参数</b>（同 {@link VenuePresenceStats} 的纪律「统计量不带口径 =
 * 邀请误读」）：名单里的 {@code visitTimes} 是<b>有窗口的</b>（近 N 天去重到店日），
 * 而 {@code lastVisitAt} 是<b>无窗口的</b>（从无到访判断靠全量扫描）。
 * 不同窗口会得到不同的 {@code totalElements}——运营看到「共 12 人」却不知道这是
 * 近 30 天还是近 90 天的 12 人，就会把它当全量历史人数。窗口与口径因此<b>随响应回显</b>。
 * <p>
 * <b>与列表/详情页数字的同源纪律</b>：{@code totalElements} 与
 * {@code GET /admin/venues} 行内的 {@code visitUsers30d} 必须<b>指向同一批人</b>——
 * 两者都走 {@code VenuePresenceService#visitorsFor} 的同一次归因与并集计算，
 * ⛔ 禁在本方法里另写一份口径（否则「点进去 12 人、列表写 5 人」= 不可解释的不一致）。
 * <p>
 * ⛔ 全字段 {@code @JsonInclude(ALWAYS)}（全局 non_null 会删 null，35 号教训）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminVenueVisitorPage(
        /** 行数据（按最近到访倒序；并列按 userId 升序保证翻页确定） */
        List<AdminVenueVisitorItem> content,
        /** 总条数（= 窗口内去重访客数，与列表行 visitUsers30d 同源同值） */
        long totalElements,
        /** 总页数 */
        int totalPages,
        /** 当前页码（0 基，与 Spring Page 一致） */
        int number,
        /** 每页条数 */
        int size,
        /** 是否最后一页（Spring Data 序列化字段；admin-web 分页据此判定 finished） */
        boolean last,
        /**
         * 本次统计的时间窗（天）。
         * <p>
         * <b>为什么名单窗口与列表的 30 天可以不同</b>：列表行的 7/30 天是与
         * 热度公式对齐的固定口径（{@code RANKING_WINDOW_DAYS}），不能由前端改；
         * 而名单页是<b>运营下钻</b>，「这家店历史上到底有谁来过」需要能放大窗口。
         * 缺省 30 天 = 与列表行同值（点进去看到的总数就是列表写的人数），
         * 放大后 {@code hitRadiusM} 等口径不变、只有窗口变，运营能自己判断可比性。
         */
        int windowDays,
        /** 到访命中半径（米，服务端权威；展示必须与数值同屏） */
        int hitRadiusM,
        /** 本店的同址归因方式（名单人数已含同址归因，非归因方式无法解释人数来源） */
        CoLocatedAttribution coLocatedAttribution,
        /**
         * 与本店同址的其他门店（同楼不同层，定位无法区分）及其营业状态。
         * 同址共享时本名单可能包含「其实在邻居店里」的人——不说明会被读成本店独立客流。
         */
        List<VenuePresenceStats.CoLocatedVenue> coLocatedVenues) {
}
