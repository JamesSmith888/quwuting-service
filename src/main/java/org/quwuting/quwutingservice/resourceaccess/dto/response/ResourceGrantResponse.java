package org.quwuting.quwutingservice.resourceaccess.dto.response;

import org.quwuting.quwutingservice.resourceaccess.enums.GrantSource;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantStatus;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;

import java.time.LocalDateTime;
import java.util.Set;

public record ResourceGrantResponse(
        Long id,
        Long subjectUserId,
        ResourceType resourceType,
        Long resourceId,
        GrantStatus status,
        boolean active,
        GrantSource source,
        Set<ResourcePermission> permissions,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        Long grantedBy,
        LocalDateTime grantedAt,
        Long revokedBy,
        LocalDateTime revokedAt,
        String revokeReason,
        String note,
        LocalDateTime updatedAt
) {}