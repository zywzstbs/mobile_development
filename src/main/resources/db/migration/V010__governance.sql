-- P07 人员变更、交接、移出表决和人员事项。

CREATE TABLE member_change (
    id                          varchar(120) PRIMARY KEY,
    project_id                  varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    target_user_id              varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    initiator_id                varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    kind                        varchar(20)  NOT NULL,
    status                      varchar(30)  NOT NULL,
    target_project_id           varchar(120) REFERENCES project(id) ON DELETE RESTRICT,
    plan_version                int          NOT NULL DEFAULT 0,
    plan_json                   jsonb        NOT NULL DEFAULT '{}'::jsonb,
    personnel_decision_json     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    source_membership_revision  int          NOT NULL,
    reason                      varchar(500) NOT NULL DEFAULT '',
    next_leader_id              varchar(120),
    notes                       varchar(2000) NOT NULL DEFAULT '',
    target_invite_status        varchar(20)  NOT NULL DEFAULT 'NONE',
    target_invite_expires_at    timestamptz,
    removal_vote_id             varchar(120),
    revision                    int          NOT NULL,
    created_at                  timestamptz  NOT NULL,
    updated_at                  timestamptz  NOT NULL,
    CONSTRAINT ck_member_change_kind CHECK (kind IN ('EXIT', 'TRANSFER', 'REMOVAL')),
    CONSTRAINT ck_member_change_status CHECK (status IN
        ('WAIT_PERSONNEL', 'RETURNED', 'READY', 'EXECUTED', 'CANCELLED')),
    CONSTRAINT ck_member_change_invite CHECK (target_invite_status IN
        ('NONE', 'PENDING', 'ACCEPTED', 'CANCELLED', 'EXPIRED'))
);

CREATE UNIQUE INDEX uq_member_change_open
    ON member_change (project_id, target_user_id)
    WHERE status IN ('WAIT_PERSONNEL', 'RETURNED', 'READY');

CREATE TABLE member_change_event (
    id          varchar(120) PRIMARY KEY,
    change_id   varchar(120) NOT NULL REFERENCES member_change(id) ON DELETE RESTRICT,
    action      varchar(40)  NOT NULL,
    actor_id    varchar(120) NOT NULL,
    reason      varchar(500) NOT NULL DEFAULT '',
    at          timestamptz  NOT NULL
);

CREATE TABLE handover_item (
    id                    varchar(120) PRIMARY KEY,
    change_id             varchar(120) NOT NULL REFERENCES member_change(id) ON DELETE RESTRICT,
    plan_version          int          NOT NULL,
    kind                  varchar(20)  NOT NULL,
    task_id               varchar(120),
    source_task_revision  int,
    recipient_id          varchar(120) NOT NULL,
    responsibility        varchar(500) NOT NULL,
    CONSTRAINT ck_handover_item_kind CHECK (kind IN ('TASK', 'LEADER'))
);

CREATE UNIQUE INDEX uq_handover_leader
    ON handover_item (change_id, plan_version) WHERE kind = 'LEADER';
CREATE UNIQUE INDEX uq_handover_task
    ON handover_item (change_id, plan_version, task_id) WHERE kind = 'TASK';

CREATE TABLE handover_ack (
    change_id     varchar(120) NOT NULL REFERENCES member_change(id) ON DELETE RESTRICT,
    plan_version  int          NOT NULL,
    item_id       varchar(120) NOT NULL REFERENCES handover_item(id) ON DELETE RESTRICT,
    user_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    decision      varchar(20)  NOT NULL,
    at            timestamptz  NOT NULL,
    CONSTRAINT pk_handover_ack PRIMARY KEY (change_id, plan_version, item_id, user_id),
    CONSTRAINT ck_handover_ack_decision CHECK (decision IN ('ACCEPT', 'DECLINE'))
);

CREATE TABLE removal_vote (
    id                  varchar(120) PRIMARY KEY,
    project_id          varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    target_user_id      varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    initiator_id        varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status              varchar(30)  NOT NULL,
    facts               varchar(2000) NOT NULL,
    communication       varchar(2000) NOT NULL,
    statement           varchar(2000) NOT NULL DEFAULT '',
    statement_ends_at   timestamptz  NOT NULL,
    voting_ends_at      timestamptz  NOT NULL,
    electorate_json     jsonb        NOT NULL,
    personnel_decision_json jsonb    NOT NULL DEFAULT '{}'::jsonb,
    member_change_id    varchar(120),
    revision            int          NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT ck_removal_status CHECK (status IN
        ('STATEMENT', 'VOTING', 'PASSED', 'FAILED', 'CANCELLED', 'QUALIFICATION_REVIEW'))
);

CREATE UNIQUE INDEX uq_removal_open
    ON removal_vote (project_id, target_user_id)
    WHERE status IN ('STATEMENT', 'VOTING', 'QUALIFICATION_REVIEW');

CREATE TABLE removal_ballot (
    vote_id   varchar(120) NOT NULL REFERENCES removal_vote(id) ON DELETE RESTRICT,
    user_id   varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    decision  varchar(20)  NOT NULL,
    revision  int          NOT NULL,
    at        timestamptz  NOT NULL,
    CONSTRAINT pk_removal_ballot PRIMARY KEY (vote_id, user_id),
    CONSTRAINT ck_removal_ballot CHECK (decision IN ('AGREE', 'DISAGREE', 'ABSTAIN'))
);

CREATE TABLE personnel_case (
    id               varchar(120) PRIMARY KEY,
    square_id        varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    project_id       varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    subject_user_id  varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    assignee_id      varchar(120),
    title            varchar(200) NOT NULL,
    status           varchar(20)  NOT NULL,
    member_change_id varchar(120),
    revision         int          NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT ck_personnel_case_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'CLOSED'))
);

CREATE TABLE personnel_record (
    id         varchar(120) PRIMARY KEY,
    case_id    varchar(120) NOT NULL REFERENCES personnel_case(id) ON DELETE RESTRICT,
    kind       varchar(20)  NOT NULL,
    author_id  varchar(120) NOT NULL,
    body_json  jsonb        NOT NULL,
    at         timestamptz  NOT NULL,
    CONSTRAINT ck_personnel_record_kind CHECK (kind IN ('STAFF', 'SUBJECT'))
);

CREATE TABLE material_request (
    id                varchar(120) PRIMARY KEY,
    case_id           varchar(120) NOT NULL REFERENCES personnel_case(id) ON DELETE RESTRICT,
    recipient_id      varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status            varchar(20)  NOT NULL,
    requirements      varchar(2000) NOT NULL,
    due_at            timestamptz  NOT NULL,
    response_version  int          NOT NULL DEFAULT 0,
    response_json     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    revision          int          NOT NULL,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT ck_material_status CHECK (status IN
        ('OPEN', 'RESPONDED', 'ACCEPTED', 'RETURNED', 'WITHDRAWN'))
);

CREATE TABLE personnel_handoff (
    id                   varchar(120) PRIMARY KEY,
    case_id              varchar(120) NOT NULL REFERENCES personnel_case(id) ON DELETE RESTRICT,
    from_user_id         varchar(120) NOT NULL,
    to_user_id           varchar(120) NOT NULL,
    status               varchar(20)  NOT NULL,
    source_case_revision int          NOT NULL,
    facts                varchar(2000) NOT NULL,
    unresolved           varchar(2000) NOT NULL,
    decisions_needed     varchar(2000) NOT NULL,
    revision             int           NOT NULL,
    created_at           timestamptz   NOT NULL,
    CONSTRAINT ck_personnel_handoff_status CHECK (status IN ('PENDING', 'ACCEPTED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_personnel_handoff_open
    ON personnel_handoff (case_id) WHERE status = 'PENDING';
