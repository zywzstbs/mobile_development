-- P15 帮助内容、私人求助与内容举报。

CREATE TABLE help_article (
    id            varchar(120) PRIMARY KEY,
    audience      varchar(20)  NOT NULL,
    square_id     varchar(120) REFERENCES square(id) ON DELETE RESTRICT,
    title         varchar(120) NOT NULL,
    summary       varchar(500) NOT NULL DEFAULT '',
    body          varchar(10000) NOT NULL,
    category      varchar(40)  NOT NULL DEFAULT 'GENERAL',
    app_version_range varchar(40) NOT NULL DEFAULT '*',
    published     boolean      NOT NULL DEFAULT true,
    revision      int          NOT NULL DEFAULT 1,
    updated_at    timestamptz  NOT NULL,
    CONSTRAINT ck_help_audience CHECK (audience IN ('GENERAL','COURSE')),
    CONSTRAINT ck_help_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE help_feedback (
    article_id varchar(120) NOT NULL REFERENCES help_article(id) ON DELETE RESTRICT,
    user_id    varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    helpful    boolean      NOT NULL,
    updated_at timestamptz  NOT NULL,
    PRIMARY KEY (article_id, user_id)
);

CREATE TABLE support_route (
    id        varchar(120) PRIMARY KEY,
    type      varchar(30)  NOT NULL,
    name      varchar(120) NOT NULL,
    available boolean      NOT NULL DEFAULT true,
    CONSTRAINT ck_support_route_type CHECK (type IN
        ('NONE','CONNECTION','COURSE','PROJECTS','APPLICATIONS','GRADES','NOTIFICATIONS','SUPPORT'))
);

INSERT INTO support_route(id, type, name, available) VALUES
 ('route_support','SUPPORT','平台使用求助',true),
 ('route_course','COURSE','课程与教学求助',true),
 ('route_grades','GRADES','成绩与评价求助',true);

CREATE TABLE support_ticket (
    id                varchar(120) PRIMARY KEY,
    requester_id      varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    route_id          varchar(120) NOT NULL REFERENCES support_route(id) ON DELETE RESTRICT,
    client_request_id varchar(120) NOT NULL,
    subject           varchar(120) NOT NULL,
    body              varchar(5000) NOT NULL,
    attachments_json  jsonb        NOT NULL DEFAULT '[]'::jsonb,
    status            varchar(20)  NOT NULL,
    assignee_id       varchar(120) REFERENCES app_user(id) ON DELETE RESTRICT,
    resolution        varchar(1000),
    resolved_by       varchar(120) REFERENCES app_user(id) ON DELETE RESTRICT,
    revision          int          NOT NULL DEFAULT 1,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    closed_at         timestamptz,
    reopen_until      timestamptz,
    CONSTRAINT uq_support_client_request UNIQUE (requester_id, client_request_id),
    CONSTRAINT ck_support_status CHECK (status IN ('OPEN','IN_PROGRESS','WAITING_USER','ANSWERED','CLOSED')),
    CONSTRAINT ck_support_revision CHECK (revision BETWEEN 1 AND 999999999)
);

CREATE TABLE support_reply (
    id         varchar(120) PRIMARY KEY,
    ticket_id  varchar(120) NOT NULL REFERENCES support_ticket(id) ON DELETE RESTRICT,
    author_id  varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    body       varchar(5000) NOT NULL,
    created_at timestamptz  NOT NULL
);

CREATE TABLE support_history (
    id         varchar(120) PRIMARY KEY,
    ticket_id  varchar(120) NOT NULL REFERENCES support_ticket(id) ON DELETE RESTRICT,
    action     varchar(40)  NOT NULL,
    actor_id   varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    reason     varchar(500) NOT NULL DEFAULT '',
    created_at timestamptz  NOT NULL
);

CREATE TABLE operator_grant (
    id         varchar(120) PRIMARY KEY,
    user_id    varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    scope_type varchar(20)  NOT NULL,
    scope_id   varchar(120),
    capability varchar(40)  NOT NULL,
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL,
    CONSTRAINT uq_operator_grant UNIQUE (user_id, scope_type, scope_id, capability)
);

CREATE TABLE content_report (
    id                varchar(120) PRIMARY KEY,
    reporter_id       varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    client_request_id varchar(120) NOT NULL,
    content_type      varchar(20)  NOT NULL,
    content_id        varchar(120) NOT NULL,
    source_version    int          NOT NULL,
    excerpt           varchar(500) NOT NULL,
    reason            varchar(500) NOT NULL,
    status            varchar(20)  NOT NULL,
    assignee_id       varchar(120) REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT uq_report_client_request UNIQUE (reporter_id, client_request_id),
    CONSTRAINT ck_report_content_type CHECK (content_type IN ('TOPIC','REPLY','CHAT_MESSAGE')),
    CONSTRAINT ck_report_status CHECK (status IN ('OPEN','ACCEPTED','RECORDED','CLOSED'))
);

CREATE TABLE report_handling (
    id         varchar(120) PRIMARY KEY,
    report_id  varchar(120) NOT NULL REFERENCES content_report(id) ON DELETE RESTRICT,
    action     varchar(30)  NOT NULL,
    actor_id   varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    note       varchar(500) NOT NULL DEFAULT '',
    created_at timestamptz  NOT NULL,
    CONSTRAINT ck_report_action CHECK (action IN ('ACCEPT','RECORD_RESULT','CLOSE'))
);
