package org.quwuting.quwutingservice.venuesync.dto.response;

import java.util.List;

/**
 * 批量新增门店整体回执（POST /admin/venue-sync/venues/batch-create）。
 *
 * @param total   请求条数
 * @param created 新建成功数
 * @param existed 同城同名已存在（幂等跳过）数
 * @param failed  失败数
 * @param items   逐条结果（index 对齐请求 items）
 */
public record BatchCreateVenueResponse(
        int total,
        int created,
        int existed,
        int failed,
        List<CreateVenueItemResult> items
) {}
