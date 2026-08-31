-- 平台授权的资源级权限（MySQL 8）：与 PostgreSQL V62 实体映射保持一致。
CREATE TABLE qwt_resource_grants (
    id               bigint NOT NULL AUTO_INCREMENT,
    created_at       datetime(6),
    updated_at       datetime(6),
    deleted          tinyint(1) NOT NULL DEFAULT 0,
    version          bigint NOT NULL DEFAULT 0,
    subject_user_id  bigint NOT NULL,
    resource_type    varchar(20) NOT NULL,
    resource_id      bigint NOT NULL,
    status           varchar(20) NOT NULL DEFAULT 'ACTIVE',
    source           varchar(30) NOT NULL DEFAULT 'ADMIN_DIRECT',
    valid_from       datetime(6),
    valid_until      datetime(6),
    granted_by       bigint NOT NULL,
    granted_at       datetime(6) NOT NULL,
    revoked_by       bigint,
    revoked_at       datetime(6),
    revoke_reason    varchar(200),
    note             varchar(500),
    PRIMARY KEY (id),
    UNIQUE KEY qwt_uk_resource_grant_subject (subject_user_id, resource_type, resource_id),
    KEY qwt_idx_resource_grants_subject (subject_user_id, status, valid_until),
    KEY qwt_idx_resource_grants_resource (resource_type, resource_id, status, valid_until)
);

CREATE TABLE qwt_resource_grant_permissions (
    grant_id          bigint NOT NULL,
    permission_code   varchar(64) NOT NULL,
    PRIMARY KEY (grant_id, permission_code)
);

CREATE TABLE qwt_resource_grant_audits (
    id                    bigint NOT NULL AUTO_INCREMENT,
    created_at            datetime(6),
    grant_id              bigint NOT NULL,
    actor_user_id         bigint NOT NULL,
    action                varchar(30) NOT NULL,
    from_status           varchar(20),
    to_status             varchar(20) NOT NULL,
    before_permissions    varchar(1000),
    after_permissions     varchar(1000) NOT NULL,
    before_valid_from     datetime(6),
    after_valid_from      datetime(6),
    before_valid_until    datetime(6),
    after_valid_until     datetime(6),
    reason                varchar(200),
    PRIMARY KEY (id),
    KEY qwt_idx_resource_grant_audits_grant (grant_id, created_at)
);

INSERT INTO qwt_resource_grants (
    deleted, version, subject_user_id, resource_type, resource_id, status, source,
    granted_by, granted_at, created_at, updated_at
)
SELECT 0, 0, v.claimed_by, 'VENUE', v.id, 'ACTIVE', 'CLAIM',
       v.claimed_by, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM qwt_venues v
JOIN qwt_users u ON u.id = v.claimed_by AND u.deleted = 0
WHERE v.deleted = 0 AND v.claimed_by IS NOT NULL AND u.role <> 'ADMIN';

INSERT INTO qwt_resource_grant_permissions (grant_id, permission_code)
SELECT g.id, p.permission_code
FROM qwt_resource_grants g
JOIN (
    SELECT 'VENUE_PROFILE_EDIT' AS permission_code
    UNION ALL SELECT 'VENUE_POST_MANAGE'
    UNION ALL SELECT 'VENUE_PHOTO_DELETE'
) p ON 1 = 1
WHERE g.source = 'CLAIM' AND g.resource_type = 'VENUE';

INSERT INTO qwt_resource_grants (
    deleted, version, subject_user_id, resource_type, resource_id, status, source,
    granted_by, granted_at, created_at, updated_at
)
SELECT 0, 0, d.created_by, 'DANCER', d.id, 'ACTIVE', 'LEGACY_CREATED_BY',
       d.created_by, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM qwt_dancers d
JOIN qwt_users u ON u.id = d.created_by AND u.deleted = 0
WHERE d.deleted = 0 AND u.role <> 'ADMIN';

INSERT INTO qwt_resource_grant_permissions (grant_id, permission_code)
SELECT g.id, p.permission_code
FROM qwt_resource_grants g
JOIN (
    SELECT 'DANCER_PROFILE_EDIT' AS permission_code
    UNION ALL SELECT 'DANCER_MEDIA_MANAGE'
    UNION ALL SELECT 'DANCER_SERVICE_MANAGE'
    UNION ALL SELECT 'DANCER_GATE_MANAGE'
    UNION ALL SELECT 'DANCER_DEMAND_RECORDS_READ'
) p ON 1 = 1
WHERE g.source = 'LEGACY_CREATED_BY' AND g.resource_type = 'DANCER';