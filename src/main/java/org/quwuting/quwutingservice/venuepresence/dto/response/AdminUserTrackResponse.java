package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端「某用户的位置轨迹」响应（2026-10-08 V46，GET /admin/users/{userId}/track；仅 ADMIN）。
 * <p>
 * 定位：用户详情页「位置轨迹」卡的数据源——把该用户窗口内的<b>坐标采样点</b>按时间升序
 * 交出（地图绘制的自然顺序），每点携带其最近门店关系（门店 / 距离 / 距离带分级）。
 * <p>
 * <b>与 {@code AdminUserVisitsResponse} 的分工（两个问题两个面）</b>：
 * 到访足迹 = 命中口径的「一次次到店」（分组、停留、采样数）；本响应 = <b>全部</b>采样点的
 * 原始轨迹（含 150m 外的「附近 / 留痕」带）——2026-10-08 user 210 的 295m 样本正是后者，
 * 「他当时到底在哪」只能靠它回答。
 * <p>
 * <b>口径参数随响应回显</b>（同 {@code VenuePresenceStats} 纪律）：{@code windowDays} /
 * {@code hitRadiusM} / {@code nearbyRadiusM} 必须与图例同屏；分级恒为距离带
 * （见 {@code PresenceTrackGrade}），⛔ 不得被读成「到访判定」。
 * <p>
 * ⛔ 全字段 {@code @JsonInclude(ALWAYS)}（全局 non_null 会删 null，35 号教训）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminUserTrackResponse(
        Long userId,
        /** 本次查询的时间窗（天，服务端钳制 1~90） */
        int windowDays,
        /** 命中带半径（米，服务端权威） */
        int hitRadiusM,
        /** 附近带半径（米，服务端权威） */
        int nearbyRadiusM,
        /** 轨迹点，按时间<b>升序</b>（绘制序）；已剔除无坐标行（其数量见 pointsWithoutCoordinates） */
        List<TrackPoint> points,
        /** 轨迹点涉及到的门店（去重；含坐标供地图画 150/300m 半径圈） */
        List<TrackVenue> venues,
        /**
         * 窗口内<b>无坐标</b>的记录数（旧端 / 历史行，不可绘制）。
         * <b>必须显式下发</b>：这些记录不在 points 里，不说就会被读成「他没来过」——
         * 同 truncated 的「禁静默」纪律。
         */
        int pointsWithoutCoordinates,
        /** 是否被上限截断（保留最近点）；截断时前端须显示「仅显示最近 N 个点」 */
        boolean truncated) {

    /**
     * 一个轨迹采样点（一次 ping）。
     *
     * @param at        采样（桶内首见）时刻，北京时间
     * @param latitude  用户纬度（gcj02）
     * @param longitude 用户经度（gcj02）
     * @param accuracyM 端侧定位精度（米；null = 端侧未提供）
     * @param venueId   该时刻的最近门店 id（端侧「500m 内最近一家」选择结果）
     * @param distanceM 距该门店距离（米，端侧自报）
     * @param grade     距离带：HIT / NEARBY / FAR（{@code PresenceTrackGrade}）
     * @param gradeDisplay 距离带展示名（服务端下发，前端不另译）
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TrackPoint(
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime at,
            double latitude,
            double longitude,
            Integer accuracyM,
            Long venueId,
            int distanceM,
            String grade,
            String gradeDisplay) {
    }

    /**
     * 轨迹点涉及的门店（供地图叠加显示）。
     * <p>
     * {@code latitude}/{@code longitude} 可能为 null（门店被清坐标 / 已软删但 ping 仍在）——
     * 前端据 null 跳过绘制门店与半径圈，但点列表里的门店名回退「门店 #id」。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TrackVenue(
            Long id,
            String name,
            Double latitude,
            Double longitude,
            String statusDisplay) {
    }
}
