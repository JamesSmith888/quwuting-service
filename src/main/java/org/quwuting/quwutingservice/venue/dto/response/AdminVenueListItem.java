package org.quwuting.quwutingservice.venue.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.enums.VenueType;
import org.quwuting.quwutingservice.venuepresence.enums.CoLocatedAttribution;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 管理端门店列表行（GET /admin/venues，2026-09-29 V33 到访域配套；2026-10-03 足迹列增强）。
 * <p>
 * 面向运营盘点的轻量行（非公开 {@code VenueResponse} 的业务裁剪面）：
 * 基础身份 + 状态 + 地址 + 坐标有无 + <b>到访摘要</b>（{@code VenuePresenceService#visitSummaries}
 * 批量注入，与详情统计卡同一次归因计算——列表与详情的数字不得各算各的）。
 * 全字段 {@code ALWAYS} 序列化（35 号文档教训：non_null 全局策略会删 null，
 * 前端对 undefined 求值会出 NaN）。
 *
 * @param id                   门店 id
 * @param name                 门店名称
 * @param city                 城市（标准行政区划名）
 * @param district             区县（可空）
 * @param address              详细地址（可空；歌友会按设计不落地址）
 * @param status               营业状态（存储态枚举名）
 * @param statusDisplay        营业状态展示文案（服务端权威）
 * @param venueType            门店类型（枚举名）
 * @param venueTypeDisplay     门店类型展示文案（服务端权威）
 * @param hasCoordinate        是否已录坐标（无坐标门店不参与到访采集，列表据此提示）
 * @param expectedOpenDate     预期开业日（V29；null = 无开业计划）
 * @param visitUsers7d         近 7 天到访人数（命中口径 = VenuePresenceService.HIT_RADIUS_M，按同址归因）
 * @param visitUsers30d        近 30 天到访人数（同上；口径详情见详情页统计卡）
 * @param lastVisitAt          最近一次到访时刻（同口径、不设时间窗）；null = 从无到访
 * @param coLocatedAttribution 同址归因方式（NONE = 无同址门店；非 NONE 时数字须带同址说明）
 * @param coLocatedCount       同址门店数（不含本店）
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
        LocalDateTime lastVisitAt,
        CoLocatedAttribution coLocatedAttribution,
        int coLocatedCount) {
}
