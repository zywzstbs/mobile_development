-- P09 责任账本、结算与正式成绩。旧快照不可改。

CREATE TABLE grade_rule (
    id             varchar(120) PRIMARY KEY,
    assignment_id  varchar(120) NOT NULL REFERENCES assignment(id) ON DELETE RESTRICT,
    version        int          NOT NULL,
    process_weight numeric(8, 4) NOT NULL,
    description    varchar(500) NOT NULL DEFAULT '',
    confirmed_by   varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    confirmed_at   timestamptz  NOT NULL,
    CONSTRAINT uq_grade_rule_version UNIQUE (assignment_id, version),
    CONSTRAINT ck_grade_rule_version CHECK (version BETWEEN 1 AND 999999999),
    CONSTRAINT ck_grade_rule_weight CHECK (process_weight >= 0 AND process_weight <= 1)
);

CREATE TABLE grade_draft (
    id                  varchar(120) PRIMARY KEY,
    assignment_id       varchar(120) NOT NULL REFERENCES assignment(id) ON DELETE RESTRICT,
    student_id          varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    q                   int          NOT NULL,
    delta               int          NOT NULL,
    delta_reason        varchar(500) NOT NULL DEFAULT '',
    evidence_refs_json  jsonb        NOT NULL,
    source_key          char(64)     NOT NULL,
    rule_version        int          NOT NULL,
    revision            int          NOT NULL,
    CONSTRAINT uq_grade_draft_student UNIQUE (assignment_id, student_id),
    CONSTRAINT ck_grade_draft_q CHECK (q BETWEEN 0 AND 100),
    CONSTRAINT ck_grade_draft_delta CHECK (delta BETWEEN -100 AND 100),
    CONSTRAINT ck_grade_draft_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE ledger_correction (
    id                     varchar(120) PRIMARY KEY,
    assignment_id          varchar(120) NOT NULL REFERENCES assignment(id) ON DELETE RESTRICT,
    student_id             varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    task_id                varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    unit_id                varchar(120) NOT NULL REFERENCES delivery_unit(id) ON DELETE RESTRICT,
    responsibility_points  numeric(12, 2) NOT NULL,
    accepted_points        numeric(12, 2) NOT NULL,
    reason                 varchar(500) NOT NULL,
    evidence_refs_json     jsonb        NOT NULL,
    source_key             char(64)     NOT NULL,
    author_id              varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    revision               int          NOT NULL,
    created_at             timestamptz  NOT NULL,
    CONSTRAINT ck_ledger_correction_points CHECK (
        responsibility_points >= 0 AND accepted_points >= 0
        AND accepted_points <= responsibility_points
        AND responsibility_points <= 1000000),
    CONSTRAINT ck_ledger_correction_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_ledger_correction_student
    ON ledger_correction (assignment_id, student_id, unit_id, created_at);

CREATE TABLE settlement (
    id                     varchar(120) PRIMARY KEY,
    assignment_id          varchar(120) NOT NULL REFERENCES assignment(id) ON DELETE RESTRICT,
    student_id             varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    source_key             char(64)     NOT NULL,
    rule_version           int          NOT NULL,
    responsibility_points  numeric(12, 2) NOT NULL,
    accepted_points        numeric(12, 2) NOT NULL,
    b                      int          NOT NULL,
    settled_at             timestamptz  NOT NULL,
    CONSTRAINT uq_settlement_source UNIQUE (assignment_id, student_id, source_key),
    CONSTRAINT ck_settlement_b CHECK (b BETWEEN 0 AND 100)
);

CREATE TABLE grade_publication (
    id            varchar(120) PRIMARY KEY,
    assignment_id varchar(120) NOT NULL REFERENCES assignment(id) ON DELETE RESTRICT,
    preview_id    varchar(120) NOT NULL UNIQUE,
    published_by  varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    published_at  timestamptz  NOT NULL
);

CREATE TABLE grade_snapshot (
    id                 varchar(120) PRIMARY KEY,
    publication_id     varchar(120) NOT NULL REFERENCES grade_publication(id) ON DELETE RESTRICT,
    assignment_id      varchar(120) NOT NULL REFERENCES assignment(id) ON DELETE RESTRICT,
    student_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    version            int          NOT NULL,
    b                  int          NOT NULL,
    q                  int          NOT NULL,
    delta              int          NOT NULL,
    g                  int          NOT NULL,
    basis_digest       char(64)     NOT NULL,
    rule_snapshot      jsonb        NOT NULL,
    ledger_snapshot    jsonb        NOT NULL,
    evidence_snapshot  jsonb        NOT NULL,
    created_at         timestamptz  NOT NULL,
    CONSTRAINT uq_grade_snapshot_version UNIQUE (assignment_id, student_id, version),
    CONSTRAINT ck_grade_snapshot_score CHECK (b BETWEEN 0 AND 100 AND q BETWEEN 0 AND 100
        AND delta BETWEEN -100 AND 100 AND g BETWEEN 0 AND 100)
);

CREATE TABLE grade_appeal (
    id                    varchar(120) PRIMARY KEY,
    grade_snapshot_id     varchar(120) NOT NULL REFERENCES grade_snapshot(id) ON DELETE RESTRICT,
    assignment_id         varchar(120) NOT NULL REFERENCES assignment(id) ON DELETE RESTRICT,
    student_id            varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status                varchar(40)  NOT NULL,
    issue                 varchar(2000) NOT NULL,
    source_refs_json      jsonb        NOT NULL,
    evidence_refs_json    jsonb        NOT NULL,
    responses_json        jsonb        NOT NULL,
    revised_snapshot_id   varchar(120) REFERENCES grade_snapshot(id) ON DELETE RESTRICT,
    revision              int          NOT NULL,
    created_at            timestamptz  NOT NULL,
    CONSTRAINT ck_grade_appeal_status CHECK (status IN (
        'PENDING', 'REVIEWING', 'RESOLVED_MAINTAINED',
        'AWAITING_REVISED_PUBLICATION', 'RESOLVED_REVISED')),
    CONSTRAINT ck_grade_appeal_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_grade_appeal_open ON grade_appeal (grade_snapshot_id)
    WHERE status IN ('PENDING', 'REVIEWING', 'AWAITING_REVISED_PUBLICATION');

CREATE TABLE teacher_summary (
    id                 varchar(120) PRIMARY KEY,
    assignment_id      varchar(120) NOT NULL REFERENCES assignment(id) ON DELETE RESTRICT,
    student_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    version            int          NOT NULL,
    revision           int          NOT NULL,
    grade_snapshot_id  varchar(120) NOT NULL REFERENCES grade_snapshot(id) ON DELETE RESTRICT,
    summary            varchar(2000) NOT NULL,
    status             varchar(20)  NOT NULL,
    CONSTRAINT uq_teacher_summary_student UNIQUE (assignment_id, student_id),
    CONSTRAINT ck_teacher_summary_status CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT ck_teacher_summary_version CHECK (version BETWEEN 1 AND 999999999),
    CONSTRAINT ck_teacher_summary_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE teacher_summary_consent (
    summary_id       varchar(120) NOT NULL REFERENCES teacher_summary(id) ON DELETE RESTRICT,
    content_version  int          NOT NULL,
    student_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    decision         varchar(10)  NOT NULL,
    CONSTRAINT pk_teacher_summary_consent PRIMARY KEY (summary_id, content_version, student_id),
    CONSTRAINT ck_teacher_summary_consent CHECK (decision IN ('SHOW', 'HIDE'))
);
