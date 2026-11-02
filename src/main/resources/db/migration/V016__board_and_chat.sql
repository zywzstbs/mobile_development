-- P13 项目留言板与团队聊天。

CREATE TABLE board_topic (
    id                varchar(120) PRIMARY KEY,
    project_id        varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    author_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    title             varchar(120) NOT NULL,
    content           varchar(5000),
    tag               varchar(20)  NOT NULL,
    refs_json         jsonb        NOT NULL DEFAULT '[]'::jsonb,
    mentions_json     jsonb        NOT NULL DEFAULT '[]'::jsonb,
    status            varchar(20)  NOT NULL,
    pinned            boolean      NOT NULL DEFAULT false,
    helpful_reply_id  varchar(120),
    resolution_note   varchar(500),
    close_reason      varchar(500),
    reply_count       int          NOT NULL DEFAULT 0,
    revision          int          NOT NULL DEFAULT 1,
    withdrawn         boolean      NOT NULL DEFAULT false,
    activity_at       timestamptz  NOT NULL,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    withdrawn_at      timestamptz,
    CONSTRAINT ck_board_topic_tag CHECK (tag IN ('QUESTION','SUGGESTION','MATERIAL','CONCLUSION')),
    CONSTRAINT ck_board_topic_status CHECK (status IN ('OPEN','RESOLVED','CLOSED')),
    CONSTRAINT ck_board_topic_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_board_topic_reply_count CHECK (reply_count >= 0)
);

CREATE INDEX ix_board_topic_project ON board_topic(project_id, pinned DESC, activity_at DESC);

CREATE TABLE board_reply (
    id            varchar(120) PRIMARY KEY,
    topic_id      varchar(120) NOT NULL REFERENCES board_topic(id) ON DELETE RESTRICT,
    author_id     varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    reply_to_id   varchar(120) REFERENCES board_reply(id) ON DELETE RESTRICT,
    content       varchar(5000),
    mentions_json jsonb        NOT NULL DEFAULT '[]'::jsonb,
    withdrawn     boolean      NOT NULL DEFAULT false,
    revision      int          NOT NULL DEFAULT 1,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    withdrawn_at  timestamptz,
    CONSTRAINT ck_board_reply_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_board_reply_topic ON board_reply(topic_id, created_at, id);

CREATE TABLE content_revision (
    id            varchar(120) PRIMARY KEY,
    content_type  varchar(10)  NOT NULL,
    content_id    varchar(120) NOT NULL,
    version       int          NOT NULL,
    title         varchar(120) NOT NULL DEFAULT '',
    content       varchar(5000),
    revision      int          NOT NULL,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT uq_content_revision UNIQUE (content_type, content_id, version),
    CONSTRAINT ck_content_revision_type CHECK (content_type IN ('TOPIC','REPLY')),
    CONSTRAINT ck_content_revision_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE topic_follow (
    topic_id   varchar(120) NOT NULL REFERENCES board_topic(id) ON DELETE RESTRICT,
    user_id    varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at timestamptz  NOT NULL,
    PRIMARY KEY (topic_id, user_id)
);

CREATE TABLE chat_counter (
    project_id           varchar(120) PRIMARY KEY REFERENCES project(id) ON DELETE RESTRICT,
    next_sequence        bigint      NOT NULL DEFAULT 1,
    last_change_sequence bigint      NOT NULL DEFAULT 0
);

CREATE TABLE chat_message (
    id                varchar(120) PRIMARY KEY,
    project_id        varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    sequence          bigint       NOT NULL,
    client_message_id varchar(128) NOT NULL,
    author_id         varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    content           varchar(1000),
    refs_json         jsonb        NOT NULL DEFAULT '[]'::jsonb,
    mentions_json     jsonb        NOT NULL DEFAULT '[]'::jsonb,
    withdrawn         boolean      NOT NULL DEFAULT false,
    revision          int          NOT NULL DEFAULT 1,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    withdrawn_at      timestamptz,
    CONSTRAINT uq_chat_client_message UNIQUE (project_id, author_id, client_message_id),
    CONSTRAINT uq_chat_sequence UNIQUE (project_id, sequence),
    CONSTRAINT ck_chat_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_chat_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_chat_message_project ON chat_message(project_id, sequence DESC);

CREATE TABLE chat_change (
    id              varchar(120) PRIMARY KEY,
    project_id      varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    change_sequence bigint       NOT NULL,
    message_id      varchar(120) NOT NULL REFERENCES chat_message(id) ON DELETE RESTRICT,
    message_revision int         NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT uq_chat_change UNIQUE (project_id, change_sequence)
);

CREATE TABLE chat_read_state (
    project_id   varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    user_id      varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    last_sequence bigint      NOT NULL DEFAULT 0,
    updated_at   timestamptz  NOT NULL,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT ck_chat_read_sequence CHECK (last_sequence >= 0)
);

CREATE TABLE notification_outbox (
    id          varchar(120) PRIMARY KEY,
    project_id  varchar(120) REFERENCES project(id) ON DELETE RESTRICT,
    event_type  varchar(60)  NOT NULL,
    payload_json jsonb       NOT NULL,
    created_at  timestamptz  NOT NULL,
    processed_at timestamptz
);

CREATE INDEX ix_notification_outbox_pending ON notification_outbox(processed_at, created_at);
