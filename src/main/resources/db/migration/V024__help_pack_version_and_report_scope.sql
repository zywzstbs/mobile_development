-- P58 第三轮：通用帮助包单调版本，以及举报来源广场。
-- 不修改 V001～V023。无法从现有来源追溯的广场保持空，不编造。

-- audience 是 V018 的原始范围。scope 只在和 square_id 不矛盾时向 audience 对齐。
-- GENERAL 却带广场、COURSE 却没有广场的行不改归属。
UPDATE help_article
SET scope = audience
WHERE scope IS DISTINCT FROM audience
  AND (
        (audience = 'GENERAL' AND square_id IS NULL)
        OR (audience = 'COURSE' AND square_id IS NOT NULL)
      );

CREATE TABLE help_content_pack (
    id         varchar(40) PRIMARY KEY,
    version    int         NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_help_content_pack_version CHECK (version BETWEEN 1 AND 999999999)
);

COMMENT ON TABLE help_content_pack IS
    '通用帮助离线包版本。任一可见内容变化都加一，不再取单条 content_version 的最大值。';
COMMENT ON COLUMN help_content_pack.updated_at IS
    '最近一次包版本推进的时间。空包也使用这里记下的时间，读取时不取当前时间。';

-- 旧客户端把 version 当成已发布通用条目的 max(content_version)，空包时服务端返回 1。
-- 初始版本取该最大值再加 1；没有条目时按旧空包的 1 再加 1，得到 2。
-- 因此新版本严格大于旧缓存里可能出现的 max，不会被误判为未变化。
INSERT INTO help_content_pack (id, version, updated_at)
SELECT 'GENERAL',
       CASE
           WHEN max_version IS NULL THEN 2
           WHEN max_version >= 999999999 THEN 999999999
           ELSE max_version + 1
       END,
       CURRENT_TIMESTAMP
FROM (
    SELECT MAX(content_version) AS max_version
    FROM help_article
    WHERE audience = 'GENERAL' AND published = true
) src;

ALTER TABLE content_report
    ADD COLUMN square_id varchar(120) REFERENCES square(id) ON DELETE RESTRICT;

COMMENT ON COLUMN content_report.square_id IS
    '来源内容所属课程广场。能从现有主题、回复或消息追溯则回填；追溯不到保持空。空值只接受 GLOBAL 处理授权。';

UPDATE content_report r
SET square_id = p.square_id
FROM board_topic t
JOIN project p ON p.id = t.project_id
WHERE r.content_type = 'BOARD_TOPIC'
  AND r.content_id = t.id
  AND r.square_id IS NULL;

UPDATE content_report r
SET square_id = p.square_id
FROM board_reply br
JOIN board_topic t ON t.id = br.topic_id
JOIN project p ON p.id = t.project_id
WHERE r.content_type = 'BOARD_REPLY'
  AND r.content_id = br.id
  AND r.square_id IS NULL;

UPDATE content_report r
SET square_id = p.square_id
FROM chat_message m
JOIN project p ON p.id = m.project_id
WHERE r.content_type = 'CHAT_MESSAGE'
  AND r.content_id = m.id
  AND r.square_id IS NULL;
