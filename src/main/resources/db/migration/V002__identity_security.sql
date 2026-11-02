-- P01 身份、会话、幂等、审计与登录限流。
-- 学校表在 P01 仅为账号归属提供最小学校维度；P02 继续建立学期/节次/真实教学班。

CREATE TABLE school (
    id          varchar(120) PRIMARY KEY,
    name        varchar(160) NOT NULL,
    timezone    varchar(64)  NOT NULL,
    revision    int          NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_school_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_school_name_nonblank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_school_timezone_nonblank CHECK (length(btrim(timezone)) > 0)
);

CREATE TABLE app_user (
    id              varchar(120) PRIMARY KEY,
    school_id       varchar(120) NOT NULL REFERENCES school(id) ON DELETE RESTRICT,
    login_id        varchar(120) NOT NULL,
    student_no      varchar(64),
    display_name    varchar(80)  NOT NULL,
    password_hash   varchar(255) NOT NULL,
    identity_status varchar(20)  NOT NULL,
    disabled        boolean      NOT NULL DEFAULT false,
    skills_json     jsonb        NOT NULL DEFAULT '[]'::jsonb,
    revision        int          NOT NULL DEFAULT 1,
    created_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_app_user_login UNIQUE (login_id),
    CONSTRAINT ck_app_user_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_app_user_identity_status CHECK (identity_status IN ('VERIFIED', 'PENDING', 'REJECTED')),
    CONSTRAINT ck_app_user_display_nonblank CHECK (length(btrim(display_name)) > 0)
);

CREATE UNIQUE INDEX uq_app_user_school_student_no
    ON app_user (school_id, student_no)
    WHERE student_no IS NOT NULL;

CREATE INDEX ix_app_user_school ON app_user (school_id);

CREATE TABLE user_role (
    user_id varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    role    varchar(20)  NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT ck_user_role_value CHECK (role IN ('STUDENT', 'TEACHER', 'CLASS_ADMIN'))
);

CREATE TABLE user_profile (
    user_id          varchar(120) PRIMARY KEY REFERENCES app_user(id) ON DELETE RESTRICT,
    intro            text         NOT NULL DEFAULT '',
    profile_json     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    visibility_json  jsonb        NOT NULL DEFAULT '{}'::jsonb,
    revision         int          NOT NULL DEFAULT 1,
    created_at       timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_profile_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_user_profile_intro_length CHECK (char_length(intro) <= 2000)
);

CREATE TABLE auth_session (
    id            varchar(120) PRIMARY KEY,
    user_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    device_label  varchar(80)  NOT NULL,
    refresh_hash  char(64)     NOT NULL,
    expires_at    timestamptz  NOT NULL,
    revoked_at    timestamptz,
    revision      int          NOT NULL DEFAULT 1,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_auth_session_refresh_hash UNIQUE (refresh_hash),
    CONSTRAINT ck_auth_session_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_auth_session_device_nonblank CHECK (length(btrim(device_label)) > 0)
);

CREATE INDEX ix_auth_session_user ON auth_session (user_id);
CREATE INDEX ix_auth_session_expires ON auth_session (expires_at);

CREATE TABLE access_token (
    id          varchar(120) PRIMARY KEY,
    session_id  varchar(120) NOT NULL REFERENCES auth_session(id) ON DELETE RESTRICT,
    token_hash  char(64)     NOT NULL,
    expires_at  timestamptz  NOT NULL,
    revoked_at  timestamptz,
    revision    int          NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_access_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_access_token_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_access_token_session ON access_token (session_id);
CREATE INDEX ix_access_token_expires ON access_token (expires_at);

CREATE TABLE operation_receipt (
    id                  varchar(120) PRIMARY KEY,
    user_id             varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    method              varchar(10)  NOT NULL,
    path                varchar(500) NOT NULL,
    key                 varchar(128) NOT NULL,
    body_hash           char(64)     NOT NULL,
    http_status         int          NOT NULL,
    business_code       varchar(64)  NOT NULL,
    result_ciphertext   text         NOT NULL,
    key_id              varchar(64)  NOT NULL,
    resource_refs_json  jsonb        NOT NULL DEFAULT '[]'::jsonb,
    expires_at          timestamptz  NOT NULL,
    revision            int          NOT NULL DEFAULT 1,
    created_at          timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_operation_receipt_scope UNIQUE (user_id, method, path, key),
    CONSTRAINT ck_operation_receipt_http_status CHECK (http_status BETWEEN 200 AND 599),
    CONSTRAINT ck_operation_receipt_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_operation_receipt_expires ON operation_receipt (expires_at);

CREATE TABLE audit_event (
    id              varchar(120) PRIMARY KEY,
    actor_id        varchar(120),
    action          varchar(80)  NOT NULL,
    resource_type   varchar(40)  NOT NULL,
    resource_id     varchar(120),
    before_revision int,
    after_revision  int,
    reason          varchar(500),
    request_id      varchar(64),
    at              timestamptz  NOT NULL,
    revision        int          NOT NULL DEFAULT 1,
    created_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_audit_event_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_audit_event_action_nonblank CHECK (length(btrim(action)) > 0)
);

CREATE INDEX ix_audit_event_resource ON audit_event (resource_type, resource_id, at);

CREATE TABLE rate_window (
    id            varchar(120) PRIMARY KEY,
    subject_hash  char(64)     NOT NULL,
    rule          varchar(40)  NOT NULL,
    window_start  timestamptz  NOT NULL,
    count         int          NOT NULL,
    blocked_until timestamptz,
    revision      int          NOT NULL DEFAULT 1,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_rate_window_scope UNIQUE (subject_hash, rule, window_start),
    CONSTRAINT ck_rate_window_count CHECK (count >= 0),
    CONSTRAINT ck_rate_window_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_rate_window_blocked ON rate_window (blocked_until);

CREATE TABLE import_batch (
    id           varchar(120) PRIMARY KEY,
    kind         varchar(40)  NOT NULL,
    input_hash   char(64)     NOT NULL,
    actor_id     varchar(120),
    status       varchar(20)  NOT NULL,
    summary_json jsonb        NOT NULL DEFAULT '{}'::jsonb,
    revision     int          NOT NULL DEFAULT 1,
    created_at   timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_import_batch_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_import_batch_status CHECK (status IN ('APPLIED', 'FAILED', 'DRY_RUN'))
);

CREATE INDEX ix_import_batch_input ON import_batch (input_hash);
