package org.quwuting.quwutingservice.resourceaccess.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.resourceaccess.entity.ResourceGrant;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;
import org.quwuting.quwutingservice.resourceaccess.repository.ResourceGrantRepository;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

@Service
public class ResourceAccessService {

    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final ResourceGrantRepository grantRepository;
    private final Cache<AccessKey, AccessSnapshot> permissionCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public ResourceAccessService(ResourceGrantRepository grantRepository) {
        this.grantRepository = grantRepository;
    }

    public boolean canCurrentUser(ResourceType resourceType, Long resourceId,
                                  ResourcePermission permission) {
        Long userId = UserContext.getCurrentUserId();
        return userId != null && hasPermission(userId, UserContext.getCurrentRole(),
                resourceType, resourceId, permission);
    }

    public Long requireCurrentUser(ResourceType resourceType, Long resourceId,
                                   ResourcePermission permission) {
        Long userId = UserContext.requireAuth();
        if (!hasPermission(userId, UserContext.getCurrentRole(), resourceType, resourceId, permission)) {
            throw new BusinessException(1003, "无该资料的维护权限");
        }
        return userId;
    }

    public boolean hasPermission(Long userId, UserRole role, ResourceType resourceType,
                                 Long resourceId, ResourcePermission permission) {
        validatePermissionType(resourceType, permission);
        if (role == UserRole.ADMIN) {
            return true;
        }
        if (userId == null || resourceId == null) {
            return false;
        }
        return capabilitiesFor(userId, resourceType, resourceId).contains(permission);
    }

    @Transactional(readOnly = true)
    public Set<ResourcePermission> capabilitiesFor(Long userId, ResourceType resourceType, Long resourceId) {
        if (userId == null || resourceType == null || resourceId == null) {
            return Set.of();
        }
        AccessKey key = new AccessKey(userId, resourceType, resourceId);
        AccessSnapshot snapshot = permissionCache.get(key,
            ignored -> loadSnapshot(userId, resourceType, resourceId));
        LocalDateTime now = LocalDateTime.now();
        if ((snapshot.validFrom() != null && snapshot.validFrom().isAfter(now))
            || (snapshot.validUntil() != null && !snapshot.validUntil().isAfter(now))) {
            return Set.of();
        }
        return snapshot.permissions();
    }

    public void invalidateUser(Long userId) {
        permissionCache.asMap().keySet().removeIf(key -> key.userId().equals(userId));
    }

    private AccessSnapshot loadSnapshot(Long userId, ResourceType resourceType, Long resourceId) {
        return grantRepository.findEnabledWithPermissions(userId, resourceType, resourceId)
                .map(grant -> {
                    EnumSet<ResourcePermission> copy = EnumSet.noneOf(ResourcePermission.class);
                    copy.addAll(grant.getPermissions());
                return new AccessSnapshot(Collections.unmodifiableSet(copy),
                    grant.getValidFrom(), grant.getValidUntil());
                })
            .orElseGet(() -> new AccessSnapshot(Set.of(), null, null));
    }

    private void validatePermissionType(ResourceType resourceType, ResourcePermission permission) {
        if (resourceType == null || permission == null || !permission.supports(resourceType)) {
            throw new IllegalArgumentException("权限与资源类型不匹配");
        }
    }

    private record AccessKey(Long userId, ResourceType resourceType, Long resourceId) {}

    private record AccessSnapshot(Set<ResourcePermission> permissions, LocalDateTime validFrom,
                                  LocalDateTime validUntil) {}
}