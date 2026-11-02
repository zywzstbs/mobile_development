-- T01：材料回应按版本留痕，审核固定被核对版本。
-- 旧实现只保存最后一份 response_json，接口无法返回 responses[] 历史，
-- 也无法证明教师审核的是哪一版正文。这里补齐真实版本与审核记录；
-- 存量数据只迁移能够证明的当前版本，不编造更早正文或时间。

CREATE TABLE material_response (
    id            varchar(120) PRIMARY KEY,
    material_id   varchar(120) NOT NULL REFERENCES material_request(id) ON DELETE CASCADE,
    version       int          NOT NULL,
    author_id     varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    content       varchar(4000) NOT NULL DEFAULT '',
    evidence_json jsonb        NOT NULL DEFAULT '[]'::jsonb,
    withdrawn     boolean      NOT NULL DEFAULT false,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT ck_material_response_version CHECK (version BETWEEN 1 AND 999999999)
);

CREATE UNIQUE INDEX uq_material_response_version
    ON material_response (material_id, version);
CREATE INDEX ix_material_response_author
    ON material_response (author_id, created_at);

CREATE TABLE material_review (
    id               varchar(120) PRIMARY KEY,
    material_id      varchar(120) NOT NULL REFERENCES material_request(id) ON DELETE CASCADE,
    response_version int          NOT NULL,
    reviewer_id      varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    decision         varchar(20)  NOT NULL,
    reason           varchar(500) NOT NULL DEFAULT '',
    created_at       timestamptz  NOT NULL,
    CONSTRAINT ck_material_review_decision CHECK (decision IN ('ACCEPT', 'RETURN')),
    CONSTRAINT ck_material_review_version CHECK (response_version BETWEEN 1 AND 999999999)
);

CREATE INDEX ix_material_review_material
    ON material_review (material_id, response_version, created_at);

-- 存量：response_json 只保存最后一次提交，故只回填该可证明的当前版本；
-- created_at 使用 material_request.updated_at（服务器真实写入时间），
-- 旧实现没有保存证据引用，evidence_json 保持空数组，不伪造。
INSERT INTO material_response (id, material_id, version, author_id, content, evidence_json,
                               withdrawn, created_at)
SELECT substring('legacy-' || mr.id, 1, 120), mr.id, mr.response_version, mr.recipient_id,
       COALESCE(mr.response_json ->> 'content', ''), '[]'::jsonb,
       mr.status = 'WITHDRAWN', mr.updated_at
FROM material_request mr
WHERE mr.response_version >= 1
  AND mr.response_json ? 'content';
