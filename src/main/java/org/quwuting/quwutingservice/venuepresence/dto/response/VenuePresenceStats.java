package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.quwuting.quwutingservice.venuepresence.enums.CoLocatedAttribution;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 门店到访统计（admin 详情卡的口径载体，2026-09-29 V33；列表行同源于
 * {@code VenueVisitSummary}，两处数字由同一次归因计算产出）。
 * <p>
 * 口径必须随数值一起下发：admin 展示的每个数字旁边都要能说出它的半径与时间窗
 * （管理端纪律：统计量必须带口径说明，否则运营会当承诺值讲）。所有字段
 * {@code ALWAYS} 序列化——「无记录 = 0 / null」与「字段缺失」是两件事，
 * non_null 全局策略会把 null 静默删掉，前端拿到 undefined 会算出 NaN
 * （35 号文档实证过的坑）。
 * <p>
 * <b>同址归因（2026-10-03，52 号 §4.2 / §4.4）</b>：坐标间距 ≤ 同址半径的门店（同楼不同层、同楼不同门牌）
 * 定位不可区分，到访证据先按营业状态归属（{@link CoLocatedAttribution}），无法归属时组内共享（用户并集）。
 * admin 必须按 {@code coLocatedAttribution} 同屏说明，否则两家同址店各显示 N 人会被读成合计 2N。
 *
 * @param visitUsers7d         近 7 天到访人数（去重 UV；命中口径：distance ≤ hitRadiusM
 *                             且 accuracy ≤ hitRadiusM，按同址归因）
 * @param visitUsers30d        近 30 天到访人数（同上口径，时间窗放大）
 * @param nearbyUsers30d       近 30 天附近人数（片区覆盖度：distance ≤ nearbyRadiusM，同一精度门槛；
 *                             语义 = 「这一带出现过多少用户」，非实时在场；<b>片区语义不做营业归因</b>，
 *                             同址组恒合并）
 * @param lastVisitAt          最近一次到访时刻（与到访人数同一命中谓词、同一归因范围，不设时间窗）；
 *                             null = 从无到访（2026-10-03 前名 lastPresenceAt、取「任意距离的最近痕迹」，
 *                             会在「到访 0 人」旁显示一个时刻——口径与旁边的数字不一致，已改）
 * @param hitRadiusM           到访命中半径（米，服务端权威，供展示口径）
 * @param nearbyRadiusM        附近覆盖半径（米，服务端权威，供展示口径）
 * @param coLocatedRadiusM     同址半径（米，门店坐标间距 ≤ 此值即视为手机定位分不开）
 * @param coLocatedAttribution 本店的同址归因方式（NONE = 无同址门店）
 * @param coLocatedVenues      与本店同址的其他门店（含营业状态，admin 据此说明证据去向）；空 = 无
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VenuePresenceStats(
        long visitUsers7d,
        long visitUsers30d,
        long nearbyUsers30d,
        LocalDateTime lastVisitAt,
        int hitRadiusM,
        int nearbyRadiusM,
        int coLocatedRadiusM,
        CoLocatedAttribution coLocatedAttribution,
        List<CoLocatedVenue> coLocatedVenues) {

    /**
     * 同址门店（识别信息 + 营业状态：admin 展示「与 X 同址」并说明证据归向，可下钻）。
     *
     * @param id            门店 id
     * @param name          门店名
     * @param statusDisplay 营业状态展示文案（服务端权威）
     * @param inOperation   是否在营（可被到访归因，判据 = {@code VenuePresenceService#isInOperation}）
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CoLocatedVenue(Long id, String name, String statusDisplay, boolean inOperation) {
    }
}
