-- G01/S01：V019 的 chat_sync_cursor 是“服务端消费式”游标，
-- 旧实现已在请求内原地推进位置，无法证明客户端收到过哪些变更页。
-- 这些 token 只用于可重建的聊天同步基线，不涉及消息、已读、幂等回执或作品数据，
-- 因此升级时统一失效，客户端按既有 409 CHAT_SYNC_EXPIRED 重建历史基线。
DELETE FROM chat_sync_cursor;

-- G05/S04+S06（D01）：保留每个广场唯一当前任课教师，
-- 由受控管理员 CLI 为指定教师授予单个人员事项的处理资格。
CREATE TABLE personnel_case_grant (
    id          varchar(120) PRIMARY KEY,
    case_id     varchar(120) NOT NULL REFERENCES personnel_case(id) ON DELETE RESTRICT,
    user_id     varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    status      varchar(20)  NOT NULL,
    revision    int          NOT NULL DEFAULT 1,
    granted_by  varchar(120) NOT NULL,
    granted_at  timestamptz  NOT NULL,
    revoked_by  varchar(120),
    revoked_at  timestamptz,
    reason      varchar(500) NOT NULL DEFAULT '',
    expires_at  timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_personnel_case_grant_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_personnel_case_grant_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_personnel_case_grant_expiry CHECK (expires_at IS NULL OR expires_at > granted_at)
);

-- 同一 case/教师同时最多一条有效授权；过期或撤销后先迁移旧行，再重新授予。
CREATE UNIQUE INDEX uq_personnel_case_grant_active
    ON personnel_case_grant (case_id, user_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_personnel_case_grant_user
    ON personnel_case_grant (user_id, status, expires_at);
CREATE INDEX ix_personnel_case_grant_case
    ON personnel_case_grant (case_id, status);
