package org.quwuting.quwutingservice.venuesync.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单条写库请求（2026-08-31，Web 管理后台「门店同步 → 条目级写库」）。
 *
 * @param venueId    平台门店 id（条目 venue.venue_id）
 * @param sourceName 资讯匹配用店名（条目 source_name，与 venueId 联合定位报告条目，
 *                   防同名多店误定位）
 */
public record ApplySyncItemRequest(
        @NotNull(message = "venueId 不能为空") Long venueId,
        @NotBlank(message = "sourceName 不能为空") String sourceName) {
}
