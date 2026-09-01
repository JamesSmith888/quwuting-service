package org.quwuting.quwutingservice.venuesync.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量新增门店请求体（POST /admin/venue-sync/venues/batch-create）。
 * <p>
 * 调用方 = 舞讯采集 Skill（Agent 一键录入新店线索）。容器级校验（非空 + 上限）
 * 用注解（整体非法直接 400 合理）；<b>元素级校验在 Service 逐条做</b>——
 * 单条脏数据（如缺城市）记 FAILED，不影响同批其他门店。
 * <p>
 * 上限 100 家/批：舞讯单日新店线索量级通常个位数到十几家，留足余量防误传。
 */
public record BatchCreateVenueRequest(
        @NotEmpty(message = "新增门店列表不能为空")
        @Size(max = 100, message = "单次最多新增 100 家门店")
        List<CreateVenueItem> items
) {}
