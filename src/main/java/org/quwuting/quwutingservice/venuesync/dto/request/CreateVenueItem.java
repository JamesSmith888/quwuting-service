package org.quwuting.quwutingservice.venuesync.dto.request;

import org.quwuting.quwutingservice.venue.enums.VenueStatus;

/**
 * 批量新增门店单条（POST /admin/venue-sync/venues/batch-create 的元素）。
 * <p>
 * 纯数据载体，<b>字段不挂校验注解</b>——批量接口要求「单条失败不拖累整批」，
 * 由 Service 层逐条校验并记入 FAILED 结果（若用 @Valid 级联，一条脏数据会整体 400）。
 * 字段对齐舞讯能提供的信息（城市 + 名称 + 可选地址/区县/状态），其余业务字段
 * （营业时段/门票/照片等）批量建档不提供，走默认值，后续人工在详情页补全。
 *
 * @param name     门店名称（必填，≤100）
 * @param city     城市（必填，标准行政区划名——幂等判重与列表筛选共用同一词表，精确匹配）
 * @param district 区县（选填，≤50）
 * @param address  地址（选填，≤200）
 * @param status   营业状态（选填，缺省 OPEN）
 */
public record CreateVenueItem(
        String name,
        String city,
        String district,
        String address,
        VenueStatus status
) {}
