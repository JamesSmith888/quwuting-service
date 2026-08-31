package org.quwuting.quwutingservice.resourceaccess.dto.response;

import org.quwuting.quwutingservice.resourceaccess.enums.GrantAuditAction;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantStatus;

import java.time.LocalDateTime;

public record ResourceGrantAuditResponse(
        Long id,
        Long actorUserId,
        GrantAuditAction action,
        GrantStatus fromStatus,
        GrantStatus toStatus,
        String beforePermissions,
        String afterPermissions,
        LocalDateTime beforeValidFrom,
        LocalDateTime afterValidFrom,
        LocalDateTime beforeValidUntil,
        LocalDateTime afterValidUntil,
        String reason,
        LocalDateTime createdAt
) {}