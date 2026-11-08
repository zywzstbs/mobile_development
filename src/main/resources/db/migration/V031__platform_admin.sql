-- P66 平台管理：超级管理员角色、停用状态、授权范围与再认证。
-- 不改写 V001–V030，不批量提权，不把历史停用账号恢复成已审核。

ALTER TABLE app_user
    ADD COLUMN eligibility_source varchar(40);

UPDATE app_user
SET eligibility_source = CASE
    WHEN disabled AND account_status = 'PENDING_REVIEW' THEN 'LEGACY_DISABLED_PENDING'
    WHEN disabled AND account_status = 'REJECTED' THEN 'LEGACY_DISABLED_REJECTED'
    WHEN disabled AND account_status = 'ACTIVE' THEN 'LEGACY_DISABLED_UNCLEAR'
    WHEN account_status = 'PENDING_REVIEW' THEN 'REGISTRATION_PENDING'
    WHEN account_status = 'REJECTED' THEN 'REGISTRATION_REJECTED'
    ELSE 'VERIFIED_ACTIVE'
END;

ALTER TABLE app_user
    ALTER COLUMN eligibility_source SET DEFAULT 'VERIFIED_ACTIVE';

ALTER TABLE app_user
    ALTER COLUMN eligibility_source SET NOT NULL;

-- 先放开旧检查，再把历史 disabled=true 且仍标 ACTIVE 的账号改为 DISABLED。
ALTER TABLE app_user DROP CONSTRAINT ck_app_user_account_status;

UPDATE app_user
SET account_status = 'DISABLED'
WHERE disabled = true AND account_status = 'ACTIVE';

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_account_status
        CHECK (account_status IN ('ACTIVE', 'PENDING_REVIEW', 'REJECTED', 'DISABLED'));

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_eligibility
        CHECK (eligibility_source IN (
            'VERIFIED_ACTIVE',
            'ADMIN_DISABLED',
            'REGISTRATION_PENDING',
            'REGISTRATION_REJECTED',
            'LEGACY_DISABLED_UNCLEAR',
            'LEGACY_DISABLED_PENDING',
            'LEGACY_DISABLED_REJECTED',
            'IMPORT_DISABLED'
        ));

-- ACTIVE 不能同时停用；DISABLED 必须带停用标记。待审/驳回可以保留历史 disabled。
ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_active_not_disabled
        CHECK (NOT (account_status = 'ACTIVE' AND disabled));

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_disabled_status
        CHECK (account_status <> 'DISABLED' OR disabled);

ALTER TABLE user_role DROP CONSTRAINT ck_user_role_value;

ALTER TABLE user_role
    ADD CONSTRAINT ck_user_role_value
        CHECK (role IN ('STUDENT', 'TEACHER', 'CLASS_ADMIN', 'SUPER_ADMIN'));

CREATE TABLE platform_admin_grant (
    id            varchar(120) PRIMARY KEY,
    user_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status        varchar(20)  NOT NULL,
    granted_by    varchar(120),
    granted_at    timestamptz  NOT NULL,
    revoked_by    varchar(120),
    revoked_at    timestamptz,
    revoke_reason varchar(500),
    revision      int          NOT NULL DEFAULT 1,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_platform_admin_grant_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_platform_admin_grant_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_platform_admin_grant_revoke CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_platform_admin_grant_active
    ON platform_admin_grant (user_id) WHERE status = 'ACTIVE';

CREATE INDEX ix_platform_admin_grant_user ON platform_admin_grant (user_id, status);

CREATE TABLE platform_admin_capability (
    grant_id   varchar(120) NOT NULL REFERENCES platform_admin_grant(id) ON DELETE RESTRICT,
    capability varchar(40)  NOT NULL,
    PRIMARY KEY (grant_id, capability),
    CONSTRAINT ck_platform_admin_capability CHECK (capability IN (
        'PLATFORM_ACCOUNT_READ',
        'PLATFORM_ACCOUNT_MANAGE',
        'PLATFORM_TEACHER_REVIEW',
        'PLATFORM_COURSE_GOVERNANCE',
        'PLATFORM_ROUTING_MANAGE',
        'PLATFORM_AUDIT_READ',
        'PLATFORM_SUPER_ADMIN_MANAGE'
    ))
);

CREATE TABLE platform_admin_scope (
    grant_id   varchar(120) NOT NULL REFERENCES platform_admin_grant(id) ON DELETE RESTRICT,
    scope_type varchar(20)  NOT NULL,
    school_id  varchar(120) REFERENCES school(id) ON DELETE RESTRICT,
    CONSTRAINT ck_platform_admin_scope_type CHECK (scope_type IN ('PLATFORM', 'SCHOOL')),
    CONSTRAINT ck_platform_admin_scope_school CHECK (
        (scope_type = 'PLATFORM' AND school_id IS NULL)
        OR (scope_type = 'SCHOOL' AND school_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_platform_admin_scope
    ON platform_admin_scope (grant_id, scope_type, school_id) NULLS NOT DISTINCT;

-- 所有会减少可用恢复管理员的路径共用这一行，避免只在单一接口里数角色。
CREATE TABLE platform_admin_lock (
    id       int PRIMARY KEY,
    revision int NOT NULL DEFAULT 1,
    CONSTRAINT ck_platform_admin_lock_singleton CHECK (id = 1),
    CONSTRAINT ck_platform_admin_lock_revision CHECK (revision BETWEEN 1 AND 999999999)
);

INSERT INTO platform_admin_lock (id) VALUES (1);

CREATE TABLE admin_reauthentication (
    id                 varchar(120) PRIMARY KEY,
    token_hash         char(64)     NOT NULL,
    actor_id           varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    session_id         varchar(120) NOT NULL REFERENCES auth_session(id) ON DELETE RESTRICT,
    operation_id       varchar(120) NOT NULL,
    action             varchar(80)  NOT NULL,
    target_type        varchar(80)  NOT NULL,
    target_id          varchar(120) NOT NULL,
    expected_revision  int,
    precondition       varchar(80),
    payload_digest     char(64)     NOT NULL,
    expires_at         timestamptz  NOT NULL,
    consumed_at        timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_admin_reauth_token UNIQUE (token_hash),
    CONSTRAINT ck_admin_reauth_revision CHECK (
        expected_revision IS NULL OR expected_revision BETWEEN 1 AND 999999999
    ),
    CONSTRAINT ck_admin_reauth_operation CHECK (operation_id ~ '^[A-Za-z0-9_-]{1,120}$')
);

CREATE INDEX ix_admin_reauth_actor ON admin_reauthentication (actor_id, operation_id);

CREATE TABLE admin_operation (
    actor_id            varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    operation_id        varchar(120) NOT NULL,
    action              varchar(80)  NOT NULL,
    target_type         varchar(80)  NOT NULL,
    target_id           varchar(120) NOT NULL,
    payload_digest      char(64)     NOT NULL,
    status              varchar(20)  NOT NULL,
    resulting_revision  int          NOT NULL,
    audit_id            varchar(120) NOT NULL,
    receipt_json        jsonb        NOT NULL,
    created_at          timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_id, operation_id),
    CONSTRAINT ck_admin_operation_status CHECK (status = 'COMMITTED'),
    CONSTRAINT ck_admin_operation_revision CHECK (resulting_revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_admin_operation_id CHECK (operation_id ~ '^[A-Za-z0-9_-]{1,120}$')
);

CREATE INDEX ix_audit_event_at ON audit_event (at DESC, id DESC);
