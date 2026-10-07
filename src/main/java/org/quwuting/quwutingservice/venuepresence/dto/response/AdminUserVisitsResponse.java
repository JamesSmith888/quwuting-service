package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端「某用户的到访足迹」响应（2026-10-06，GET /admin/users/{userId}/visits；仅 ADMIN）。
 * <p>
 * 定位：用户详情页「到访足迹」卡片的唯一数据源——把该用户的到访<b>按门店分组</b>、
 * 组内按到店时间倒序展开每一次到店（{@link AdminUserVisitRecord}）。
 * <p>
 * <b>为什么按门店分组而不是一条平铺的时间线</b>：足迹的价值是「他常去哪家店」，
 * 平铺时间线会把同一家店的多次到店散落在列表里、看不出集中度。分组后
 * 每组自带「来过几次 / 最近什么时候」，一眼可读。
 * <p>
 * <b>口径参数随响应回显</b>（同 {@code VenuePresenceStats} 纪律：统计量不带口径 = 邀请误读）：
 * {@code windowDays} / {@code hitRadiusM} 必须与行内数字同屏。
 * <p>
 * ⛔ 全字段 {@code @JsonInclude(ALWAYS)}（全局 non_null 会删 null，35 号教训）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminUserVisitsResponse(
        Long userId,
        /** 按最近到访倒序的门店分组 */
        List<AdminUserVisitVenueGroup> venues,
        /** 窗口内去重访客过几家店（= venues.size()，冗余便于前端直接展示，不必遍历） */
        int venueCount,
        /** 窗口内到店总次数（= 各组次数之和） */
        long visitCount,
        /** 窗口内最近一次到访时刻；null = 窗口内无到访（与「从无到访」区分：前者有历史） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastVisitAt,
        /** 本次查询的时间窗（天）；命中判定不随窗口变化，只有「纳入哪些记录」随窗口变 */
        int windowDays,
        /** 到访命中半径（米，服务端权威；展示必须与数值同屏） */
        int hitRadiusM,
        /**
         * 记录是否被截断（达到单次响应上限）。
         * <b>必须显式下发</b>：截断时前端要显示「仅显示最近 N 次」，
         * 否则运营会把「上限」误读成「他就这么多次来过」——
         * 同 UserDetailView 行为轨迹的 {@code truncated} 纪律。
         */
        boolean truncated,
        /**
         * 该用户的到访足迹开关当前态 + 变更流水（2026-10-07 新增）。
         * <p>
         * <b>为什么与足迹同响应</b>：运营在同一屏要同时回答「他是否允许我们记录」与「他来过哪家店」——
         * 看到 20 条到访却不知道对方早已关闭采集，会直接误判为隐私事故。拆成两个接口必然出现
         * 「足迹已出、开关还在加载」的中间态，那种不一致比慢 100ms 危险。
         * <p>
         * ⛔ 恒非 null（即便用户从未确立也返回 {@code state=NEVER_ASKED} 的对象）：
         * 字段缺失与「未询问」是两件事，前端不该靠 null 推断语义。
         */
        AdminUserConsentResponse consent) {

    /**
     * 一个门店分组：该用户在这家店的窗口内到访记录（按到店时间倒序）。
     *
     * @param visitCount 本店窗口内到访次数（= records.size()）
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminUserVisitVenueGroup(
            Long venueId,
            String venueName,
            String venueCity,
            String venueStatusDisplay,
            /** 本店窗口内到访次数 */
            int visitCount,
            /** 本店最近一次到访时刻（同 records 首条 arrivedAt，冗余供分组头展示） */
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime lastVisitAt,
            /** 本店到访明细，按到店时间倒序（最近的在前） */
            List<AdminUserVisitRecord> records) {
    }
}
