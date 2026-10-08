package org.quwuting.quwutingservice.venue.enums;

public enum VenueStatus {
    OPEN("营业中"),
    RENOVATING("装修中"),
    CLOSED("休息中"),
    SUSPENDED("暂停营业"),
    CEASED("已停业");

    private final String displayName;

    VenueStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 本状态是否「<b>可服务</b>」——门店处于能正常接待顾客的状态。
     * <p>
     * 判据 = {@code OPEN}（单点收口，2026-10-08）；休息中 / 装修中 / 暂停营业 / 已停业
     * 一律不可服务。服务于一切<b>依赖「到店」才能兑现</b>的用户端内容可见性：
     * <ul>
     *   <li><b>营业活动</b>（2026-10-08 用户拍板「非 OPEN 全静默」）：门店不可服务期间，
     *       活动在所有 C 端出口静默——列表活动行 / 详情活动卡
     *       （{@code VenueActivityService} 的两个查询）、「有活动」筛选
     *       （{@code VenueRepository#ACTIVITY_PREDICATE}）、到店打卡（{@code #checkin}）。
     *       活动数据与状态本身不动，门店恢复 OPEN 后仍在有效期的活动自动恢复可见；</li>
     *   <li><b>热度上报准入</b>：非营业门店禁报（{@code CrowdReportService#submit} 的
     *       {@code != OPEN} 判定，2026-09-01 起，同判据的历史写法，未强迁）。</li>
     * </ul>
     * ⛔ <b>与「未到营业时间」无关</b>：窗口外的 OPEN 门店在展示层派生出
     * {@code NOT_OPEN_YET}（「今晚 19:00 开门」），存储态仍 OPEN ⇒ <b>仍可服务</b>
     * ——今天会开门，活动行要按「13:00 起」的口径提示。别把本方法当成
     * 「此刻是否在营业时段」用（时段判定归营业时段字段与前端 {@code utils/venueStatus}）。
     * <p>
     * ⚠️ SQL 无法调用本方法：{@code VenueRepository#ACTIVITY_PREDICATE} 与
     * {@code VenueActivityRepository} 两个查询里以 {@code v.status = ...VenueStatus.OPEN}
     * 表达同一判据（三处镜像）——改本方法语义时必须同步那三处。
     */
    public boolean isServable() {
        return this == OPEN;
    }
}
