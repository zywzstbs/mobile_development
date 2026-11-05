-- P62：注册申请、作业创建者、通知偏好新结构、通知来源版本、聊天回复。

ALTER TABLE school
    ADD COLUMN registration_enabled boolean NOT NULL DEFAULT false;

ALTER TABLE app_user
    ADD COLUMN account_status varchar(30) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_account_status
        CHECK (account_status IN ('ACTIVE', 'PENDING_REVIEW', 'REJECTED'));

CREATE TABLE registration_application (
    id                   varchar(120) PRIMARY KEY,
    user_id              varchar(120) NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE RESTRICT,
    requested_role       varchar(20)  NOT NULL,
    status               varchar(30)  NOT NULL,
    school_id            varchar(120) NOT NULL REFERENCES school(id) ON DELETE RESTRICT,
    teacher_application  text,
    revision             int          NOT NULL DEFAULT 1,
    reviewed_by          varchar(120),
    reviewed_at          timestamptz,
    review_reason        varchar(500),
    created_at           timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_registration_role CHECK (requested_role IN ('STUDENT', 'TEACHER')),
    CONSTRAINT ck_registration_status CHECK (status IN ('ACTIVE', 'PENDING_REVIEW', 'REJECTED')),
    CONSTRAINT ck_registration_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_registration_teacher_text CHECK (
        (requested_role = 'TEACHER'
            AND teacher_application IS NOT NULL
            AND char_length(btrim(teacher_application)) BETWEEN 5 AND 500)
        OR (requested_role = 'STUDENT' AND teacher_application IS NULL)
    )
);

CREATE INDEX ix_registration_status ON registration_application (status, created_at);

CREATE TABLE registration_receipt (
    endpoint         varchar(80)  NOT NULL,
    idempotency_key  varchar(128) NOT NULL,
    fingerprint      char(64)     NOT NULL,
    key_id           varchar(64)  NOT NULL,
    receipt_json     jsonb        NOT NULL,
    expires_at       timestamptz  NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (endpoint, idempotency_key)
);

CREATE INDEX ix_registration_receipt_expires ON registration_receipt (expires_at);

ALTER TABLE assignment
    ADD COLUMN created_by varchar(120) REFERENCES app_user(id) ON DELETE RESTRICT;

ALTER TABLE assignment
    ALTER COLUMN requirements TYPE varchar(10000);

ALTER TABLE notification
    ADD COLUMN source_version int;

UPDATE notification n
SET source_version = a.content_version
FROM announcement a
WHERE n.source_type = 'ANNOUNCEMENT'
  AND n.source_id = a.id
  AND n.event_version = a.content_version;

ALTER TABLE notification_preference
    ADD COLUMN course_notices varchar(20),
    ADD COLUMN mention_channel varchar(20),
    ADD COLUMN support_replies varchar(20),
    ADD COLUMN quiet_enabled boolean,
    ADD COLUMN project_chat_modes_json jsonb NOT NULL DEFAULT '[]'::jsonb;

-- 旧结构无法表达 quietHours.enabled，迁移时记为启用，保留原来的起止时间。
-- 三个渠道沿用原来的单一 channel，不默认改成静音。
UPDATE notification_preference
SET course_notices = channel,
    mention_channel = channel,
    support_replies = channel,
    quiet_enabled = true
WHERE course_notices IS NULL;

ALTER TABLE notification_preference
    ALTER COLUMN course_notices SET NOT NULL,
    ALTER COLUMN mention_channel SET NOT NULL,
    ALTER COLUMN support_replies SET NOT NULL,
    ALTER COLUMN quiet_enabled SET NOT NULL;

ALTER TABLE notification_preference
    DROP CONSTRAINT ck_notification_channel;

ALTER TABLE notification_preference
    DROP COLUMN channel;

ALTER TABLE notification_preference
    ADD CONSTRAINT ck_np_course CHECK (course_notices IN ('IN_APP', 'SYSTEM')),
    ADD CONSTRAINT ck_np_mention CHECK (mention_channel IN ('IN_APP', 'SYSTEM')),
    ADD CONSTRAINT ck_np_support CHECK (support_replies IN ('IN_APP', 'SYSTEM'));

ALTER TABLE chat_message
    ADD COLUMN reply_to_id varchar(120);

ALTER TABLE chat_message
    ADD CONSTRAINT fk_chat_message_reply
        FOREIGN KEY (reply_to_id) REFERENCES chat_message(id) ON DELETE RESTRICT;
