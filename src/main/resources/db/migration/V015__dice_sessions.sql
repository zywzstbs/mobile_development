-- P12 正式骰子：任务无主无提交时的参加、投骰和领取意愿。

CREATE TABLE dice_session (
    id                  varchar(120) PRIMARY KEY,
    project_id          varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    task_id             varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    created_by          varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    rule                varchar(10)  NOT NULL,
    target_value        int,
    max_rounds          int          NOT NULL,
    round_no            int          NOT NULL DEFAULT 1,
    status              varchar(20)  NOT NULL,
    task_revision       int          NOT NULL,
    revision            int          NOT NULL DEFAULT 1,
    invite_ends_at      timestamptz  NOT NULL,
    roll_ends_at        timestamptz  NOT NULL,
    source_frozen       boolean      NOT NULL DEFAULT false,
    proposed_owner_id   varchar(120) REFERENCES app_user(id) ON DELETE RESTRICT,
    outcome_decision    varchar(20),
    claim_status        varchar(20)  NOT NULL DEFAULT 'NOT_REQUESTED',
    termination_reason  varchar(500),
    winner_id           varchar(120) REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT ck_dice_rule CHECK (rule IN ('MAX', 'MIN', 'TARGET')),
    CONSTRAINT ck_dice_status CHECK (status IN
        ('INVITING', 'ROLLING', 'TIE_ROUND', 'AWAITING_CLAIM', 'CLAIMED',
         'DECLINED', 'UNRESOLVED', 'TERMINATED', 'EXPIRED', 'INVALIDATED')),
    CONSTRAINT ck_dice_outcome CHECK (outcome_decision IS NULL OR outcome_decision IN ('ACCEPT', 'DECLINE')),
    CONSTRAINT ck_dice_claim CHECK (claim_status IN
        ('NOT_REQUESTED', 'AWAITING', 'CLAIMED', 'DECLINED', 'INVALIDATED', 'EXPIRED')),
    CONSTRAINT ck_dice_target CHECK
        ((rule = 'TARGET' AND target_value BETWEEN 1 AND 6)
         OR (rule <> 'TARGET' AND target_value IS NULL)),
    CONSTRAINT ck_dice_rounds CHECK (max_rounds = 3 AND round_no BETWEEN 1 AND 3),
    CONSTRAINT ck_dice_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_dice_active_task ON dice_session (task_id)
    WHERE status IN ('INVITING', 'ROLLING', 'TIE_ROUND', 'AWAITING_CLAIM');

CREATE INDEX ix_dice_project ON dice_session(project_id, created_at DESC);

CREATE TABLE dice_participant (
    id             varchar(120) PRIMARY KEY,
    session_id     varchar(120) NOT NULL REFERENCES dice_session(id) ON DELETE RESTRICT,
    user_id        varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    decision       varchar(20)  NOT NULL,
    active_in_round boolean     NOT NULL DEFAULT true,
    revision       int          NOT NULL DEFAULT 1,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT uq_dice_participant UNIQUE (session_id, user_id),
    CONSTRAINT ck_dice_participant_decision CHECK (decision IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    CONSTRAINT ck_dice_participant_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE dice_roll (
    id          varchar(120) PRIMARY KEY,
    session_id  varchar(120) NOT NULL REFERENCES dice_session(id) ON DELETE RESTRICT,
    round_no    int          NOT NULL,
    user_id     varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    value       int          NOT NULL,
    created_at  timestamptz  NOT NULL,
    CONSTRAINT uq_dice_roll UNIQUE (session_id, round_no, user_id),
    CONSTRAINT ck_dice_roll_value CHECK (value BETWEEN 1 AND 6),
    CONSTRAINT ck_dice_roll_round CHECK (round_no BETWEEN 1 AND 3)
);

CREATE INDEX ix_dice_roll_round ON dice_roll(session_id, round_no);

CREATE TABLE dice_round (
    id            varchar(120) PRIMARY KEY,
    session_id    varchar(120) NOT NULL REFERENCES dice_session(id) ON DELETE RESTRICT,
    round_no      int          NOT NULL,
    candidate_ids jsonb        NOT NULL,
    winner_id     varchar(120) REFERENCES app_user(id) ON DELETE RESTRICT,
    revealed      boolean      NOT NULL DEFAULT false,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    CONSTRAINT uq_dice_round UNIQUE (session_id, round_no),
    CONSTRAINT ck_dice_round_no CHECK (round_no BETWEEN 1 AND 3)
);
