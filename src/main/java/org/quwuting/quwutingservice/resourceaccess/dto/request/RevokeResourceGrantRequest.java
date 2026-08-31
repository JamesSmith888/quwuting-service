package org.quwuting.quwutingservice.resourceaccess.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RevokeResourceGrantRequest(
        @NotBlank @Size(max = 200) String reason
) {}