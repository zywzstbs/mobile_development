-- 第四轮：修复仍停留在 V023 机械投影上的帮助条目。
-- 不修改 V001～V024，也不覆盖已经由 HELP-05 或结构化导入写入的内容。
--
-- HELP-05 和结构化导入都会写入非空 failure_action，并带上 entry.type。
-- 下面只处理失败动作仍为空、入口仍是空对象、步骤/结果/前置/角色仍是默认空结构，
-- 且步骤要么为空、要么恰好是 V023 对 body 的 left(body, 500) 的行。
--
-- body 长度 1～500：步骤改为完整正文这一条。这是仅有的正文，不截断，也不编造入口。
-- body 超过 500：不能把前 500 字当成步骤。撤下发布并清空这条截断步骤，正文留在 body。
-- 只有因此改变了已发布通用帮助的输出时，通用包版本加一。

DO $$
DECLARE
    n int := 0;
    c int;
BEGIN
    WITH fixed AS (
        UPDATE help_article
        SET steps_json = jsonb_build_array(body),
            content_version = content_version + 1,
            revision = revision + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE failure_action = ''
          AND entry_json = '{}'::jsonb
          AND outcomes_json = '[]'::jsonb
          AND prerequisites_json = '[]'::jsonb
          AND roles_json = '[]'::jsonb
          AND audience = 'GENERAL'
          AND published = true
          AND char_length(body) BETWEEN 1 AND 500
          AND steps_json IS DISTINCT FROM jsonb_build_array(body)
          AND (
              steps_json = '[]'::jsonb
              OR steps_json = jsonb_build_array(left(body, 500))
          )
        RETURNING 1
    )
    SELECT count(*) INTO c FROM fixed;
    n := n + c;

    WITH pulled AS (
        UPDATE help_article
        SET published = false,
            steps_json = '[]'::jsonb,
            content_version = content_version + 1,
            revision = revision + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE failure_action = ''
          AND entry_json = '{}'::jsonb
          AND outcomes_json = '[]'::jsonb
          AND prerequisites_json = '[]'::jsonb
          AND roles_json = '[]'::jsonb
          AND audience = 'GENERAL'
          AND published = true
          AND char_length(body) > 500
          AND (
              steps_json = '[]'::jsonb
              OR steps_json = jsonb_build_array(left(body, 500))
          )
        RETURNING 1
    )
    SELECT count(*) INTO c FROM pulled;
    n := n + c;

    UPDATE help_article
    SET steps_json = jsonb_build_array(body),
        content_version = content_version + 1,
        revision = revision + 1,
        updated_at = CURRENT_TIMESTAMP
    WHERE failure_action = ''
      AND entry_json = '{}'::jsonb
      AND outcomes_json = '[]'::jsonb
      AND prerequisites_json = '[]'::jsonb
      AND roles_json = '[]'::jsonb
      AND NOT (audience = 'GENERAL' AND published = true)
      AND char_length(body) BETWEEN 1 AND 500
      AND steps_json IS DISTINCT FROM jsonb_build_array(body)
      AND (
          steps_json = '[]'::jsonb
          OR steps_json = jsonb_build_array(left(body, 500))
      );

    UPDATE help_article
    SET published = false,
        steps_json = '[]'::jsonb,
        content_version = content_version + 1,
        revision = revision + 1,
        updated_at = CURRENT_TIMESTAMP
    WHERE failure_action = ''
      AND entry_json = '{}'::jsonb
      AND outcomes_json = '[]'::jsonb
      AND prerequisites_json = '[]'::jsonb
      AND roles_json = '[]'::jsonb
      AND published = true
      AND audience <> 'GENERAL'
      AND char_length(body) > 500
      AND (
          steps_json = '[]'::jsonb
          OR steps_json = jsonb_build_array(left(body, 500))
      );

    UPDATE help_article
    SET steps_json = '[]'::jsonb,
        content_version = content_version + 1,
        revision = revision + 1,
        updated_at = CURRENT_TIMESTAMP
    WHERE failure_action = ''
      AND entry_json = '{}'::jsonb
      AND outcomes_json = '[]'::jsonb
      AND prerequisites_json = '[]'::jsonb
      AND roles_json = '[]'::jsonb
      AND published = false
      AND char_length(body) > 500
      AND steps_json = jsonb_build_array(left(body, 500));

    IF n > 0 THEN
        UPDATE help_content_pack
        SET version = LEAST(version + 1, 999999999),
            updated_at = CURRENT_TIMESTAMP
        WHERE id = 'GENERAL'
          AND version < 999999999;
    END IF;
END $$;

COMMENT ON COLUMN help_article.steps_json IS
    '客户端读取的步骤。结构化导入和 HELP-05 直接写入；V023 对超长 body 的截断不再当作有效步骤。';
COMMENT ON COLUMN help_article.body IS
    '由 steps 用换行拼接的兼容列，不单独作为帮助响应正文。';
COMMENT ON COLUMN help_article.summary IS
    '由 failure_action 派生的兼容列，不单独作为帮助响应字段。';
