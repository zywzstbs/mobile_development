-- P14 课程公告、通知索引、偏好与 outbox 投影。

CREATE TABLE announcement (
    id                    varchar(120) PRIMARY KEY,
    square_id             varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    assignment_id         varchar(120) REFERENCES assignment(id) ON DELETE RESTRICT,
    author_id             varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    publisher_role        varchar(30)  NOT NULL,
    title                 varchar(120) NOT NULL,
    content               varchar(5000) NOT NULL,
    category              varchar(30)  NOT NULL,
    importance            varchar(20)  NOT NULL,
    status                varchar(20)  NOT NULL,
    content_version       int          NOT NULL,
    display_revision      int          NOT NULL DEFAULT 1,
    requires_acknowledgement boolean   NOT NULL DEFAULT false,
    acknowledge_due_at    timestamptz,
    expires_at            timestamptz,
    recipient_snapshot_id varchar(120) NOT NULL,
    recipient_count       int          NOT NULL,
    change_summary        varchar(500),
    revision              int          NOT NULL DEFAULT 1,
    revoked_reason        varchar(500),
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    CONSTRAINT ck_announcement_role CHECK (publisher_role IN ('TEACHER','CLASS_ASSISTANT')),
    CONSTRAINT ck_announcement_category CHECK (category IN
        ('COURSE_INFO','ASSIGNMENT','EXAM','MATERIAL','OTHER')),
    CONSTRAINT ck_announcement_importance CHECK (importance IN ('NORMAL','IMPORTANT')),
    CONSTRAINT ck_announcement_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT ck_announcement_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_announcement_content_version CHECK (content_version BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_announcement_square ON announcement(square_id, created_at DESC);

CREATE TABLE announcement_version (
    id                    varchar(120) PRIMARY KEY,
    announcement_id       varchar(120) NOT NULL REFERENCES announcement(id) ON DELETE RESTRICT,
    content_version       int          NOT NULL,
    display_revision      int          NOT NULL,
    title                 varchar(120) NOT NULL,
    content               varchar(5000) NOT NULL,
    requires_acknowledgement boolean   NOT NULL,
    acknowledge_due_at    timestamptz,
    expires_at            timestamptz,
    recipient_snapshot_id varchar(120) NOT NULL,
    recipient_count       int          NOT NULL,
    change_summary        varchar(500),
    created_by            varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at            timestamptz  NOT NULL,
    CONSTRAINT uq_announcement_version UNIQUE (announcement_id, content_version),
    CONSTRAINT ck_announcement_version CHECK (content_version BETWEEN 1 AND 999999999)
);

CREATE TABLE announcement_recipient (
    snapshot_id   varchar(120) NOT NULL,
    announcement_id varchar(120) NOT NULL REFERENCES announcement(id) ON DELETE RESTRICT,
    content_version int        NOT NULL,
    user_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    PRIMARY KEY (snapshot_id, user_id)
);

CREATE INDEX ix_announcement_recipient_user ON announcement_recipient(user_id, announcement_id);

CREATE TABLE announcement_receipt (
    announcement_id varchar(120) NOT NULL REFERENCES announcement(id) ON DELETE RESTRICT,
    content_version int          NOT NULL,
    user_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    read_at         timestamptz,
    acknowledged_at timestamptz,
    PRIMARY KEY (announcement_id, content_version, user_id)
);

CREATE TABLE announcement_preview (
    id            varchar(120) PRIMARY KEY,
    square_id     varchar(120) NOT NULL REFERENCES square(id) ON DELETE RESTRICT,
    created_by    varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    token_hash    char(64)     NOT NULL UNIQUE,
    payload_json  jsonb        NOT NULL,
    expires_at    timestamptz  NOT NULL,
    used_at       timestamptz,
    created_at    timestamptz  NOT NULL
);

CREATE TABLE announcement_reminder (
    id              varchar(120) PRIMARY KEY,
    announcement_id varchar(120) NOT NULL REFERENCES announcement(id) ON DELETE RESTRICT,
    content_version int          NOT NULL,
    created_by      varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    recorded_count  int          NOT NULL,
    created_at      timestamptz  NOT NULL
);

CREATE INDEX ix_announcement_reminder ON announcement_reminder(announcement_id, content_version, created_at DESC);

CREATE TABLE notification (
    id            varchar(120) PRIMARY KEY,
    user_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    source_type   varchar(40)  NOT NULL,
    source_id     varchar(120) NOT NULL,
    category      varchar(30)  NOT NULL,
    title         varchar(120) NOT NULL,
    body          varchar(1000) NOT NULL DEFAULT '',
    event_version int          NOT NULL DEFAULT 1,
    visible_at    timestamptz  NOT NULL,
    read_at       timestamptz,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT uq_notification_source UNIQUE (user_id, source_type, source_id, event_version)
);

CREATE INDEX ix_notification_user ON notification(user_id, visible_at DESC);

CREATE TABLE notification_preference (
    user_id      varchar(120) PRIMARY KEY REFERENCES app_user(id) ON DELETE RESTRICT,
    channel      varchar(20)  NOT NULL,
    chat_default varchar(20)  NOT NULL,
    quiet_start  varchar(5)   NOT NULL,
    quiet_end    varchar(5)   NOT NULL,
    timezone     varchar(80)  NOT NULL,
    revision     int          NOT NULL DEFAULT 1,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT ck_notification_channel CHECK (channel IN ('IN_APP','SYSTEM')),
    CONSTRAINT ck_notification_chat CHECK (chat_default IN ('ALL','MENTIONS','MUTE')),
    CONSTRAINT ck_notification_revision CHECK (revision BETWEEN 1 AND 999999999)
);
