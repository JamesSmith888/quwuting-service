package org.quwuting.quwutingservice.groupchat.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.groupchat.enums.GroupChatScope;

/**
 * 舞友群创建 / 更新请求（管理端，ADMIN 鉴权；创建与更新共用——更新为全量覆盖，
 * enabled 不在请求内，由上下线接口独立管理）。
 * <p>
 * 维度一致性校验（scope ↔ city / region 互斥必填）在 GroupChatService 统一做
 * （跨字段业务规则，不属单字段 Bean Validation 职责）。
 */
public record GroupChatUpsertRequest(

        @NotBlank(message = "群名称不能为空")
        @Size(max = 64, message = "群名称不能超过 64 字")
        String name,

        @NotNull(message = "群维度不能为空")
        GroupChatScope scope,

        @Size(max = 50, message = "城市不能超过 50 字")
        String city,

        @Size(max = 50, message = "地域不能超过 50 字")
        String region,

        @NotBlank(message = "群二维码不能为空")
        @Size(max = 500, message = "二维码地址过长")
        String qrCodeUrl,

        @Size(max = 200, message = "群简介不能超过 200 字")
        String description,

        @Min(value = 0, message = "排序不能为负")
        @Max(value = 9999, message = "排序过大")
        Integer displayOrder
) {
}
