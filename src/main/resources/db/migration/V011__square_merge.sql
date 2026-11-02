-- P08 课程广场合并。作业和项目 ID 保持不变，来源广场只留追溯。

CREATE TABLE square_merge (
    id                  varchar(120) PRIMARY KEY,
    source_square_id    varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    target_square_id    varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    initiator_id        varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    reason              varchar(500) NOT NULL DEFAULT '',
    evidence_json       jsonb        NOT NULL DEFAULT '[]'::jsonb,
    status              varchar(30)  NOT NULL,
    electorates_json    jsonb        NOT NULL,
    source_epochs_json  jsonb        NOT NULL,
    fact_check_json     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    voting_ends_at      timestamptz  NOT NULL,
    revision            int          NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT ck_square_merge_distinct CHECK (source_square_id <> target_square_id),
    CONSTRAINT ck_square_merge_status CHECK (status IN
        ('VOTING', 'WAIT_TEACHER', 'READY', 'EXECUTED', 'FAILED', 'CANCELLED', 'QUALIFICATION_REVIEW', 'RETURNED')),
    CONSTRAINT ck_square_merge_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE merge_ballot (
    proposal_id varchar(120) NOT NULL REFERENCES square_merge(id) ON DELETE RESTRICT,
    square_id   varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    user_id     varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    decision    varchar(20)  NOT NULL,
    revision    int          NOT NULL,
    at          timestamptz  NOT NULL,
    CONSTRAINT pk_merge_ballot PRIMARY KEY (proposal_id, square_id, user_id),
    CONSTRAINT ck_merge_ballot_decision CHECK (decision IN ('AGREE', 'DISAGREE', 'ABSTAIN'))
);
