package org.quwuting.quwutingservice.venue.dailyopening.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量应用「门店每日营业快照」请求体（POST /admin/venue-daily-openings/batch）。
 * <p>
 * 一次最多 500 条（管线每日量级 ~160 条，留足余量防误传海量数据）。
 */
public record ApplyDailyOpeningBatchRequest(
        @NotEmpty(message = "快照列表不能为空")
        @Size(max = 500, message = "单次最多提交 500 条")
        List<@Valid ApplyDailyOpeningRequest> items
) {}
