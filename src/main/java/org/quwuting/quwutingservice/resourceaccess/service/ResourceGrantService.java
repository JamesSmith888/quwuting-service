package org.quwuting.quwutingservice.resourceaccess.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.resourceaccess.dto.request.UpsertResourceGrantRequest;
import org.quwuting.quwutingservice.resourceaccess.dto.response.ManagedResourceResponse;
import org.quwuting.quwutingservice.resourceaccess.dto.response.ResourceGrantAuditResponse;
import org.quwuting.quwutingservice.resourceaccess.dto.response.ResourceGrantResponse;
import org.quwuting.quwutingservice.resourceaccess.dto.response.ResourceSearchItemResponse;
import org.quwuting.quwutingservice.resourceaccess.entity.ResourceGrant;
import org.quwuting.quwutingservice.resourceaccess.entity.ResourceGrantAudit;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantAuditAction;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantSource;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantStatus;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;
import org.quwuting.quwutingservice.resourceaccess.repository.ResourceGrantAuditRepository;
import org.quwuting.quwutingservice.resourceaccess.repository.ResourceGrantRepository;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ResourceGrantService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ResourceGrantRepository grantRepository;
    private final ResourceGrantAuditRepository auditRepository;
    private final UserRepository userRepository;
    private final VenueRepository venueRepository;
    private final DancerRepository dancerRepository;
    private final ResourceAccessService resourceAccessService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ResourceGrantResponse upsert(UpsertResourceGrantRequest request) {
        Long actorUserId = UserContext.requireAdmin();
        User subject = userRepository.findByIdAndDeletedFalseForUpdate(request.subjectUserId())
                .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
        if (subject.getRole() == UserRole.ADMIN) {
            throw new BusinessException(1001, "该账号无需资料协作");
        }
        validateResource(request.resourceType(), request.resourceId());
        Set<ResourcePermission> permissions = validatedPermissions(request.resourceType(), request.permissions());
        validateValidity(request.validFrom(), request.validUntil());

        ResourceGrant grant = grantRepository.findForUpdate(request.subjectUserId(),
                        request.resourceType(), request.resourceId())
                .orElseGet(ResourceGrant::new);
        boolean created = grant.getId() == null;
        GrantStatus beforeStatus = created ? null : grant.getStatus();
        Set<ResourcePermission> beforePermissions = EnumSet.noneOf(ResourcePermission.class);
        beforePermissions.addAll(grant.getPermissions());
        LocalDateTime beforeValidFrom = grant.getValidFrom();
        LocalDateTime beforeValidUntil = grant.getValidUntil();
        String beforeNote = grant.getNote();

        if (created) {
            grant.setSubjectUserId(request.subjectUserId());
            grant.setResourceType(request.resourceType());
            grant.setResourceId(request.resourceId());
            grant.setSource(GrantSource.ADMIN_DIRECT);
            grant.setGrantedAt(LocalDateTime.now());
        } else if (grant.getStatus() == GrantStatus.REVOKED) {
            grant.setGrantedAt(LocalDateTime.now());
        }
        grant.setGrantedBy(actorUserId);
        grant.setStatus(GrantStatus.ACTIVE);
        grant.setValidFrom(request.validFrom());
        grant.setValidUntil(request.validUntil());
        grant.setRevokedBy(null);
        grant.setRevokedAt(null);
        grant.setRevokeReason(null);
        grant.setNote(trimToNull(request.note()));
        grant.getPermissions().clear();
        grant.getPermissions().addAll(permissions);

        boolean unchanged = !created && beforeStatus == GrantStatus.ACTIVE
                && beforePermissions.equals(permissions)
                && Objects.equals(beforeValidFrom, request.validFrom())
                && Objects.equals(beforeValidUntil, request.validUntil())
                && Objects.equals(beforeNote, grant.getNote());
        if (unchanged) {
            return toResponse(grant, LocalDateTime.now());
        }
        ResourceGrant saved = grantRepository.save(grant);
        GrantAuditAction action = created ? GrantAuditAction.GRANTED
            : beforeStatus == GrantStatus.REVOKED ? GrantAuditAction.REACTIVATED
            : GrantAuditAction.UPDATED;
        auditRepository.save(buildAudit(saved, actorUserId, action, beforeStatus,
            beforePermissions, permissions, beforeValidFrom, request.validFrom(),
            beforeValidUntil, request.validUntil(), null));
        invalidateAfterCommit(saved.getSubjectUserId());
        return toResponse(saved, LocalDateTime.now());
    }

        @Transactional
        public void grantVenueClaim(Long subjectUserId, Long venueId) {
        Long actorUserId = UserContext.requireAdmin();
        User subject = userRepository.findByIdAndDeletedFalseForUpdate(subjectUserId)
            .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
        if (subject.getRole() == UserRole.ADMIN) {
            return;
        }
        ResourceGrant grant = grantRepository.findForUpdate(subjectUserId, ResourceType.VENUE, venueId)
            .orElseGet(ResourceGrant::new);
        boolean created = grant.getId() == null;
        GrantStatus beforeStatus = created ? null : grant.getStatus();
        Set<ResourcePermission> beforePermissions = EnumSet.noneOf(ResourcePermission.class);
        beforePermissions.addAll(grant.getPermissions());
        LocalDateTime beforeValidFrom = grant.getValidFrom();
        LocalDateTime beforeValidUntil = grant.getValidUntil();
        Set<ResourcePermission> permissions = EnumSet.of(
            ResourcePermission.VENUE_PROFILE_EDIT,
            ResourcePermission.VENUE_POST_MANAGE,
            ResourcePermission.VENUE_PHOTO_DELETE);
        permissions.addAll(beforePermissions);
        LocalDateTime now = LocalDateTime.now();

        if (created) {
            grant.setSubjectUserId(subjectUserId);
            grant.setResourceType(ResourceType.VENUE);
            grant.setResourceId(venueId);
        }
        grant.setStatus(GrantStatus.ACTIVE);
        grant.setSource(GrantSource.CLAIM);
        grant.setValidFrom(null);
        grant.setValidUntil(null);
        grant.setGrantedBy(actorUserId);
        grant.setGrantedAt(now);
        grant.setRevokedBy(null);
        grant.setRevokedAt(null);
        grant.setRevokeReason(null);
        grant.getPermissions().clear();
        grant.getPermissions().addAll(permissions);
        ResourceGrant saved = grantRepository.save(grant);
        auditRepository.save(buildAudit(saved, actorUserId,
            created ? GrantAuditAction.GRANTED
                : beforeStatus == GrantStatus.REVOKED ? GrantAuditAction.REACTIVATED
                : GrantAuditAction.UPDATED,
                beforeStatus, beforePermissions, permissions, beforeValidFrom, null,
                beforeValidUntil, null, "门店认领审核通过"));
        invalidateAfterCommit(subjectUserId);
    }

    @Transactional
    public ResourceGrantResponse revoke(Long grantId, String reason) {
        Long actorUserId = UserContext.requireAdmin();
        ResourceGrant grant = grantRepository.findByIdAndDeletedFalseForUpdate(grantId)
                .orElseThrow(() -> new BusinessException(1001, "协作不存在"));
        String normalizedReason = trimToNull(reason);
        if (normalizedReason == null) {
            throw new BusinessException(1001, "请填写移除原因");
        }
        if (grant.getStatus() == GrantStatus.REVOKED) {
            return toResponse(grant, LocalDateTime.now());
        }
        GrantStatus beforeStatus = grant.getStatus();
        Set<ResourcePermission> permissions = EnumSet.noneOf(ResourcePermission.class);
        permissions.addAll(grant.getPermissions());
        LocalDateTime now = LocalDateTime.now();
        grant.setStatus(GrantStatus.REVOKED);
        grant.setRevokedBy(actorUserId);
        grant.setRevokedAt(now);
        grant.setRevokeReason(normalizedReason);
        ResourceGrant saved = grantRepository.save(grant);
        auditRepository.save(buildAudit(saved, actorUserId, GrantAuditAction.REVOKED,
                beforeStatus, permissions, permissions, grant.getValidFrom(), grant.getValidFrom(),
                grant.getValidUntil(), grant.getValidUntil(), normalizedReason));
        invalidateAfterCommit(saved.getSubjectUserId());
        return toResponse(saved, now);
    }

    @Transactional(readOnly = true)
    public Page<ResourceGrantResponse> list(Long subjectUserId, ResourceType resourceType,
                                            Long resourceId, GrantStatus status, int page, int size) {
        UserContext.requireAdmin();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        LocalDateTime now = LocalDateTime.now();
        return grantRepository.findPage(subjectUserId, resourceType, resourceId, status,
                        PageRequest.of(safePage, safeSize))
                .map(grant -> toResponse(grant, now));
    }

    @Transactional(readOnly = true)
    public List<ResourceGrantAuditResponse> audits(Long grantId) {
        UserContext.requireAdmin();
        grantRepository.findByIdAndDeletedFalse(grantId)
                .orElseThrow(() -> new BusinessException(1001, "协作不存在"));
        return auditRepository.findByGrantIdOrderByCreatedAtDescIdDesc(grantId).stream()
                .map(this::toAuditResponse)
                .toList();
    }

    /**
     * 管理端资源模糊搜索（2026-08-31：新增协作页「选择门店或舞伴」数据源）。
     * 门店 = 名称/地址匹配，舞伴 = 昵称匹配；不限资源状态（协作目标不要求公开可见）。
     * 空关键字返回空列表；limit 上限 20（列表选择场景足够，防误查大表）。
     */
    @Transactional(readOnly = true)
    public List<ResourceSearchItemResponse> searchResources(ResourceType resourceType, String keyword, int limit) {
        UserContext.requireAdmin();
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String kw = "%" + keyword.trim() + "%";
        int safeLimit = Math.min(Math.max(1, limit), 20);
        Pageable pageable = PageRequest.of(0, safeLimit);
        List<ResourceSearchItemResponse> items = new ArrayList<>(safeLimit);
        if (resourceType == ResourceType.VENUE) {
            for (Object[] row : venueRepository.searchGrantTarget(kw, pageable)) {
                Long id = ((Number) row[0]).longValue();
                String city = (String) row[2];
                String district = (String) row[3];
                items.add(new ResourceSearchItemResponse(id, (String) row[1], city,
                        (String) row[4], subLabel(city, district)));
            }
        } else {
            for (Object[] row : dancerRepository.searchGrantTarget(kw, pageable)) {
                Long id = ((Number) row[0]).longValue();
                String city = (String) row[2];
                items.add(new ResourceSearchItemResponse(id, (String) row[1], city,
                        (String) row[3], city));
            }
        }
        return items;
    }

    private static String subLabel(String city, String district) {
        boolean hasCity = city != null && !city.isBlank();
        boolean hasDistrict = district != null && !district.isBlank();
        if (hasCity && hasDistrict) {
            return city + " · " + district;
        }
        return hasCity ? city : (hasDistrict ? district : null);
    }

    /**
     * 按 ID 取资源信息（2026-08-31：编辑已有协作时回显资源名——编辑入口只带
     * resourceType + resourceId，名字需回查。与搜索同口径：不限资源状态）。
     */
    @Transactional(readOnly = true)
    public ResourceSearchItemResponse resourceInfo(ResourceType resourceType, Long resourceId) {
        UserContext.requireAdmin();
        if (resourceId == null || resourceId <= 0) {
            throw new BusinessException(1001, "资源不存在");
        }
        if (resourceType == ResourceType.VENUE) {
            Venue venue = venueRepository.findByIdAndDeletedFalse(resourceId)
                    .orElseThrow(() -> new BusinessException(1001, "资源不存在"));
            return new ResourceSearchItemResponse(venue.getId(), venue.getName(), venue.getCity(),
                    venue.getImageUrl(), subLabel(venue.getCity(), venue.getDistrict()));
        }
        Dancer dancer = dancerRepository.findByIdAndDeletedFalse(resourceId)
                .orElseThrow(() -> new BusinessException(1001, "资源不存在"));
        return new ResourceSearchItemResponse(dancer.getId(), dancer.getNickname(), dancer.getCity(),
                dancer.getAvatarUrl(), dancer.getCity());
    }

            @Transactional(readOnly = true)
            public List<ManagedResourceResponse> managedResources(Long userId, ResourceType resourceType) {
            List<ResourceGrant> grants = grantRepository.findAllActiveWithPermissions(userId, LocalDateTime.now()).stream()
                .filter(grant -> resourceType == null || grant.getResourceType() == resourceType)
                .toList();
            List<Long> venueIds = grants.stream()
                .filter(grant -> grant.getResourceType() == ResourceType.VENUE)
                .map(ResourceGrant::getResourceId).distinct().toList();
            List<Long> dancerIds = grants.stream()
                .filter(grant -> grant.getResourceType() == ResourceType.DANCER)
                .map(ResourceGrant::getResourceId).distinct().toList();
            Map<Long, Venue> venues = venueIds.isEmpty() ? Map.of()
                : venueRepository.findByIdInAndDeletedFalse(venueIds).stream()
                    .collect(Collectors.toMap(Venue::getId, Function.identity()));
            Map<Long, Dancer> dancers = dancerIds.isEmpty() ? Map.of()
                : dancerRepository.findByIds(dancerIds).stream()
                    .collect(Collectors.toMap(Dancer::getId, Function.identity()));
            List<ManagedResourceResponse> result = new ArrayList<>();
            for (ResourceGrant grant : grants) {
                if (grant.getResourceType() == ResourceType.VENUE) {
                Venue venue = venues.get(grant.getResourceId());
                if (venue != null) {
                    result.add(new ManagedResourceResponse(grant.getId(), ResourceType.VENUE, venue.getId(),
                        venue.getName(), venue.getCity(), venue.getImageUrl(), Set.copyOf(grant.getPermissions()),
                        grant.getValidUntil(), "资料协作者"));
                }
                } else {
                Dancer dancer = dancers.get(grant.getResourceId());
                if (dancer != null) {
                    result.add(new ManagedResourceResponse(grant.getId(), ResourceType.DANCER, dancer.getId(),
                        dancer.getNickname(), dancer.getCity(), dancer.getAvatarUrl(), Set.copyOf(grant.getPermissions()),
                        grant.getValidUntil(), "资料协作者"));
                }
                }
            }
            return result;
            }

    private void validateResource(ResourceType resourceType, Long resourceId) {
        if (resourceId == null || resourceId <= 0) {
            throw new BusinessException(1001, "资源不存在");
        }
        boolean exists = switch (resourceType) {
            case VENUE -> venueRepository.findByIdAndDeletedFalse(resourceId).isPresent();
            case DANCER -> dancerRepository.findByIdAndDeletedFalse(resourceId).isPresent();
        };
        if (!exists) {
            throw new BusinessException(1001, "资源不存在");
        }
    }

    private Set<ResourcePermission> validatedPermissions(ResourceType resourceType,
                                                         Set<ResourcePermission> requested) {
        EnumSet<ResourcePermission> permissions = EnumSet.noneOf(ResourcePermission.class);
        permissions.addAll(requested);
        if (permissions.isEmpty() || permissions.stream().anyMatch(permission -> !permission.supports(resourceType))) {
            throw new BusinessException(1001, "权限与资源类型不匹配");
        }
        if (permissions.contains(ResourcePermission.DANCER_GATE_MANAGE)
                && !permissions.contains(ResourcePermission.DANCER_PROFILE_EDIT)
                && !permissions.contains(ResourcePermission.DANCER_MEDIA_MANAGE)) {
            throw new BusinessException(1001, "积分门槛权限必须与资料或媒体维护权限一同授予");
        }
        return permissions;
    }

    private void validateValidity(LocalDateTime validFrom, LocalDateTime validUntil) {
        if (validUntil != null && !validUntil.isAfter(LocalDateTime.now())) {
            throw new BusinessException(1001, "结束日期必须晚于当前时间");
        }
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new BusinessException(1001, "结束日期必须晚于开始日期");
        }
    }

    private ResourceGrantAudit buildAudit(ResourceGrant grant, Long actorUserId,
                                           GrantAuditAction action, GrantStatus fromStatus,
                                           Set<ResourcePermission> beforePermissions,
                                           Set<ResourcePermission> afterPermissions,
                                           LocalDateTime beforeValidFrom, LocalDateTime afterValidFrom,
                                           LocalDateTime beforeValidUntil, LocalDateTime afterValidUntil,
                                           String reason) {
        ResourceGrantAudit audit = new ResourceGrantAudit();
        audit.setGrantId(grant.getId());
        audit.setActorUserId(actorUserId);
        audit.setAction(action);
        audit.setFromStatus(fromStatus);
        audit.setToStatus(grant.getStatus());
        audit.setBeforePermissions(serializePermissions(beforePermissions));
        audit.setAfterPermissions(serializePermissions(afterPermissions));
        audit.setBeforeValidFrom(beforeValidFrom);
        audit.setAfterValidFrom(afterValidFrom);
        audit.setBeforeValidUntil(beforeValidUntil);
        audit.setAfterValidUntil(afterValidUntil);
        audit.setReason(reason);
        return audit;
    }

    private String serializePermissions(Set<ResourcePermission> permissions) {
        List<String> names = new ArrayList<>();
        permissions.stream().map(Enum::name).sorted(Comparator.naturalOrder()).forEach(names::add);
        try {
            return objectMapper.writeValueAsString(names);
        } catch (Exception e) {
            throw new IllegalStateException("授权权限快照序列化失败", e);
        }
    }

    private ResourceGrantResponse toResponse(ResourceGrant grant, LocalDateTime now) {
        EnumSet<ResourcePermission> permissions = EnumSet.noneOf(ResourcePermission.class);
        permissions.addAll(grant.getPermissions());
        boolean active = grant.getStatus() == GrantStatus.ACTIVE
                && (grant.getValidFrom() == null || !grant.getValidFrom().isAfter(now))
                && (grant.getValidUntil() == null || grant.getValidUntil().isAfter(now));
        return new ResourceGrantResponse(grant.getId(), grant.getSubjectUserId(), grant.getResourceType(),
                grant.getResourceId(), grant.getStatus(), active, grant.getSource(), Set.copyOf(permissions),
                grant.getValidFrom(), grant.getValidUntil(), grant.getGrantedBy(), grant.getGrantedAt(),
                grant.getRevokedBy(), grant.getRevokedAt(), grant.getRevokeReason(), grant.getNote(),
                grant.getUpdatedAt());
    }

    private ResourceGrantAuditResponse toAuditResponse(ResourceGrantAudit audit) {
        return new ResourceGrantAuditResponse(audit.getId(), audit.getActorUserId(), audit.getAction(),
                audit.getFromStatus(), audit.getToStatus(), audit.getBeforePermissions(),
                audit.getAfterPermissions(), audit.getBeforeValidFrom(), audit.getAfterValidFrom(),
                audit.getBeforeValidUntil(), audit.getAfterValidUntil(), audit.getReason(), audit.getCreatedAt());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void invalidateAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            resourceAccessService.invalidateUser(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                resourceAccessService.invalidateUser(userId);
            }
        });
    }
}