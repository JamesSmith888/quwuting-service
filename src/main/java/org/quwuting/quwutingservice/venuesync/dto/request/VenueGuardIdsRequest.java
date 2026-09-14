package org.quwuting.quwutingservice.venuesync.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 门店状态权威层级 · 批量门店 ID 请求（2026-09-14，V25；方案见 docs/agents/48）。
 * <p>
 * 用于「查询门禁状态」（POST /admin/venue-sync/guard/query）与「恢复自动同步」
 * （POST /admin/venue-sync/guard/unlock）。用 POST 承载批量 ID 而非 PUT/DELETE，
 * 遵循项目「禁 PUT/PATCH/DELETE」约定。
 *
 * @param venueIds 门店 ID 列表（1~100；管理端一次操作几百家门店的场景不存在）
 */
public record VenueGuardIdsRequest(
        @NotEmpty(message = "门店 ID 列表不能为空")
        @Size(max = 100, message = "单次最多 100 家门店")
        List<Long> venueIds
) {}
