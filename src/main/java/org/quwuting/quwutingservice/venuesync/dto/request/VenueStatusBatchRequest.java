package org.quwuting.quwutingservice.venuesync.dto.request;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 门店实时状态批量查询请求（2026-09-01，Web 管理后台「门店同步」条目对比条）。
 * <p>
 * 报告条目的平台状态是生成时点快照（会过时）；对比条需要平台<b>当前</b>状态，
 * 由本接口一次往返批量查库（对齐「最少 DB 往返」约束，禁逐条查询）。
 */
public record VenueStatusBatchRequest(
        @Size(max = 300, message = "单次最多查询 300 个门店") List<Long> venueIds) {

    public VenueStatusBatchRequest {
        if (venueIds == null) {
            venueIds = List.of();
        }
    }
}
