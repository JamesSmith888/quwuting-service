package org.quwuting.quwutingservice.venuesync.dto.response;

/**
 * 批量新增门店单条结果（POST /admin/venue-sync/venues/batch-create 的元素级回执）。
 *
 * @param index    请求 items 中的下标（Skill 侧按 index 对齐回执）
 * @param name     门店名称
 * @param city     城市
 * @param result   CREATED（已建档）/ EXISTED（同城同名已存在，幂等跳过）/ FAILED（校验或落库失败）
 * @param venueId  建档成功的门店 ID（CREATED/EXISTED 时非空）
 * @param message  失败原因（FAILED 时非空；EXISTED 时提示已存在门店 ID）
 */
public record CreateVenueItemResult(
        int index,
        String name,
        String city,
        String result,
        Long venueId,
        String message
) {}
