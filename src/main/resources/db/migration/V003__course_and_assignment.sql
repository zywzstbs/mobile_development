-- P02 课程字典、可信名单、课程广场与作业。
-- school 已在 P01 建立；这里增加学期/节次/课程名/真实教学班/选课事实/广场/课程成员/作业/匹配token。

CREATE TABLE term (
    id          varchar(120) PRIMARY KEY,
    school_id   varchar(120) NOT NULL REFERENCES school(id) ON DELETE RESTRICT,
    name        varchar(80)  NOT NULL,
    start_date  date         NOT NULL,
    end_date    date         NOT NULL,
    ordinal     int          NOT NULL,
    revision    int          NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_term_school_ordinal UNIQUE (school_id, ordinal),
    CONSTRAINT ck_term_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_term_dates CHECK (start_date <= end_date),
    CONSTRAINT ck_term_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_term_name_nonblank CHECK (length(btrim(name)) > 0)
);

CREATE INDEX ix_term_school ON term (school_id);

CREATE TABLE period (
    id          varchar(120) PRIMARY KEY,
    school_id   varchar(120) NOT NULL REFERENCES school(id) ON DELETE RESTRICT,
    term_id     varchar(120) NOT NULL REFERENCES term(id) ON DELETE RESTRICT,
    label       varchar(40)  NOT NULL,
    start_time  time         NOT NULL,
    end_time    time         NOT NULL,
    ordinal     int          NOT NULL,
    revision    int          NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_period_term_ordinal UNIQUE (school_id, term_id, ordinal),
    CONSTRAINT ck_period_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_period_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_period_times CHECK (start_time < end_time),
    CONSTRAINT ck_period_label_nonblank CHECK (length(btrim(label)) > 0)
);

CREATE INDEX ix_period_term ON period (term_id);

CREATE TABLE course_name (
    id               varchar(120) PRIMARY KEY,
    school_id        varchar(120) NOT NULL REFERENCES school(id) ON DELETE RESTRICT,
    full_name        varchar(160) NOT NULL,
    normalized_name  varchar(160) NOT NULL,
    aliases_json     jsonb        NOT NULL DEFAULT '[]'::jsonb,
    revision         int          NOT NULL DEFAULT 1,
    created_at       timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_course_name_school_normalized UNIQUE (school_id, normalized_name),
    CONSTRAINT ck_course_name_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_course_name_full_nonblank CHECK (length(btrim(full_name)) > 0)
);

CREATE INDEX ix_course_name_school ON course_name (school_id);

CREATE TABLE verified_course (
    id              varchar(120) PRIMARY KEY,
    school_id       varchar(120) NOT NULL REFERENCES school(id) ON DELETE RESTRICT,
    term_id         varchar(120) NOT NULL REFERENCES term(id) ON DELETE RESTRICT,
    external_key    varchar(120) NOT NULL,
    course_name_id  varchar(120) NOT NULL REFERENCES course_name(id) ON DELETE RESTRICT,
    teacher_user_id varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    slots_json      jsonb        NOT NULL,
    canonical_key   char(64)     NOT NULL,
    revision        int          NOT NULL DEFAULT 1,
    created_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_verified_course_external UNIQUE (school_id, term_id, external_key),
    CONSTRAINT uq_verified_course_canonical UNIQUE (canonical_key),
    CONSTRAINT ck_verified_course_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_verified_course_term ON verified_course (term_id);

CREATE TABLE verified_enrollment (
    id                 varchar(120) PRIMARY KEY,
    verified_course_id varchar(120) NOT NULL REFERENCES verified_course(id) ON DELETE RESTRICT,
    user_id            varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    source_batch_id    varchar(120),
    valid              boolean      NOT NULL DEFAULT true,
    revision           int          NOT NULL DEFAULT 1,
    created_at         timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_verified_enrollment UNIQUE (verified_course_id, user_id),
    CONSTRAINT ck_verified_enrollment_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_verified_enrollment_user ON verified_enrollment (user_id);

CREATE TABLE square (
    id                  varchar(120) PRIMARY KEY,
    verified_course_id  varchar(120) REFERENCES verified_course(id) ON DELETE RESTRICT,
    school_id           varchar(120) NOT NULL REFERENCES school(id) ON DELETE RESTRICT,
    term_id             varchar(120) NOT NULL REFERENCES term(id) ON DELETE RESTRICT,
    name                varchar(160) NOT NULL,
    teacher_name        varchar(80)  NOT NULL,
    slots_json          jsonb        NOT NULL,
    canonical_key       char(64)     NOT NULL,
    square_no           varchar(40)  NOT NULL,
    status              varchar(20)  NOT NULL,
    merged_into_id      varchar(120) REFERENCES square(id) ON DELETE RESTRICT,
    authorization_epoch int          NOT NULL DEFAULT 1,
    revision            int          NOT NULL DEFAULT 1,
    created_at          timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_square_canonical UNIQUE (canonical_key),
    CONSTRAINT uq_square_no UNIQUE (square_no),
    CONSTRAINT ck_square_status CHECK (status IN ('ACTIVE', 'MERGED')),
    CONSTRAINT ck_square_epoch CHECK (authorization_epoch >= 0),
    CONSTRAINT ck_square_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_square_name_nonblank CHECK (length(btrim(name)) > 0)
);

CREATE INDEX ix_square_school_term ON square (school_id, term_id);
CREATE INDEX ix_square_updated ON square (updated_at, id);

CREATE SEQUENCE square_no_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE course_membership (
    id          varchar(120) PRIMARY KEY,
    square_id   varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    user_id     varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status      varchar(20)  NOT NULL,
    joined_at   timestamptz  NOT NULL,
    left_at     timestamptz,
    revision    int          NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_course_membership UNIQUE (square_id, user_id),
    CONSTRAINT ck_course_membership_status CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
    CONSTRAINT ck_course_membership_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_course_membership_user ON course_membership (user_id);

CREATE TABLE assignment (
    id                   varchar(120) PRIMARY KEY,
    square_id            varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    name                 varchar(120) NOT NULL,
    requirements         varchar(5000) NOT NULL,
    origin               varchar(20)  NOT NULL,
    confirmation_status  varchar(30)  NOT NULL,
    hard_due_at          timestamptz  NOT NULL,
    min_members          int          NOT NULL,
    max_members          int          NOT NULL,
    grading_enabled      boolean      NOT NULL DEFAULT false,
    source_references_json jsonb      NOT NULL DEFAULT '[]'::jsonb,
    revision             int          NOT NULL DEFAULT 1,
    created_at           timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_assignment_origin CHECK (origin IN ('STUDENT', 'TEACHER')),
    CONSTRAINT ck_assignment_confirmation CHECK (confirmation_status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'MAPPED')),
    CONSTRAINT ck_assignment_members CHECK (min_members >= 1 AND max_members <= 20 AND min_members <= max_members),
    CONSTRAINT ck_assignment_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_assignment_name_nonblank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_assignment_requirements_nonblank CHECK (length(btrim(requirements)) > 0)
);

CREATE INDEX ix_assignment_square ON assignment (square_id, created_at, id);

CREATE TABLE preview_token (
    id            varchar(120) PRIMARY KEY,
    token_hash    char(64)     NOT NULL,
    user_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    kind          varchar(40)  NOT NULL,
    resource_id   varchar(120),
    body_hash     char(64)     NOT NULL,
    source_digest char(64)     NOT NULL,
    payload_json  jsonb        NOT NULL DEFAULT '{}'::jsonb,
    expires_at    timestamptz  NOT NULL,
    revision      int          NOT NULL DEFAULT 1,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_preview_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_preview_token_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_preview_token_user_kind ON preview_token (user_id, kind, expires_at);
