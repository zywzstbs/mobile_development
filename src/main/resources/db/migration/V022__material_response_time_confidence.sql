-- U02：材料提交时间可信度。
--
-- V021 已执行且不重写。V021 把 material_request.updated_at 回填成提交时间，
-- 但旧实现的提交、审核、撤回都会更新 updated_at，因此该值只能证明最后修改时间。
-- 本迁移只按 V021 的确定性 legacy ID 规则识别回填行，把旧值移入
-- legacy_recorded_at 供内部诊断，created_at 置空并标记 UNKNOWN；
-- 其他真实新提交保持原时间并标记 EXACT。不按“时间看起来可疑”猜测来源。

ALTER TABLE material_response ALTER COLUMN created_at DROP NOT NULL;
ALTER TABLE material_response ADD COLUMN created_at_confidence varchar(10);
ALTER TABLE material_response ADD COLUMN legacy_recorded_at timestamptz;
ALTER TABLE material_response ADD COLUMN time_source varchar(30);
ALTER TABLE material_response ADD COLUMN time_evidence varchar(200);
ALTER TABLE material_response ADD COLUMN time_recovered_by varchar(120);
ALTER TABLE material_response ADD COLUMN time_recovered_at timestamptz;

UPDATE material_response r
SET legacy_recorded_at = r.created_at,
    created_at = NULL,
    created_at_confidence = 'UNKNOWN',
    time_source = 'LEGACY_UNKNOWN'
FROM material_request mr
WHERE r.material_id = mr.id
  AND r.id = left('legacy-' || mr.id, 120)
  AND r.id LIKE 'legacy-%'
  AND r.author_id = mr.recipient_id;

UPDATE material_response
SET created_at_confidence = 'EXACT',
    time_source = 'SERVER_SUBMISSION'
WHERE created_at_confidence IS NULL;

ALTER TABLE material_response ALTER COLUMN created_at_confidence SET NOT NULL;
ALTER TABLE material_response ALTER COLUMN time_source SET NOT NULL;

ALTER TABLE material_response
    ADD CONSTRAINT ck_material_response_time_confidence CHECK (
        (created_at_confidence = 'EXACT' AND created_at IS NOT NULL)
        OR (created_at_confidence = 'UNKNOWN' AND created_at IS NULL)),
    ADD CONSTRAINT ck_material_response_time_source CHECK (
        time_source IN ('SERVER_SUBMISSION', 'RECOVERED_EVIDENCE', 'LEGACY_UNKNOWN')),
    ADD CONSTRAINT ck_material_response_legacy_time CHECK (
        time_source <> 'LEGACY_UNKNOWN' OR created_at_confidence = 'UNKNOWN'),
    ADD CONSTRAINT ck_material_response_recovery CHECK (
        time_source <> 'RECOVERED_EVIDENCE'
        OR (time_evidence IS NOT NULL AND time_recovered_at IS NOT NULL));

COMMENT ON COLUMN material_response.legacy_recorded_at IS
    'V021 回填的最后修改时间，仅诊断/审计，不作为提交时间向前端输出';
COMMENT ON COLUMN material_response.created_at_confidence IS
    'EXACT=真实服务器提交时间；UNKNOWN=历史提交时间不可恢复，created_at 必须为 NULL';
COMMENT ON COLUMN material_response.time_source IS
    'SERVER_SUBMISSION/RECOVERED_EVIDENCE/LEGACY_UNKNOWN';
