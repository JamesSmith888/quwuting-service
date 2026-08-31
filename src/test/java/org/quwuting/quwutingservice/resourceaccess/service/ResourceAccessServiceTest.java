package org.quwuting.quwutingservice.resourceaccess.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.resourceaccess.entity.ResourceGrant;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;
import org.quwuting.quwutingservice.resourceaccess.repository.ResourceGrantRepository;
import org.quwuting.quwutingservice.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceAccessServiceTest {

    @Mock
    private ResourceGrantRepository grantRepository;

    @Test
    void adminBypassesResourceLookup() {
        ResourceAccessService service = new ResourceAccessService(grantRepository);

        assertTrue(service.hasPermission(1L, UserRole.ADMIN, ResourceType.VENUE, 9L,
                ResourcePermission.VENUE_PROFILE_EDIT));
        verify(grantRepository, never()).findEnabledWithPermissions(any(), any(), any());
    }

    @Test
    void grantOnlyAllowsExplicitPermissionAndResource() {
        ResourceAccessService service = new ResourceAccessService(grantRepository);
        ResourceGrant grant = new ResourceGrant();
        grant.getPermissions().add(ResourcePermission.DANCER_PROFILE_EDIT);
        when(grantRepository.findEnabledWithPermissions(any(), any(), any()))
                .thenReturn(Optional.of(grant));

        assertTrue(service.hasPermission(7L, UserRole.USER, ResourceType.DANCER, 20L,
                ResourcePermission.DANCER_PROFILE_EDIT));
        assertFalse(service.hasPermission(7L, UserRole.USER, ResourceType.DANCER, 20L,
                ResourcePermission.DANCER_DEMAND_RECORDS_READ));
    }

    @Test
    void invalidationForcesPermissionReload() {
        ResourceAccessService service = new ResourceAccessService(grantRepository);
        ResourceGrant grant = new ResourceGrant();
        grant.getPermissions().add(ResourcePermission.VENUE_PROFILE_EDIT);
        when(grantRepository.findEnabledWithPermissions(any(), any(), any()))
                .thenReturn(Optional.of(grant), Optional.empty());

        assertTrue(service.hasPermission(7L, UserRole.USER, ResourceType.VENUE, 20L,
                ResourcePermission.VENUE_PROFILE_EDIT));
        service.invalidateUser(7L);
        assertFalse(service.hasPermission(7L, UserRole.USER, ResourceType.VENUE, 20L,
                ResourcePermission.VENUE_PROFILE_EDIT));
        verify(grantRepository, org.mockito.Mockito.times(2))
                .findEnabledWithPermissions(any(), any(), any());
    }

    @Test
    void cachedGrantStopsImmediatelyAtValidUntil() {
        ResourceAccessService service = new ResourceAccessService(grantRepository);
        ResourceGrant grant = new ResourceGrant();
        grant.setValidUntil(LocalDateTime.now().minusSeconds(1));
        grant.getPermissions().add(ResourcePermission.VENUE_PROFILE_EDIT);
        when(grantRepository.findEnabledWithPermissions(any(), any(), any()))
                .thenReturn(Optional.of(grant));

        assertFalse(service.hasPermission(7L, UserRole.USER, ResourceType.VENUE, 20L,
                ResourcePermission.VENUE_PROFILE_EDIT));
    }

    @Test
    void cachedGrantStartsExactlyAtValidFrom() {
        ResourceAccessService service = new ResourceAccessService(grantRepository);
        ResourceGrant grant = new ResourceGrant();
        grant.setValidFrom(LocalDateTime.now().plusMinutes(1));
        grant.getPermissions().add(ResourcePermission.DANCER_PROFILE_EDIT);
        when(grantRepository.findEnabledWithPermissions(any(), any(), any()))
                .thenReturn(Optional.of(grant));

        assertFalse(service.hasPermission(7L, UserRole.USER, ResourceType.DANCER, 20L,
                ResourcePermission.DANCER_PROFILE_EDIT));
    }

    @Test
    void rejectsPermissionFromAnotherResourceType() {
        ResourceAccessService service = new ResourceAccessService(grantRepository);

        assertThrows(IllegalArgumentException.class,
                () -> service.hasPermission(7L, UserRole.USER, ResourceType.VENUE, 20L,
                        ResourcePermission.DANCER_PROFILE_EDIT));
    }
}