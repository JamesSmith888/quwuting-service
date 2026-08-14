package org.quwuting.quwutingservice.opsconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 运营配置更新请求（仅 ADMIN；POST——项目禁 PUT/PATCH）。
 * key 必须已存在（新增键的唯一通道 = Flyway 迁移），防管理端手滑造出无人消费的配置。
 */
public record OpsConfigUpdateRequest(
        @NotBlank @Size(max = 64) String key,
        @NotBlank @Size(max = 255) String value) {
}
