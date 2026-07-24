package org.quwuting.quwutingservice.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "code不能为空")
        @Size(max = 128, message = "code格式无效")
        String code
) {}
