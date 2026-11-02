-- P10 report snapshots and project lifecycle.

ALTER TABLE project ADD COLUMN archived_at timestamptz;

CREATE TABLE project_reflection (
    id              varchar(120) PRIMARY KEY,
    project_id      varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    user_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    body_json       jsonb        NOT NULL,
    revision        int          NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT uq_project_reflection_user UNIQUE (project_id, user_id),
    CONSTRAINT ck_project_reflection_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE overall_reflection (
    id              varchar(120) PRIMARY KEY,
    project_id      varchar(120) NOT NULL UNIQUE REFERENCES project(id) ON DELETE RESTRICT,
    author_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    body_json       jsonb        NOT NULL,
    revision        int          NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT ck_overall_reflection_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE delivery_release_draft (
    id              varchar(120) PRIMARY KEY,
    project_id      varchar(120) NOT NULL UNIQUE REFERENCES project(id) ON DELETE RESTRICT,
    body_json       jsonb        NOT NULL,
    revision        int          NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT ck_delivery_release_draft_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE delivery_release_preview (
    id              varchar(120) PRIMARY KEY,
    project_id      varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    created_by      varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    token_hash      char(64)     NOT NULL UNIQUE,
    source_key      char(64)     NOT NULL,
    draft_revision  int          NOT NULL,
    body_snapshot   jsonb        NOT NULL,
    sources_json    jsonb        NOT NULL,
    expires_at      timestamptz  NOT NULL,
    used_at         timestamptz,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT ck_delivery_preview_draft_revision CHECK (draft_revision >= 0)
);

CREATE INDEX ix_delivery_preview_project ON delivery_release_preview(project_id, expires_at);

CREATE TABLE delivery_release (
    id              varchar(120) PRIMARY KEY,
    project_id      varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    version         int          NOT NULL,
    source_key      char(64)     NOT NULL,
    body_snapshot   jsonb        NOT NULL,
    confirmed_by    varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    confirmed_at    timestamptz  NOT NULL,
    revision        int          NOT NULL DEFAULT 1,
    CONSTRAINT uq_delivery_release_version UNIQUE (project_id, version),
    CONSTRAINT ck_delivery_release_version CHECK (version BETWEEN 1 AND 999999999),
    CONSTRAINT ck_delivery_release_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_delivery_release_project ON delivery_release(project_id, version DESC);

CREATE TABLE report_snapshot (
    id              varchar(120) PRIMARY KEY,
    project_id      varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    type            varchar(20)  NOT NULL,
    name            varchar(120) NOT NULL,
    closing_cycle   int          NOT NULL,
    source_key      char(64)     NOT NULL,
    release_id      varchar(120) REFERENCES delivery_release(id) ON DELETE RESTRICT,
    body_snapshot   jsonb        NOT NULL,
    empty_reason    varchar(1000) NOT NULL DEFAULT '',
    created_by      varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at      timestamptz  NOT NULL,
    revision        int          NOT NULL DEFAULT 1,
    CONSTRAINT uq_report_snapshot_source UNIQUE (project_id, type, closing_cycle, source_key),
    CONSTRAINT ck_report_snapshot_type CHECK (type IN ('PROCESS', 'FINAL', 'EMPTY')),
    CONSTRAINT ck_report_snapshot_cycle CHECK (closing_cycle >= 0),
    CONSTRAINT ck_report_snapshot_empty_reason CHECK
        ((type = 'EMPTY' AND length(btrim(empty_reason)) BETWEEN 1 AND 1000)
         OR (type <> 'EMPTY' AND empty_reason = '')),
    CONSTRAINT ck_report_snapshot_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_report_snapshot_project ON report_snapshot(project_id, created_at DESC, id);
