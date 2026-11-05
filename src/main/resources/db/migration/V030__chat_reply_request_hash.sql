-- 非空 reply_to_id 的发送写入 request_hash_version = 2。
-- V019 的 ck_chat_request_hash 只接受版本 1，合法回复会被检查约束拒绝。
-- 旧版本 1（无回复）和 legacy 双空保持可写入、可保留。

ALTER TABLE chat_message
    DROP CONSTRAINT ck_chat_request_hash;

ALTER TABLE chat_message
    ADD CONSTRAINT ck_chat_request_hash CHECK (
        (request_hash IS NULL AND request_hash_version IS NULL AND reply_to_id IS NULL)
        OR (request_hash ~ '^[0-9a-f]{64}$' AND request_hash_version = 1 AND reply_to_id IS NULL)
        OR (request_hash ~ '^[0-9a-f]{64}$' AND request_hash_version = 2 AND reply_to_id IS NOT NULL)
    );
