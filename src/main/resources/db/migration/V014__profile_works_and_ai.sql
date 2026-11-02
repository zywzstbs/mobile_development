-- P11 画像作品、合作评价与客户端 AI 参考。

CREATE TABLE work (
    id                 varchar(120) PRIMARY KEY,
    user_id            varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    source_project_id  varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    title              varchar(120) NOT NULL,
    description        varchar(2000) NOT NULL DEFAULT '',
    role               varchar(200) NOT NULL,
    my_contribution    varchar(2000) NOT NULL,
    audience           varchar(20)  NOT NULL,
    status             varchar(20)  NOT NULL,
    revision           int          NOT NULL DEFAULT 1,
    current_version    int          NOT NULL DEFAULT 1,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    withdrawn_at       timestamptz,
    CONSTRAINT ck_work_audience CHECK (audience IN ('PRIVATE', 'TEAM', 'CLASS')),
    CONSTRAINT ck_work_status CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT ck_work_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_work_version CHECK (current_version BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_work_user ON work(user_id, created_at DESC);
CREATE INDEX ix_work_source_project ON work(source_project_id);

CREATE TABLE work_version (
    id          varchar(120) PRIMARY KEY,
    work_id     varchar(120) NOT NULL REFERENCES work(id) ON DELETE RESTRICT,
    version     int          NOT NULL,
    body_json   jsonb        NOT NULL,
    revision    int          NOT NULL,
    created_at  timestamptz  NOT NULL,
    CONSTRAINT uq_work_version UNIQUE (work_id, version),
    CONSTRAINT ck_work_version_version CHECK (version BETWEEN 1 AND 999999999),
    CONSTRAINT ck_work_version_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE peer_review (
    id               varchar(120) PRIMARY KEY,
    project_id       varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    author_id        varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    target_user_id   varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    observed_enough  boolean      NOT NULL,
    observations_json jsonb       NOT NULL,
    comment          varchar(500) NOT NULL DEFAULT '',
    status           varchar(20)  NOT NULL,
    revision         int          NOT NULL DEFAULT 1,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    withdrawn_at     timestamptz,
    CONSTRAINT uq_peer_review_author_target UNIQUE (project_id, author_id, target_user_id),
    CONSTRAINT ck_peer_review_not_self CHECK (author_id <> target_user_id),
    CONSTRAINT ck_peer_review_status CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT ck_peer_review_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_peer_review_target ON peer_review(target_user_id, status);
CREATE INDEX ix_peer_review_author ON peer_review(author_id, status);

CREATE TABLE peer_review_version (
    id            varchar(120) PRIMARY KEY,
    peer_review_id varchar(120) NOT NULL REFERENCES peer_review(id) ON DELETE RESTRICT,
    version       int          NOT NULL,
    body_json     jsonb        NOT NULL,
    revision      int          NOT NULL,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT uq_peer_review_version UNIQUE (peer_review_id, version),
    CONSTRAINT ck_peer_review_version_version CHECK (version BETWEEN 1 AND 999999999),
    CONSTRAINT ck_peer_review_version_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE ai_reference (
    id                 varchar(120) PRIMARY KEY,
    project_id         varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    source_project_id  varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    client_record_id   varchar(80)  NOT NULL,
    scene              varchar(40)  NOT NULL,
    model              varchar(120) NOT NULL,
    title              varchar(120) NOT NULL,
    text               varchar(16000) NOT NULL,
    created_by         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    author_id          varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    origin             varchar(40)  NOT NULL,
    revision           int          NOT NULL DEFAULT 1,
    created_at         timestamptz  NOT NULL,
    CONSTRAINT uq_ai_reference_client_record UNIQUE (project_id, client_record_id),
    CONSTRAINT ck_ai_reference_scene CHECK (scene IN
        ('TOPIC_ANALYSIS', 'TASK_PLAN', 'DOCUMENT_REVIEW', 'RECRUITMENT')),
    CONSTRAINT ck_ai_reference_origin CHECK (origin = 'CLIENT_BYOK_LEADER_CONFIRMED'),
    CONSTRAINT ck_ai_reference_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_ai_reference_project ON ai_reference(project_id, created_at DESC);
