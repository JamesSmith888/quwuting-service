package org.quwuting.quwutingservice.resourceaccess.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ResourcePermission {
    VENUE_PROFILE_EDIT(ResourceType.VENUE),
    VENUE_POST_MANAGE(ResourceType.VENUE),
    VENUE_PHOTO_DELETE(ResourceType.VENUE),
    DANCER_PROFILE_EDIT(ResourceType.DANCER),
    DANCER_MEDIA_MANAGE(ResourceType.DANCER),
    DANCER_SERVICE_MANAGE(ResourceType.DANCER),
    DANCER_GATE_MANAGE(ResourceType.DANCER),
    DANCER_DEMAND_RECORDS_READ(ResourceType.DANCER);

    private final ResourceType resourceType;

    public boolean supports(ResourceType type) {
        return resourceType == type;
    }
}