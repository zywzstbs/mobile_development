-- 第四轮：匿名通用帮助分页快照。
-- 不修改 V001～V025。匿名没有 app_user，不能把 user_id 写成空串或借用某个登录用户。

ALTER TABLE cursor_snapshot
    ADD COLUMN subject_type varchar(20) NOT NULL DEFAULT 'USER',
    ADD COLUMN subject_key varchar(120);

UPDATE cursor_snapshot
SET subject_key = user_id
WHERE subject_key IS NULL;

ALTER TABLE cursor_snapshot
    ALTER COLUMN subject_key SET NOT NULL;

ALTER TABLE cursor_snapshot
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE cursor_snapshot
    ADD CONSTRAINT ck_cursor_snapshot_subject CHECK (
        (subject_type = 'USER' AND user_id IS NOT NULL AND subject_key = user_id)
        OR (subject_type = 'ANONYMOUS' AND user_id IS NULL AND subject_key = 'PUBLIC')
    );

COMMENT ON COLUMN cursor_snapshot.subject_type IS
    'USER 绑定 app_user；ANONYMOUS 只用于没有登录身份的公共快照，subject_key 固定为 PUBLIC。';
COMMENT ON COLUMN cursor_snapshot.subject_key IS
    'USER 时等于 user_id。ANONYMOUS 时为 PUBLIC，不引用 app_user。';
