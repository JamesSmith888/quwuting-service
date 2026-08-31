package org.quwuting.quwutingservice.resourceaccess.repository;

import jakarta.persistence.LockModeType;
import org.quwuting.quwutingservice.resourceaccess.entity.ResourceGrant;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantStatus;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ResourceGrantRepository extends JpaRepository<ResourceGrant, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM ResourceGrant g WHERE g.subjectUserId = :subjectUserId " +
            "AND g.resourceType = :resourceType AND g.resourceId = :resourceId AND g.deleted = false")
    Optional<ResourceGrant> findForUpdate(@Param("subjectUserId") Long subjectUserId,
                                          @Param("resourceType") ResourceType resourceType,
                                          @Param("resourceId") Long resourceId);

    Optional<ResourceGrant> findByIdAndDeletedFalse(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM ResourceGrant g WHERE g.id = :id AND g.deleted = false")
    Optional<ResourceGrant> findByIdAndDeletedFalseForUpdate(@Param("id") Long id);

    @Query("SELECT DISTINCT g FROM ResourceGrant g LEFT JOIN FETCH g.permissions " +
            "WHERE g.subjectUserId = :subjectUserId AND g.resourceType = :resourceType " +
            "AND g.resourceId = :resourceId AND g.deleted = false AND g.status = 'ACTIVE'")
    Optional<ResourceGrant> findEnabledWithPermissions(@Param("subjectUserId") Long subjectUserId,
                                                       @Param("resourceType") ResourceType resourceType,
                                                       @Param("resourceId") Long resourceId);

    @Query("SELECT g FROM ResourceGrant g WHERE g.deleted = false " +
            "AND (:subjectUserId IS NULL OR g.subjectUserId = :subjectUserId) " +
            "AND (:resourceType IS NULL OR g.resourceType = :resourceType) " +
            "AND (:resourceId IS NULL OR g.resourceId = :resourceId) " +
            "AND (:status IS NULL OR g.status = :status) ORDER BY g.id DESC")
    Page<ResourceGrant> findPage(@Param("subjectUserId") Long subjectUserId,
                                 @Param("resourceType") ResourceType resourceType,
                                 @Param("resourceId") Long resourceId,
                                 @Param("status") GrantStatus status,
                                 Pageable pageable);

    @Query("SELECT DISTINCT g FROM ResourceGrant g LEFT JOIN FETCH g.permissions " +
            "WHERE g.subjectUserId = :subjectUserId AND g.deleted = false AND g.status = 'ACTIVE' " +
            "AND (g.validFrom IS NULL OR g.validFrom <= :now) " +
            "AND (g.validUntil IS NULL OR g.validUntil > :now)")
    List<ResourceGrant> findAllActiveWithPermissions(@Param("subjectUserId") Long subjectUserId,
                                                     @Param("now") LocalDateTime now);
}