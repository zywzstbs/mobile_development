-- P03 项目、组队资格、申请、邀请、席位预留与组队码。

CREATE TABLE project (
    id                      varchar(120) PRIMARY KEY,
    assignment_id           varchar(120) NOT NULL REFERENCES assignment(id) ON DELETE RESTRICT,
    square_id               varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    owner_id                varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    mode                    varchar(20)  NOT NULL,
    name                    varchar(120),
    intro                   varchar(2000),
    capacity                int          NOT NULL,
    status                  varchar(20)  NOT NULL,
    closing_cycle           int          NOT NULL DEFAULT 0,
    authorization_epoch     int          NOT NULL DEFAULT 1,
    discussion_time         varchar(200) NOT NULL DEFAULT '',
    recruitment_open        boolean      NOT NULL DEFAULT false,
    recruitment_description varchar(2000) NOT NULL DEFAULT '',
    professional_direction  varchar(200) NOT NULL DEFAULT '',
    weekly_hours            int          NOT NULL DEFAULT 0,
    accept_beginners        boolean      NOT NULL DEFAULT true,
    revision                int          NOT NULL DEFAULT 1,
    created_at              timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_project_mode CHECK (mode IN ('QUICK', 'DETAILED')),
    CONSTRAINT ck_project_status CHECK (status IN ('ACTIVE', 'CLOSING', 'ARCHIVED')),
    CONSTRAINT ck_project_capacity CHECK (capacity BETWEEN 1 AND 20),
    CONSTRAINT ck_project_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_project_closing_cycle CHECK (closing_cycle >= 0),
    CONSTRAINT ck_project_weekly_hours CHECK (weekly_hours BETWEEN 0 AND 168)
);

CREATE INDEX ix_project_assignment ON project (assignment_id);
CREATE INDEX ix_project_square ON project (square_id);
CREATE INDEX ix_project_recruitment ON project (assignment_id, recruitment_open, status);

CREATE TABLE project_stage (
    id          varchar(120) PRIMARY KEY,
    project_id  varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    name        varchar(40)  NOT NULL,
    position    int          NOT NULL,
    revision    int          NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_project_stage_position UNIQUE (project_id, position),
    CONSTRAINT ck_project_stage_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_project_stage_name_nonblank CHECK (length(btrim(name)) > 0)
);

CREATE TABLE role_slot (
    id          varchar(120) PRIMARY KEY,
    project_id  varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    role_name   varchar(60)  NOT NULL,
    duty        varchar(1000) NOT NULL,
    total       int          NOT NULL,
    position    int          NOT NULL,
    revision    int          NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_role_slot_position UNIQUE (project_id, position),
    CONSTRAINT ck_role_slot_total CHECK (total >= 0),
    CONSTRAINT ck_role_slot_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE membership (
    id            varchar(120) PRIMARY KEY,
    project_id    varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    user_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    role_slot_id  varchar(120) REFERENCES role_slot(id) ON DELETE RESTRICT,
    project_role  varchar(20)  NOT NULL,
    status        varchar(20)  NOT NULL,
    color         varchar(7)   NOT NULL,
    color_revision int         NOT NULL DEFAULT 0,
    joined_at     timestamptz  NOT NULL,
    left_at       timestamptz,
    revision      int          NOT NULL DEFAULT 1,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_membership_role CHECK (project_role IN ('LEADER', 'MEMBER')),
    CONSTRAINT ck_membership_status CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
    CONSTRAINT ck_membership_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_membership_active
    ON membership (project_id, user_id) WHERE status = 'ACTIVE';

CREATE INDEX ix_membership_user ON membership (user_id, status);

CREATE TABLE assignment_team_slot (
    id            varchar(120) PRIMARY KEY,
    assignment_id varchar(120) NOT NULL REFERENCES assignment(id) ON DELETE RESTRICT,
    user_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    project_id    varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    membership_id varchar(120) NOT NULL REFERENCES membership(id) ON DELETE RESTRICT,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_assignment_team_slot UNIQUE (assignment_id, user_id)
);

CREATE INDEX ix_assignment_team_slot_project ON assignment_team_slot (project_id);

CREATE TABLE team_application (
    id            varchar(120) PRIMARY KEY,
    project_id    varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    applicant_id  varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    role_slot_id  varchar(120) REFERENCES role_slot(id) ON DELETE RESTRICT,
    message       varchar(500) NOT NULL,
    status        varchar(20)  NOT NULL,
    invitation_id varchar(120),
    reason        varchar(500),
    revision      int          NOT NULL DEFAULT 1,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_team_application_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CLOSED')),
    CONSTRAINT ck_team_application_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_team_application_pending
    ON team_application (project_id, applicant_id) WHERE status = 'PENDING';

CREATE INDEX ix_team_application_applicant ON team_application (applicant_id, created_at);

CREATE TABLE team_invitation (
    id            varchar(120) PRIMARY KEY,
    project_id    varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    recipient_id  varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    role_slot_id  varchar(120) REFERENCES role_slot(id) ON DELETE RESTRICT,
    message       varchar(500) NOT NULL,
    status        varchar(20)  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    reason        varchar(500),
    revision      int          NOT NULL DEFAULT 1,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_team_invitation_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_team_invitation_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_team_invitation_pending
    ON team_invitation (project_id, recipient_id) WHERE status = 'PENDING';

CREATE INDEX ix_team_invitation_recipient ON team_invitation (recipient_id, created_at);

CREATE TABLE seat_reservation (
    id            varchar(120) PRIMARY KEY,
    project_id    varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    user_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    role_slot_id  varchar(120) REFERENCES role_slot(id) ON DELETE RESTRICT,
    invitation_id varchar(120) NOT NULL REFERENCES team_invitation(id) ON DELETE RESTRICT,
    status        varchar(20)  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    revision      int          NOT NULL DEFAULT 1,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_seat_reservation_status CHECK (status IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'CONSUMED')),
    CONSTRAINT ck_seat_reservation_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_seat_reservation_active
    ON seat_reservation (project_id, user_id) WHERE status = 'ACTIVE';

CREATE INDEX ix_seat_reservation_invitation ON seat_reservation (invitation_id);
CREATE INDEX ix_seat_reservation_expires ON seat_reservation (expires_at);

CREATE TABLE project_code (
    id             varchar(120) PRIMARY KEY,
    project_id     varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    code_hash      char(64)     NOT NULL,
    code_ciphertext text        NOT NULL,
    key_id         varchar(64)  NOT NULL,
    status         varchar(20)  NOT NULL,
    code_version   int          NOT NULL DEFAULT 1,
    revision       int          NOT NULL DEFAULT 1,
    created_at     timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_project_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_project_code_status CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED')),
    CONSTRAINT ck_project_code_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_project_code_active
    ON project_code (project_id) WHERE status = 'ACTIVE';
