-- P02B 教师权属、班级授权、课程码、入课申请与分页快照。

CREATE TABLE teacher_assignment (
    id          varchar(120) PRIMARY KEY,
    square_id   varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    user_id     varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    active      boolean      NOT NULL DEFAULT true,
    started_at  timestamptz  NOT NULL,
    ended_at    timestamptz,
    revision    int          NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_teacher_assignment_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_teacher_assignment_active
    ON teacher_assignment (square_id) WHERE active;

CREATE INDEX ix_teacher_assignment_user ON teacher_assignment (user_id, active);

CREATE TABLE teacher_claim (
    id              varchar(120) PRIMARY KEY,
    square_id       varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    user_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status          varchar(20)  NOT NULL,
    message         varchar(500) NOT NULL,
    proof_refs_json jsonb        NOT NULL DEFAULT '[]'::jsonb,
    source_batch_id varchar(120),
    revision        int          NOT NULL DEFAULT 1,
    created_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_teacher_claim_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_teacher_claim_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_teacher_claim_pending
    ON teacher_claim (square_id, user_id) WHERE status = 'PENDING';

CREATE INDEX ix_teacher_claim_user ON teacher_claim (user_id, status);

CREATE TABLE teacher_transfer (
    id              varchar(120) PRIMARY KEY,
    square_id       varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    from_user_id    varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    to_user_id      varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status          varchar(20)  NOT NULL,
    source_revision int          NOT NULL,
    reason          varchar(500) NOT NULL,
    revision        int          NOT NULL DEFAULT 1,
    created_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_teacher_transfer_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    CONSTRAINT ck_teacher_transfer_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_teacher_transfer_source_revision CHECK (source_revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_teacher_transfer_pending
    ON teacher_transfer (square_id) WHERE status = 'PENDING';

CREATE INDEX ix_teacher_transfer_recipient ON teacher_transfer (to_user_id, status);

CREATE TABLE class_grant (
    id               varchar(120) PRIMARY KEY,
    square_id        varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    user_id          varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    capabilities_json jsonb       NOT NULL,
    status           varchar(20)  NOT NULL,
    expires_at       timestamptz  NOT NULL,
    granted_by       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    revision         int          NOT NULL DEFAULT 1,
    created_at       timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_class_grant_square_user UNIQUE (square_id, user_id),
    CONSTRAINT ck_class_grant_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_class_grant_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_class_grant_user ON class_grant (user_id, status, expires_at);

CREATE TABLE course_code (
    id           varchar(120) PRIMARY KEY,
    square_id    varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    code_hash    char(64)     NOT NULL,
    code_ciphertext text      NOT NULL,
    key_id       varchar(64)  NOT NULL,
    code_version int          NOT NULL,
    status       varchar(20)  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    issued_by    varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    revision     int          NOT NULL DEFAULT 1,
    created_at   timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_course_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_course_code_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_course_code_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_course_code_active
    ON course_code (square_id) WHERE status = 'ACTIVE';

CREATE INDEX ix_course_code_square ON course_code (square_id, created_at);

CREATE TABLE course_join_request (
    id              varchar(120) PRIMARY KEY,
    square_id       varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    user_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status          varchar(20)  NOT NULL,
    message         varchar(500) NOT NULL DEFAULT '',
    proof_refs_json jsonb        NOT NULL DEFAULT '[]'::jsonb,
    decided_by      varchar(120),
    decision_reason varchar(500),
    revision        int          NOT NULL DEFAULT 1,
    created_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_course_join_request_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_course_join_request_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_course_join_request_pending
    ON course_join_request (square_id, user_id) WHERE status = 'PENDING';

CREATE INDEX ix_course_join_request_user ON course_join_request (user_id, created_at);

CREATE TABLE cursor_snapshot (
    id          varchar(120) PRIMARY KEY,
    token_hash  char(64)     NOT NULL,
    user_id     varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    scope_key   varchar(200) NOT NULL,
    query_hash  char(64)     NOT NULL,
    epoch       int          NOT NULL DEFAULT 0,
    ids_json    jsonb        NOT NULL,
    total       int          NOT NULL,
    position    int          NOT NULL DEFAULT 0,
    expires_at  timestamptz  NOT NULL,
    revision    int          NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_cursor_snapshot_hash UNIQUE (token_hash),
    CONSTRAINT ck_cursor_snapshot_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_cursor_snapshot_position CHECK (position >= 0),
    CONSTRAINT ck_cursor_snapshot_total CHECK (total >= 0)
);

CREATE INDEX ix_cursor_snapshot_expires ON cursor_snapshot (expires_at);

-- 已有 P02 广场的真实教师立即成为当前负责教师；新建广场由服务层补建。
INSERT INTO teacher_assignment (id, square_id, user_id, active, started_at, revision)
SELECT 'ta_' || substr(md5(s.id), 1, 32), s.id, vc.teacher_user_id, true, CURRENT_TIMESTAMP, 1
FROM square s
JOIN verified_course vc ON vc.id = s.verified_course_id
WHERE NOT EXISTS (
    SELECT 1 FROM teacher_assignment ta WHERE ta.square_id = s.id AND ta.active
);
