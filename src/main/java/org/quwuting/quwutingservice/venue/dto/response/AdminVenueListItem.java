package org.quwuting.quwutingservice.venue.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.enums.VenueType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 管理端门店列表行（GET /admin/venues，2026-09-29 V33 到访域配套；2026-10-03 足迹列增强；
 * <b>2026-10-13 足迹列改为「展示口径一源」</b>）。
 * <p>
 * 面向运营盘点的轻量行（非公开 {@code VenueResponse} 的业务裁剪面）：
 * 基础身份 + 状态 + 地址 + 坐标有无 + <b>足迹摘要</b>（{@code VenueVisitBadgeService#footprintsForAdmin}
 * 批量注入，与小程序列表卡片、详情统计卡、足迹名单读的是<b>同一次计算</b>——后台的数字就是用户在小程序里
 * 看到的那组数字）。全字段 {@code ALWAYS} 序列化（35 号文档教训：non_null 全局策略会删 null，
 * 前端对 undefined 求值会出 NaN）。
 *
 * @param id               门店 id
 * @param name             门店名称
 * @param city             城市（标准行政区划名）
 * @param district         区县（可空）
 * @param address          详细地址（可空；歌友会按设计不落地址）
 * @param status           营业状态（存储态枚举名）
 * @param statusDisplay    营业状态展示文案（服务端权威）
 * @param venueType        门店类型（枚举名）
 * @param venueTypeDisplay 门店类型展示文案（服务端权威）
 * @param hasCoordinate    是否已录坐标（无坐标门店不参与足迹采集，列表据此提示）
 * @param expectedOpenDate 预期开业日（V29；null = 无开业计划）
 * @param visitUsers7d     近 7 天附近足迹去重用户数
 * @param visitUsers30d    近 30 天附近足迹去重用户数（= 小程序「感谢 N 位舞友」的 N）
 * @param visitEvents30d   近 30 天去重到访次数（(用户, 自然日) 并集；= 小程序「N 次」的 N）
 * @param lastVisitAt      最近一次附近足迹时刻（不设时间窗）；null = 附近从无足迹
 * @param footprintText    小程序卡片此刻会显示的原文（null = 无足迹、卡片不渲染）
 * @param nearbyVenueCount 邻域（≤150m）内的其他门店数：这些店与本店显示同一组足迹
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminVenueListItem(
        Long id,
        String name,
        String city,
        String district,
        String address,
        VenueStatus status,
        String statusDisplay,
        VenueType venueType,
        String venueTypeDisplay,
        boolean hasCoordinate,
        LocalDate expectedOpenDate,
        long visitUsers7d,
        long visitUsers30d,
        long visitEvents30d,
        LocalDateTime lastVisitAt,
        String footprintText,
        int nearbyVenueCount) {
}
