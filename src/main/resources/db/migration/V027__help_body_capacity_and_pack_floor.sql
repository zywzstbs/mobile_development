-- 第五轮：合法步骤拼接必须放得进兼容列；已执行的 V024 不能改。
-- 旧客户端 knownVersion 曾是已发布通用条目的 max(content_version)。
-- V024 只用迁移当时仍发布的条目，最高版本被撤下后可能与旧缓存撞成同一个数。
-- 这里用库里仍在的全部 content_version 做上限。帮助条目没有物理删除，
-- 这个上限不低于旧缓存里出现过、且行还在的版本。版本类型仍是整数。

ALTER TABLE help_article
    ALTER COLUMN body TYPE varchar(10019);

COMMENT ON COLUMN help_article.body IS
    '由 steps 用换行拼接的兼容列。上限 10019，对应 20 条各 500 字再加 19 个换行。不截断，也不单独作为帮助响应正文。';

UPDATE help_content_pack AS pack
SET version = LEAST(999999999, src.ceiling + 1),
    updated_at = CURRENT_TIMESTAMP
FROM (
    SELECT COALESCE(MAX(content_version), 0) AS ceiling
    FROM help_article
) AS src
WHERE pack.id = 'GENERAL'
  AND pack.version <= src.ceiling
  AND pack.version < 999999999;
