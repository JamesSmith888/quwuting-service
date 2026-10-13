package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 门店足迹统计（admin 详情页「到店信息」卡，GET /admin/venues/{id}/presence；2026-09-29 V33，
 * <b>2026-10-13 改为「展示口径一源」</b>：与小程序列表卡片、admin 门店列表行<b>同一次计算</b>
 * （{@code NearbyFootprint}），详情页上每个数字都能点开看到「具体是哪些人」）。
 * <p>
 * 口径必须随数值一起下发：admin 展示的每个数字旁边都要能说出它的半径与时间窗
 * （管理端纪律：统计量不带口径 = 邀请误读）。所有字段 {@code ALWAYS} 序列化——「无记录 = 0 / null」
 * 与「字段缺失」是两件事，non_null 全局策略会把 null 静默删掉，前端拿到 undefined 会算出 NaN
 * （35 号文档实证过的坑）。
 * <p>
 * <b>历史（勿回退）</b>：此前本记录带「到访 7/30 天（150m 命中 × 同址归因）」与「附近 30 天（300m 片区）」
 * 两套并列数字，且详情页明确规定「四张统计卡不可点」——因为两套数字指向的人不是同一批，做成可点会
 * 出现「显示 40 人、名单只有 12 人」。<b>不可点是症状，两套口径并存才是病因</b>：口径合一后
 * 卡片、列表行、名单、小程序文案指向同一批人，可点自然成立。同址归因
 * （NONE/SHARED/ABSORBED/YIELDED）只剩热度排序在用，不再进任何展示数字。
 *
 * @param visitUsers7d     近 7 天去重用户数（附近带 {@code nearbyRadiusM} 并集）
 * @param visitUsers30d    近 30 天去重用户数（= 小程序「感谢 N 位舞友」的 N）
 * @param visitEvents30d   近 30 天去重到访次数（(用户, 自然日) 并集；= 小程序「N 次」的 N）
 * @param lastVisitAt      最近一次足迹时刻（不设时间窗）；null = 附近从无足迹
 * @param excludedUsers30d 近 30 天因「排除账号集合」未计入的用户数（0 = 无；非 0 时展示必须明说）
 * @param nearbyRadiusM    足迹取数半径（米，用户 ↔ 门店：距店 ≤ 此值的定位计入）
 * @param vicinityRadiusM  邻域半径（米，门店 ↔ 门店：此范围内的门店显示同一组足迹）
 * @param maxAccuracyM     定位精度门槛（米，不随半径放宽；精度缺失视为达标）
 * @param footprintText    小程序列表卡片上<b>会显示的原文</b>（null = 无足迹、卡片不渲染）。
 *                         与 C 侧同一渲染函数产出，用于「所见即所得」核对
 * @param vicinityVenues   邻域内的其他门店（含营业状态；这些店与本店显示同一组足迹）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VenuePresenceStats(
        long visitUsers7d,
        long visitUsers30d,
        long visitEvents30d,
        LocalDateTime lastVisitAt,
        long excludedUsers30d,
        int nearbyRadiusM,
        int vicinityRadiusM,
        int maxAccuracyM,
        String footprintText,
        List<VicinityVenue> vicinityVenues) {

    /**
     * 邻域内的其他门店（识别信息 + 营业状态：admin 展示「与 X 显示同一组足迹」，可下钻）。
     *
     * @param id            门店 id
     * @param name          门店名
     * @param statusDisplay 营业状态展示文案（服务端权威）
     * @param inOperation   是否在营（仅供运营识别；展示口径<b>不看</b>营业状态）
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VicinityVenue(Long id, String name, String statusDisplay, boolean inOperation) {
    }
}
