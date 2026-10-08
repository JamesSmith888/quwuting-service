package org.quwuting.quwutingservice.venuepresence.enums;

import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;

/**
 * 轨迹点的距离带分级（2026-10-08 V46，admin「位置轨迹」卡；服务端判定、前端只展示）。
 * <p>
 * 判据 = 该 ping 自报的「距最近门店距离」（端侧取自 {@code /venues/nearby} 的服务端
 * Haversine 值，原样回传）：
 * <ul>
 *   <li>≤ {@link VenuePresenceService#HIT_RADIUS_M}（150m）⇒ {@link #HIT 命中}——与到访统计同一距离带；</li>
 *   <li>≤ {@link VenuePresenceService#NEARBY_RADIUS_M}（300m）⇒ {@link #NEARBY 附近}——「片区」带
 *       （2026-10-08 用户要求：150~300m 的「附近」语义也要在 admin 展示出来）；</li>
 *   <li>其余（写侧限幅 500m 以内）⇒ {@link #FAR 留痕}。</li>
 * </ul>
 * ⚠️ 本分级只表达 <b>距离带</b>，不是到访判定的替代：到访另需精度达标
 * （{@link VenuePresenceService#HIT_MAX_ACCURACY_M}）且同址归因在查询侧另有计算——
 * admin 图上「命中」点与「到访记录」不是同一份口径，卡片说明必须写明
 * （「标签恒等于结果」纪律）。
 * <p>
 * 阈值不在本枚举复写：引用 {@link VenuePresenceService} 的协议常量（编译期内联，
 * 改值仍只需改那三处镜像纪律所辖的位置）。
 */
public enum PresenceTrackGrade {

    /** 命中带：距最近门店 ≤ 150m（与到访统计同一距离带；到访另需精度达标） */
    HIT("命中"),

    /** 附近带：150~300m——「片区」语义（这一带出现过用户），不计入到访 */
    NEARBY("附近"),

    /** 留痕带：> 300m（采集限幅 500m 以内的「路过」样本） */
    FAR("留痕");

    private final String displayName;

    PresenceTrackGrade(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 距离带判定（单点：admin 轨迹接口的唯一分级实现，⛔ 禁在服务/前端另写一份）。
     * 调用方已保证 distanceM 非空（ping 表 distance_m NOT NULL）。
     */
    public static PresenceTrackGrade ofDistance(int distanceM) {
        if (distanceM <= VenuePresenceService.HIT_RADIUS_M) {
            return HIT;
        }
        if (distanceM <= VenuePresenceService.NEARBY_RADIUS_M) {
            return NEARBY;
        }
        return FAR;
    }
}
