package org.quwuting.quwutingservice.venuesync.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 手动映射别名创建/更新请求（POST /admin/venue-sync/aliases）。
 * <p>
 * 语义：城市 + 网上门店名称（sourceName）唯一确定一条映射（幂等 upsert），
 * 重复提交同一 key = 更新 venueId/note。
 */
public record UpsertVenueSyncAliasRequest(
        @NotBlank(message = "城市不能为空")
        @Size(max = 50, message = "城市最长 50 字符")
        String city,

        @NotBlank(message = "网上门店名称不能为空")
        @Size(max = 100, message = "网上门店名称最长 100 字符")
        String sourceName,

        @NotNull(message = "平台门店不能为空")
        Long venueId,

        @Size(max = 200, message = "备注最长 200 字符")
        String note
) {}
