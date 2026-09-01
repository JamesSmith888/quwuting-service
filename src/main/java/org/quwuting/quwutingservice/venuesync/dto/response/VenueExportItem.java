package org.quwuting.quwutingservice.venuesync.dto.response;

/**
 * 门店导出条目（GET /admin/venue-sync/venues/export，Skill/Agent 比对候选库数据底座）。
 * <p>
 * 设计定位：给舞讯采集 Skill 的「按量加载」接口用——只带比对必需字段
 * （id/名称/城市/区县/地址/状态），<b>不做</b> {@code listVenues} 的重型组装
 * （Reaction 徽标/浏览量/照片/热度角标对机器比对毫无意义，纯属浪费）。
 * 字段命名对齐管线快照口径（venue_id），便于 Skill 侧与 matcher 输出对照。
 *
 * @param venueId 平台门店 ID
 * @param name    门店名称
 * @param city    城市（标准行政区划名）
 * @param district 区县（可空）
 * @param address 地址（可空）
 * @param status  营业状态枚举名（OPEN/RENOVATING/CLOSED/SUSPENDED/CEASED）
 */
public record VenueExportItem(
        Long venueId,
        String name,
        String city,
        String district,
        String address,
        String status
) {}
