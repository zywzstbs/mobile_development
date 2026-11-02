-- P05F 文件意图、短期授权、引用与清理计划。字节不进数据库。

CREATE TABLE stored_file (
    id                varchar(120) PRIMARY KEY,
    uploader_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    purpose           varchar(40)  NOT NULL,
    project_id        varchar(120) REFERENCES project(id) ON DELETE RESTRICT,
    case_id           varchar(120),
    ticket_id         varchar(120),
    file_name         varchar(200) NOT NULL,
    content_type      varchar(200) NOT NULL,
    declared_size     int          NOT NULL,
    actual_size       int,
    sha256            char(64),
    declared_sha256   char(64),
    storage_key       varchar(200) NOT NULL,
    status            varchar(20)  NOT NULL,
    upload_expires_at timestamptz  NOT NULL,
    received_at       timestamptz,
    ready_at          timestamptz,
    revision          int          NOT NULL DEFAULT 1,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT uq_stored_file_key UNIQUE (storage_key),
    CONSTRAINT ck_stored_file_purpose CHECK (purpose IN (
        'SUPPORT_ATTACHMENT', 'DELIVERY_ARTIFACT', 'MATERIAL_PROOF', 'WORK_ARTIFACT')),
    CONSTRAINT ck_stored_file_status CHECK (status IN ('UPLOADING', 'READY', 'FAILED')),
    CONSTRAINT ck_stored_file_declared_size CHECK (declared_size BETWEEN 1 AND 8388608),
    CONSTRAINT ck_stored_file_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_stored_file_uploader ON stored_file (uploader_id, created_at);
CREATE INDEX ix_stored_file_status ON stored_file (status, created_at);

CREATE TABLE file_authorization (
    id          varchar(120) PRIMARY KEY,
    file_id     varchar(120) NOT NULL REFERENCES stored_file(id) ON DELETE RESTRICT,
    user_id     varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    kind        varchar(20)  NOT NULL,
    token_hash  char(64)     NOT NULL,
    expires_at  timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL,
    CONSTRAINT uq_file_authorization_token UNIQUE (token_hash),
    CONSTRAINT ck_file_authorization_kind CHECK (kind IN ('UPLOAD', 'DOWNLOAD'))
);

CREATE INDEX ix_file_authorization_file ON file_authorization (file_id, kind, expires_at);

CREATE TABLE file_reference (
    id                varchar(120) PRIMARY KEY,
    file_id           varchar(120) NOT NULL REFERENCES stored_file(id) ON DELETE RESTRICT,
    resource_type     varchar(40)  NOT NULL,
    resource_id       varchar(120) NOT NULL,
    resource_version  int          NOT NULL,
    retain_until      timestamptz,
    created_at        timestamptz  NOT NULL,
    CONSTRAINT uq_file_reference UNIQUE (file_id, resource_type, resource_id, resource_version)
);

CREATE INDEX ix_file_reference_file ON file_reference (file_id);

CREATE TABLE cleanup_plan (
    id             varchar(120) PRIMARY KEY,
    resource_type  varchar(40)  NOT NULL,
    resource_id    varchar(120) NOT NULL,
    reason         varchar(40)  NOT NULL,
    eligible_at    timestamptz  NOT NULL,
    execute_after  timestamptz  NOT NULL,
    status         varchar(20)  NOT NULL,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT ck_cleanup_plan_status CHECK (status IN ('PLANNED', 'EXECUTED', 'CANCELLED')),
    CONSTRAINT ck_cleanup_plan_window CHECK (execute_after >= eligible_at + interval '7 days')
);

CREATE UNIQUE INDEX uq_cleanup_plan_current
    ON cleanup_plan (resource_type, resource_id)
    WHERE status = 'PLANNED';
