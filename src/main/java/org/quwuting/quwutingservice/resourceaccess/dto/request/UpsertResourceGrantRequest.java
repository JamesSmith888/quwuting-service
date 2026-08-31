package org.quwuting.quwutingservice.resourceaccess.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;

import java.time.LocalDateTime;
import java.util.Set;

public record UpsertResourceGrantRequest(
        @NotNull Long subjectUserId,
        @NotNull ResourceType resourceType,
        @NotNull Long resourceId,
        @NotEmpty Set<@NotNull ResourcePermission> permissions,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        @Size(max = 500) String note
) {}