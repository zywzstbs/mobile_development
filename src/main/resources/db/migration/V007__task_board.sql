-- P05 普通任务、阶段依赖与本人项目颜色。正式约定留到 P06，这里不建半成品份额。

CREATE TABLE task (
    id                 varchar(120) PRIMARY KEY,
    project_id         varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    stage_id           varchar(120) NOT NULL REFERENCES project_stage(id) ON DELETE RESTRICT,
    title              varchar(120) NOT NULL,
    description        varchar(2000) NOT NULL DEFAULT '',
    mode               varchar(20)  NOT NULL,
    owner_id           varchar(120) REFERENCES app_user(id) ON DELETE RESTRICT,
    due_at             timestamptz,
    status             varchar(20)  NOT NULL,
    agreement_version  int,
    revision           int          NOT NULL DEFAULT 1,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    CONSTRAINT ck_task_mode CHECK (mode IN ('ORDINARY', 'ASSESSED')),
    CONSTRAINT ck_task_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'REVIEW_PENDING', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_task_revision CHECK (revision BETWEEN 1 AND 999999999),
    CONSTRAINT ck_task_title_nonblank CHECK (length(btrim(title)) > 0)
);

CREATE INDEX ix_task_project ON task (project_id, created_at, id);
CREATE INDEX ix_task_stage ON task (stage_id);

CREATE TABLE criterion (
    id                varchar(120) PRIMARY KEY,
    task_id           varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    text              varchar(500) NOT NULL,
    position          int          NOT NULL,
    standard_version  int          NOT NULL DEFAULT 1,
    CONSTRAINT uq_criterion_position UNIQUE (task_id, position),
    CONSTRAINT ck_criterion_text_nonblank CHECK (length(btrim(text)) > 0)
);

CREATE TABLE task_dependency (
    task_id            varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    depends_on_task_id varchar(120) NOT NULL REFERENCES task(id) ON DELETE RESTRICT,
    gate               varchar(20)  NOT NULL,
    CONSTRAINT pk_task_dependency PRIMARY KEY (task_id, depends_on_task_id),
    CONSTRAINT ck_task_dependency_not_self CHECK (task_id <> depends_on_task_id),
    CONSTRAINT ck_task_dependency_gate CHECK (gate IN ('BEFORE_START', 'BEFORE_SUBMIT'))
);

CREATE INDEX ix_task_dependency_depends ON task_dependency (depends_on_task_id);

CREATE TABLE user_project_color (
    project_id  varchar(120) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    user_id     varchar(120) NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    color_key   varchar(7)   NOT NULL,
    revision    int          NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT pk_user_project_color PRIMARY KEY (project_id, user_id),
    CONSTRAINT ck_user_project_color_revision CHECK (revision BETWEEN 0 AND 999999999)
);
