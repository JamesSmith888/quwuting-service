package org.quwuting.quwutingservice.venue.dailyopening.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量置「暂停营业」请求体（POST /admin/venue-daily-openings/batch-suspend）。
 * <p>
 * 语义（2026-09-10 用户拍板「白名单口径」）：当日舞讯<b>点名覆盖的城市</b>内，
 * 未出现在营业名单里的门店视为今日未营业 → 平台 status 由 OPEN 置 SUSPENDED。
 * <b>未被舞讯覆盖的城市一律不推断</b>（调用方负责只提交覆盖城市内的门店）。
 * <p>
 * 一次最多 500 条（与 {@link ApplyDailyOpeningBatchRequest} 同口径：
 * 全国级舞讯名义覆盖门店数百家，留足余量防误传海量数据）。
 */
public record ApplyVenueSuspendBatchRequest(
        @NotEmpty(message = "待暂停门店列表不能为空")
        @Size(max = 500, message = "单次最多提交 500 条")
        List<@Valid VenueSuspendItemRequest> items
) {}
