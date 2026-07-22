package org.quwuting.quwutingservice.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "code不能为空")
        String code
) {}
