-- F03/R07：聊天完整请求摘要（clientMessageId 业务唯一键的规范化版本）
-- F04/R05：聊天独立变更同步游标（绑定本人/项目/授权代次，7天有效）
-- F05/R06：本人已授权下发的连续序号区间（阅读游标只能覆盖连续区间）
--
-- 存量 chat_message 的 request_hash 保持 NULL，表示 legacy/unverifiable：
-- 旧实现只保存了 trim 后的正文与去重后的 mentions，无法无损还原原始完整请求，
-- 因此不猜测“相同”，也不再恢复旧正文。新消息写入 version=1 的规范摘要。

ALTER TABLE chat_message
    ADD COLUMN request_hash char(64),
    ADD COLUMN request_hash_version int;

ALTER TABLE chat_message
    ADD CONSTRAINT ck_chat_request_hash CHECK (
        (request_hash IS NULL AND request_hash_version IS NULL)
        OR (request_hash ~ '^[0-9a-f]{64}$' AND request_hash_version = 1)
    );

CREATE TABLE chat_sync_cursor (
    id                      varchar(120) PRIMARY KEY,
    token_hash              char(64)     NOT NULL,
    user_id                 varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    project_id              varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    authorization_epoch     int          NOT NULL,
    last_change_sequence    bigint       NOT NULL DEFAULT 0,
    expires_at              timestamptz  NOT NULL,
    created_at              timestamptz  NOT NULL,
    CONSTRAINT uq_chat_sync_cursor_hash UNIQUE (token_hash),
    CONSTRAINT ck_chat_sync_cursor_sequence CHECK (last_change_sequence >= 0),
    CONSTRAINT ck_chat_sync_cursor_epoch CHECK (authorization_epoch >= 0)
);

CREATE INDEX ix_chat_sync_cursor_expires ON chat_sync_cursor (expires_at);
CREATE INDEX ix_chat_sync_cursor_owner ON chat_sync_cursor (user_id, project_id);

CREATE TABLE chat_delivery_range (
    id                  varchar(120) PRIMARY KEY,
    user_id             varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    project_id          varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    authorization_epoch int          NOT NULL,
    start_sequence      bigint       NOT NULL,
    end_sequence        bigint       NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT ck_chat_delivery_range_order CHECK (start_sequence >= 1 AND start_sequence <= end_sequence),
    CONSTRAINT ck_chat_delivery_range_epoch CHECK (authorization_epoch >= 0)
);

CREATE INDEX ix_chat_delivery_range_lookup
    ON chat_delivery_range (user_id, project_id, authorization_epoch, start_sequence);
CREATE INDEX ix_chat_delivery_range_project
    ON chat_delivery_range (project_id, authorization_epoch);
