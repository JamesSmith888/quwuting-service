package org.quwuting.quwutingservice.resourceaccess.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.resourceaccess.dto.request.UpsertResourceGrantRequest;
import org.quwuting.quwutingservice.resourceaccess.dto.response.ResourceGrantResponse;
import org.quwuting.quwutingservice.resourceaccess.entity.ResourceGrant;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantAuditAction;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantStatus;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;
import org.quwuting.quwutingservice.resourceaccess.repository.ResourceGrantAuditRepository;
import org.quwuting.quwutingservice.resourceaccess.repository.ResourceGrantRepository;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceGrantServiceTest {

    @Mock
    private ResourceGrantRepository grantRepository;
    @Mock
    private ResourceGrantAuditRepository auditRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private DancerRepository dancerRepository;
    @Mock
    private ResourceAccessService resourceAccessService;

    private ResourceGrantService service;

    @BeforeEach
    void setUp() {
        service = new ResourceGrantService(grantRepository, auditRepository, userRepository,
                venueRepository, dancerRepository, resourceAccessService, new ObjectMapper());
        UserContext.set(99L, UserRole.ADMIN);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void upsertCreatesActiveGrantAndAudit() {
        User subject = new User();
        subject.setId(7L);
        subject.setRole(UserRole.USER);
        Venue venue = new Venue();
        venue.setId(20L);
        when(userRepository.findByIdAndDeletedFalseForUpdate(7L)).thenReturn(Optional.of(subject));
        when(venueRepository.findByIdAndDeletedFalse(20L)).thenReturn(Optional.of(venue));
        when(grantRepository.findForUpdate(7L, ResourceType.VENUE, 20L)).thenReturn(Optional.empty());
        when(grantRepository.save(any(ResourceGrant.class))).thenAnswer(invocation -> {
            ResourceGrant grant = invocation.getArgument(0);
            grant.setId(31L);
            return grant;
        });

        ResourceGrantResponse response = service.upsert(new UpsertResourceGrantRequest(
                7L, ResourceType.VENUE, 20L,
                Set.of(ResourcePermission.VENUE_PROFILE_EDIT), null, null, "负责资料核对"));

        assertEquals(GrantStatus.ACTIVE, response.status());
        assertTrue(response.permissions().contains(ResourcePermission.VENUE_PROFILE_EDIT));
        verify(auditRepository).save(org.mockito.ArgumentMatchers.argThat(
                audit -> audit.getAction() == GrantAuditAction.GRANTED));
        verify(resourceAccessService).invalidateUser(7L);
    }

    @Test
    void revokeDisablesGrantAndWritesAudit() {
        ResourceGrant grant = new ResourceGrant();
        grant.setId(31L);
        grant.setSubjectUserId(7L);
        grant.setResourceType(ResourceType.DANCER);
        grant.setResourceId(20L);
        grant.setStatus(GrantStatus.ACTIVE);
        grant.getPermissions().add(ResourcePermission.DANCER_PROFILE_EDIT);
        when(grantRepository.findByIdAndDeletedFalseForUpdate(31L)).thenReturn(Optional.of(grant));
        when(grantRepository.save(grant)).thenReturn(grant);

        ResourceGrantResponse response = service.revoke(31L, "协作已结束");

        assertEquals(GrantStatus.REVOKED, response.status());
        assertEquals("协作已结束", response.revokeReason());
        verify(auditRepository).save(org.mockito.ArgumentMatchers.argThat(
                audit -> audit.getAction() == GrantAuditAction.REVOKED));
        verify(resourceAccessService).invalidateUser(7L);
    }

    @Test
    void venueClaimMergesActiveGrantAndWritesUpdatedAudit() {
        User subject = new User();
        subject.setId(7L);
        subject.setRole(UserRole.USER);
        ResourceGrant grant = new ResourceGrant();
        grant.setId(31L);
        grant.setSubjectUserId(7L);
        grant.setResourceType(ResourceType.VENUE);
        grant.setResourceId(20L);
        grant.setStatus(GrantStatus.ACTIVE);
        grant.getPermissions().add(ResourcePermission.VENUE_PROFILE_EDIT);
        when(userRepository.findByIdAndDeletedFalseForUpdate(7L)).thenReturn(Optional.of(subject));
        when(grantRepository.findForUpdate(7L, ResourceType.VENUE, 20L)).thenReturn(Optional.of(grant));
        when(grantRepository.save(grant)).thenReturn(grant);

        service.grantVenueClaim(7L, 20L);

        assertTrue(grant.getPermissions().contains(ResourcePermission.VENUE_POST_MANAGE));
        assertTrue(grant.getPermissions().contains(ResourcePermission.VENUE_PHOTO_DELETE));
        verify(auditRepository).save(org.mockito.ArgumentMatchers.argThat(
                audit -> audit.getAction() == GrantAuditAction.UPDATED));
        verify(resourceAccessService).invalidateUser(7L);
    }
}