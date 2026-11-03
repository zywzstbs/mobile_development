-- P58 求助、帮助反馈、举报状态与合并阶段名称。
-- 不修改 V001～V022。无法从旧列推导的课程范围、路由接收人保持空，不编造。

ALTER TABLE support_route
    ADD COLUMN category varchar(40) NOT NULL DEFAULT 'OPERATION',
    ADD COLUMN square_id varchar(120) REFERENCES square(id) ON DELETE RESTRICT,
    ADD COLUMN receiver_user_id varchar(120) REFERENCES app_user(id) ON DELETE RESTRICT;

UPDATE support_route SET category = CASE type
    WHEN 'SUPPORT' THEN 'OPERATION'
    WHEN 'COURSE' THEN 'COURSE_REQUIREMENT'
    WHEN 'GRADES' THEN 'GRADES'
    ELSE category
END;

ALTER TABLE support_route
    ADD CONSTRAINT ck_support_route_category CHECK (category IN
        ('OPERATION', 'COURSE_REQUIREMENT', 'GRADES'));

COMMENT ON COLUMN support_route.receiver_user_id IS
    '当前真实接收人。历史路由没有接收人，保持空；空接收人不可用于新建工单。';
COMMENT ON COLUMN support_route.square_id IS
    '课程范围。历史 COURSE 路由没有广场，保持空，不能当作任意课程的接收渠道。';

ALTER TABLE support_ticket
    ADD COLUMN category varchar(40),
    ADD COLUMN title varchar(60),
    ADD COLUMN content varchar(3000),
    ADD COLUMN square_id varchar(120) REFERENCES square(id) ON DELETE RESTRICT,
    ADD COLUMN context_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN attachment_file_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN request_digest char(64),
    ADD COLUMN resolved_by_requester boolean NOT NULL DEFAULT false,
    ADD COLUMN resolution_note varchar(1000),
    ADD COLUMN closure_next_action varchar(500),
    ADD COLUMN related_ticket_id varchar(120);

UPDATE support_ticket t
SET category = CASE r.category
        WHEN 'COURSE_REQUIREMENT' THEN 'COURSE_REQUIREMENT'
        WHEN 'OPERATION' THEN 'OPERATION'
        ELSE 'OPERATION'
    END,
    title = left(t.subject, 60),
    content = left(t.body, 3000)
FROM support_route r
WHERE r.id = t.route_id
  AND t.category IS NULL;

UPDATE support_ticket
SET category = 'OPERATION',
    title = left(subject, 60),
    content = left(body, 3000)
WHERE category IS NULL;

ALTER TABLE support_ticket ALTER COLUMN category SET NOT NULL;
ALTER TABLE support_ticket ALTER COLUMN title SET NOT NULL;
ALTER TABLE support_ticket ALTER COLUMN content SET NOT NULL;

-- 先放开旧检查再改值。首次提交和带 REOPEN 历史的 OPEN 都改为 SUBMITTED：
-- P58 没有 OPEN；重开事实留在 support_history，不伪造负责人或课程。
ALTER TABLE support_ticket DROP CONSTRAINT ck_support_status;
UPDATE support_ticket SET status = 'SUBMITTED' WHERE status = 'OPEN';
ALTER TABLE support_ticket
    ADD CONSTRAINT ck_support_status CHECK (status IN
        ('SUBMITTED', 'IN_PROGRESS', 'WAITING_USER', 'ANSWERED', 'CLOSED'));
ALTER TABLE support_ticket
    ADD CONSTRAINT ck_support_ticket_category CHECK (category IN ('OPERATION', 'COURSE_REQUIREMENT'));

ALTER TABLE support_reply
    ADD COLUMN attachment_file_ids jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE help_article
    ADD COLUMN roles_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN scope varchar(20) NOT NULL DEFAULT 'GENERAL',
    ADD COLUMN prerequisites_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN steps_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN outcomes_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN failure_action varchar(500) NOT NULL DEFAULT '',
    ADD COLUMN entry_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN content_version int NOT NULL DEFAULT 1;

UPDATE help_article
SET scope = audience,
    steps_json = CASE
        WHEN body IS NULL OR btrim(body) = '' THEN '[]'::jsonb
        ELSE jsonb_build_array(left(body, 500))
    END
WHERE steps_json = '[]'::jsonb;

COMMENT ON COLUMN help_article.entry_json IS
    '历史条目没有页面入口。空对象表示未记录，响应投影为 type=null,id=null，不编造白名单页面。';

ALTER TABLE help_feedback
    ADD COLUMN content_version int NOT NULL DEFAULT 1,
    ADD COLUMN reason_code varchar(40);

ALTER TABLE help_feedback DROP CONSTRAINT help_feedback_pkey;
ALTER TABLE help_feedback
    ADD CONSTRAINT pk_help_feedback PRIMARY KEY (article_id, user_id, content_version);

ALTER TABLE content_report
    ADD COLUMN revision int NOT NULL DEFAULT 1,
    ADD COLUMN request_digest char(64);

ALTER TABLE content_report DROP CONSTRAINT ck_report_content_type;
ALTER TABLE content_report DROP CONSTRAINT ck_report_status;
UPDATE content_report SET content_type = CASE content_type
    WHEN 'TOPIC' THEN 'BOARD_TOPIC'
    WHEN 'REPLY' THEN 'BOARD_REPLY'
    ELSE content_type
END;
UPDATE content_report SET status = CASE status
    WHEN 'OPEN' THEN 'SUBMITTED'
    WHEN 'ACCEPTED' THEN 'IN_PROGRESS'
    WHEN 'RECORDED' THEN 'HANDLED'
    ELSE status
END;
ALTER TABLE content_report
    ADD CONSTRAINT ck_report_content_type CHECK (content_type IN
        ('BOARD_TOPIC', 'BOARD_REPLY', 'CHAT_MESSAGE'));
ALTER TABLE content_report
    ADD CONSTRAINT ck_report_status CHECK (status IN
        ('SUBMITTED', 'IN_PROGRESS', 'HANDLED', 'CLOSED'));
ALTER TABLE content_report
    ADD CONSTRAINT ck_report_revision CHECK (revision BETWEEN 1 AND 999999999);

-- WAIT_TEACHER 是双方过半后等待教学事实核对，对应 WAIT_FACT。
-- RETURNED 是核对已被退回，不改成 WAIT_FACT。
-- EXECUTED/FAILED 分别对应已执行完成和未通过。
ALTER TABLE square_merge DROP CONSTRAINT ck_square_merge_status;
UPDATE square_merge SET status = 'WAIT_FACT' WHERE status = 'WAIT_TEACHER';
UPDATE square_merge SET status = 'COMPLETED' WHERE status = 'EXECUTED';
UPDATE square_merge SET status = 'REJECTED' WHERE status = 'FAILED';
ALTER TABLE square_merge
    ADD CONSTRAINT ck_square_merge_status CHECK (status IN
        ('VOTING', 'WAIT_FACT', 'READY', 'COMPLETED', 'REJECTED', 'CANCELLED',
         'QUALIFICATION_REVIEW', 'RETURNED'));
