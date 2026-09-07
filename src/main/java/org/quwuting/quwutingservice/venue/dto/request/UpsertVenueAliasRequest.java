package org.quwuting.quwutingservice.venue.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 门店别名 upsert 请求体（POST /admin/venue-aliases）。
 * <p>
 * 幂等：同店同名复活（软删行重用），否则新增——venueId + alias 归一化后
 * （trim）作为幂等键，对齐生成列部分唯一索引（MySQL V12）。
 */
public record UpsertVenueAliasRequest(
        @NotNull(message = "venueId 不能为空")
        Long venueId,

        @NotBlank(message = "别名不能为空")
        @Size(max = 100, message = "别名最长 100 字")
        String alias
) {}
