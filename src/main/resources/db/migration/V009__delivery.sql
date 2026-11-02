-- P06 正式约定、提交与验收。普通任务的标准仍然 unit_id 为空。

ALTER TABLE task
    ADD COLUMN handover_blocked boolean NOT NULL DEFAULT false;

ALTER TABLE criterion
    ADD COLUMN unit_id varchar(120);

CREATE TABLE agreement (
    id                   varchar(120) PRIMARY KEY,
    task_id              varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    version              int          NOT NULL,
    status               varchar(20)  NOT NULL,
    base_task_revision   int          NOT NULL,
    plan_json            jsonb        NOT NULL,
    source_change_id     varchar(120),
    revision             int          NOT NULL,
    created_at           timestamptz  NOT NULL,
    updated_at           timestamptz  NOT NULL,
    CONSTRAINT uq_agreement_version UNIQUE (task_id, version),
    CONSTRAINT ck_agreement_status CHECK (status IN ('PENDING', 'ACTIVE', 'CHANGES_REQUESTED', 'SUPERSEDED')),
    CONSTRAINT ck_agreement_version CHECK (version BETWEEN 1 AND 999999999),
    CONSTRAINT ck_agreement_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_agreement_active ON agreement (task_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_agreement_open ON agreement (task_id)
    WHERE status IN ('PENDING', 'CHANGES_REQUESTED');

CREATE TABLE agreement_ack (
    agreement_id varchar(120) NOT NULL REFERENCES agreement(id) ON DELETE RESTRICT,
    user_id      varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    decision     varchar(20)  NOT NULL,
    comment      varchar(500) NOT NULL DEFAULT '',
    at           timestamptz  NOT NULL,
    CONSTRAINT pk_agreement_ack PRIMARY KEY (agreement_id, user_id),
    CONSTRAINT ck_agreement_ack_decision CHECK (decision IN ('ACCEPT', 'REQUEST_CHANGE'))
);

CREATE TABLE delivery_unit (
    id          varchar(120) PRIMARY KEY,
    task_id     varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    client_key  varchar(120) NOT NULL,
    title       varchar(120) NOT NULL,
    due_at      timestamptz  NOT NULL,
    points      numeric(12, 2) NOT NULL,
    revision    int          NOT NULL,
    position    int          NOT NULL,
    passed      boolean      NOT NULL DEFAULT false,
    CONSTRAINT uq_delivery_unit_position UNIQUE (task_id, position),
    CONSTRAINT uq_delivery_unit_key UNIQUE (task_id, client_key),
    CONSTRAINT ck_delivery_unit_points CHECK (points >= 0 AND points <= 1000000),
    CONSTRAINT ck_delivery_unit_revision CHECK (revision BETWEEN 1 AND 999999999)
);

ALTER TABLE criterion
    ADD CONSTRAINT fk_criterion_unit FOREIGN KEY (unit_id) REFERENCES delivery_unit(id) ON DELETE RESTRICT;

CREATE TABLE unit_share (
    id                 varchar(120) PRIMARY KEY,
    unit_id            varchar(120) NOT NULL REFERENCES delivery_unit(id) ON DELETE RESTRICT,
    user_id            varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    points             numeric(12, 2) NOT NULL,
    responsibility     varchar(500) NOT NULL,
    agreement_version  int          NOT NULL,
    signed             boolean      NOT NULL DEFAULT false,
    CONSTRAINT uq_unit_share_version UNIQUE (unit_id, user_id, agreement_version),
    CONSTRAINT ck_unit_share_points CHECK (points >= 0 AND points <= 1000000)
);

CREATE TABLE reviewer_arrangement (
    task_id  varchar(120) PRIMARY KEY REFERENCES task(id) ON DELETE RESTRICT,
    user_id  varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    kind     varchar(20)  NOT NULL,
    status   varchar(20)  NOT NULL,
    version  int          NOT NULL,
    CONSTRAINT ck_reviewer_kind CHECK (kind IN ('INDEPENDENT', 'SELF_CHECK')),
    CONSTRAINT ck_reviewer_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED'))
);

CREATE TABLE reviewer_invitation (
    id                   varchar(120) PRIMARY KEY,
    task_id              varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    recipient_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status               varchar(20)  NOT NULL,
    arrangement_version  int          NOT NULL,
    expires_at           timestamptz  NOT NULL,
    revision             int          NOT NULL,
    created_at           timestamptz  NOT NULL,
    CONSTRAINT uq_reviewer_invitation_version UNIQUE (task_id, arrangement_version),
    CONSTRAINT ck_reviewer_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'SUPERSEDED')),
    CONSTRAINT ck_reviewer_invitation_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE delivery_draft (
    id                      varchar(120) PRIMARY KEY,
    task_id                 varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    user_id                 varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    unit_ids_json           jsonb        NOT NULL,
    summary                 varchar(2000) NOT NULL,
    artifacts_json          jsonb        NOT NULL,
    base_agreement_version  int,
    revision                int          NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT uq_delivery_draft_owner UNIQUE (task_id, user_id),
    CONSTRAINT ck_delivery_draft_revision CHECK (revision BETWEEN 0 AND 999999999)
);

CREATE TABLE submission (
    id                      varchar(120) PRIMARY KEY,
    task_id                 varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    sequence                int          NOT NULL,
    submitted_by            varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status                  varchar(20)  NOT NULL,
    summary                 varchar(2000) NOT NULL,
    artifacts_json          jsonb        NOT NULL,
    unit_ids_json           jsonb        NOT NULL,
    criteria_snapshot       jsonb        NOT NULL,
    due_snapshot            timestamptz,
    unit_due_snapshot_json  jsonb        NOT NULL,
    agreement_snapshot      jsonb,
    agreement_version       int,
    from_draft_revision     int,
    submitted_at            timestamptz  NOT NULL,
    revision                int          NOT NULL,
    CONSTRAINT uq_submission_sequence UNIQUE (task_id, sequence),
    CONSTRAINT ck_submission_status CHECK (status IN ('PENDING', 'REVIEWED', 'WITHDRAWN')),
    CONSTRAINT ck_submission_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_submission_one_pending ON submission (task_id) WHERE status = 'PENDING';

CREATE TABLE acceptance (
    id                 varchar(120) PRIMARY KEY,
    submission_id      varchar(120) NOT NULL UNIQUE REFERENCES submission(id) ON DELETE RESTRICT,
    reviewer_id        varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    kind               varchar(20)  NOT NULL,
    checks_json        jsonb        NOT NULL,
    decision           varchar(20)  NOT NULL,
    reason             varchar(2000) NOT NULL DEFAULT '',
    self_check_reason  varchar(500),
    accepted_at        timestamptz  NOT NULL,
    CONSTRAINT ck_acceptance_kind CHECK (kind IN ('INDEPENDENT', 'SELF_CHECK')),
    CONSTRAINT ck_acceptance_decision CHECK (decision IN ('PASSED', 'PARTIAL', 'RETURNED'))
);

CREATE TABLE unit_acceptance (
    unit_id        varchar(120) NOT NULL REFERENCES delivery_unit(id) ON DELETE RESTRICT,
    submission_id  varchar(120) NOT NULL REFERENCES submission(id) ON DELETE RESTRICT,
    acceptance_id  varchar(120) NOT NULL REFERENCES acceptance(id) ON DELETE RESTRICT,
    passed         boolean      NOT NULL,
    CONSTRAINT pk_unit_acceptance PRIMARY KEY (unit_id, submission_id)
);

CREATE UNIQUE INDEX uq_unit_acceptance_passed ON unit_acceptance (unit_id) WHERE passed = true;

CREATE TABLE contribution_confirmation (
    id             varchar(120) PRIMARY KEY,
    unit_id        varchar(120) NOT NULL REFERENCES delivery_unit(id) ON DELETE RESTRICT,
    user_id        varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    acceptance_id  varchar(120) NOT NULL REFERENCES acceptance(id) ON DELETE RESTRICT,
    submission_id  varchar(120) NOT NULL REFERENCES submission(id) ON DELETE RESTRICT,
    statement      varchar(500) NOT NULL,
    at             timestamptz  NOT NULL,
    CONSTRAINT uq_contribution UNIQUE (unit_id, user_id, acceptance_id)
);

CREATE TABLE unit_change_proposal (
    id                      varchar(120) PRIMARY KEY,
    task_id                 varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    base_agreement_version  int          NOT NULL,
    status                  varchar(20)  NOT NULL,
    changes_json            jsonb        NOT NULL,
    reason                  varchar(500) NOT NULL DEFAULT '',
    affected_users_json     jsonb        NOT NULL,
    initiator_id            varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    revision                int          NOT NULL,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT ck_proposal_status CHECK (status IN ('PENDING', 'APPLIED', 'WITHDRAWN')),
    CONSTRAINT ck_proposal_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE proposal_response (
    id              varchar(120) PRIMARY KEY,
    proposal_id     varchar(120) NOT NULL REFERENCES unit_change_proposal(id) ON DELETE RESTRICT,
    user_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    kind            varchar(30)  NOT NULL,
    decision        varchar(20),
    comment         varchar(500) NOT NULL DEFAULT '',
    proposal_text   varchar(2000),
    opinion_id      varchar(120),
    source_revision int          NOT NULL,
    at              timestamptz  NOT NULL,
    CONSTRAINT ck_proposal_response_kind CHECK (kind IN ('CONFIRMATION', 'OPINION'))
);

CREATE UNIQUE INDEX uq_proposal_confirmation ON proposal_response (proposal_id, user_id)
    WHERE kind = 'CONFIRMATION';
