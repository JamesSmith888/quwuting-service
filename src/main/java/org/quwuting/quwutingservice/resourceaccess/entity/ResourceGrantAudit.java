package org.quwuting.quwutingservice.resourceaccess.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantAuditAction;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantStatus;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "qwt_resource_grant_audits", indexes = {
        @Index(name = "qwt_idx_resource_grant_audits_grant", columnList = "grant_id,created_at")
})
public class ResourceGrantAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private GrantAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private GrantStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GrantStatus toStatus;

    @Column(length = 1000)
    private String beforePermissions;

    @Column(nullable = false, length = 1000)
    private String afterPermissions;

    private LocalDateTime beforeValidFrom;

    private LocalDateTime afterValidFrom;

    private LocalDateTime beforeValidUntil;

    private LocalDateTime afterValidUntil;

    @Column(length = 200)
    private String reason;
}