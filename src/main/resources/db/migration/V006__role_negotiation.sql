-- P04 岗位协商。单人提议、双人交换、多人计划统一存放，对外投影不同。

CREATE TABLE role_negotiation (
    id                       varchar(120) PRIMARY KEY,
    project_id               varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    kind                     varchar(20)  NOT NULL,
    status                   varchar(20)  NOT NULL,
    source_project_revision  int          NOT NULL,
    leader_id                varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    initiator_id             varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    plan_json                jsonb        NOT NULL,
    reason                   varchar(500) NOT NULL DEFAULT '',
    message                  varchar(500) NOT NULL DEFAULT '',
    revision                 int          NOT NULL DEFAULT 1,
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    CONSTRAINT ck_role_negotiation_kind CHECK (kind IN ('PLAN', 'PROPOSAL', 'SWAP')),
    CONSTRAINT ck_role_negotiation_status
        CHECK (status IN ('PENDING', 'APPLIED', 'DECLINED', 'WITHDRAWN')),
    CONSTRAINT ck_role_negotiation_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_role_negotiation_project
    ON role_negotiation (project_id, kind, created_at DESC, id);
CREATE INDEX ix_role_negotiation_initiator
    ON role_negotiation (initiator_id, created_at DESC);

CREATE TABLE role_ack (
    id              varchar(120) PRIMARY KEY,
    negotiation_id  varchar(120) NOT NULL REFERENCES role_negotiation(id) ON DELETE RESTRICT,
    user_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    decision        varchar(20)  NOT NULL,
    comment         varchar(500) NOT NULL DEFAULT '',
    at              timestamptz  NOT NULL,
    CONSTRAINT uq_role_ack UNIQUE (negotiation_id, user_id),
    CONSTRAINT ck_role_ack_decision CHECK (decision IN ('ACCEPT', 'DECLINE'))
);

CREATE INDEX ix_role_ack_negotiation ON role_ack (negotiation_id, at, user_id);
