package org.quwuting.quwutingservice.resourceaccess.dto.response;

import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;

import java.time.LocalDateTime;
import java.util.Set;

public record ManagedResourceResponse(
        Long grantId,
        ResourceType resourceType,
        Long resourceId,
        String name,
        String city,
        String imageUrl,
        Set<ResourcePermission> capabilities,
        LocalDateTime validUntil,
        String managementTitle
) {}