-- P00 基础迁移：只建立工程元数据表，不提前创建 P01 的身份/幂等/审计表。
-- 所有业务表在后续阶段按 docs/backend/03 建立；本表用于记录已应用的契约基线。

CREATE TABLE app_meta (
    id          varchar(120) PRIMARY KEY,
    meta_key    varchar(120) NOT NULL,
    meta_value  text         NOT NULL,
    revision    int          NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_app_meta_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_app_meta_key_nonblank CHECK (length(btrim(meta_key)) > 0),
    CONSTRAINT uq_app_meta_key UNIQUE (meta_key)
);

COMMENT ON TABLE app_meta IS '工程/契约基线元数据；业务数据不存放于此';
COMMENT ON COLUMN app_meta.revision IS '应用层乐观版本，从 1 开始';

INSERT INTO app_meta (id, meta_key, meta_value)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'schema_baseline', 'P00'),
    ('00000000-0000-0000-0000-000000000002', 'contract_version', '1.1');
