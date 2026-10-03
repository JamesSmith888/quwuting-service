package org.quwuting.quwutingservice.venue.enums;

/**
 * 管理端门店列表排序（GET /admin/venues?sort=，2026-10-03；docs/agents/52-venue-presence.md §6.1）。
 * <p>
 * 只有 {@link #LATEST} 是库表列排序（走 SQL 分页）；其余两项排的是<b>到访派生量</b>（命中谓词 ×
 * 同址归因 × 用户并集，不是任何一列），由 {@code AdminVenueQueryService} 取全量 id 后稳定排序切页。
 * 并列按最近到访倒序，再并列（含从无到访）回落默认序（id 倒序），保证同一筛选下翻页结果确定。
 */
public enum AdminVenueSort {

    /** 最新收录（id 倒序，默认）：运营最常核对新店 */
    LATEST,

    /** 近 30 天到访人数倒序（并列按最近到访倒序） */
    VISITS_30D,

    /** 最近到访时刻倒序（从无到访的门店排最后） */
    LAST_VISIT
}
