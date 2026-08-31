package org.quwuting.quwutingservice.resourceaccess.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.BatchSize;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantSource;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantStatus;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "qwt_resource_grants",
        uniqueConstraints = @UniqueConstraint(name = "qwt_uk_resource_grant_subject",
                columnNames = {"subject_user_id", "resource_type", "resource_id"}),
        indexes = {
                @Index(name = "qwt_idx_resource_grants_subject", columnList = "subject_user_id,status,valid_until"),
                @Index(name = "qwt_idx_resource_grants_resource", columnList = "resource_type,resource_id,status,valid_until")
        })
public class ResourceGrant extends BaseEntity {

    @Version
    @Column(nullable = false)
    @ColumnDefault("0")
    private long version;

    @Column(name = "subject_user_id", nullable = false)
    private Long subjectUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private ResourceType resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'ACTIVE'")
    private GrantStatus status = GrantStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @ColumnDefault("'ADMIN_DIRECT'")
    private GrantSource source = GrantSource.ADMIN_DIRECT;

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    @Column(nullable = false)
    private Long grantedBy;

    @Column(nullable = false)
    private LocalDateTime grantedAt;

    private Long revokedBy;

    private LocalDateTime revokedAt;

    @Column(length = 200)
    private String revokeReason;

    @Column(length = 500)
    private String note;

    @ElementCollection(fetch = FetchType.LAZY)
        @BatchSize(size = 50)
    @CollectionTable(name = "qwt_resource_grant_permissions",
            joinColumns = @JoinColumn(name = "grant_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission_code", nullable = false, length = 64)
    private Set<ResourcePermission> permissions = new LinkedHashSet<>();
}