-- 交接续约说明只记在当次约定上。验收行只插入、不更新，已有行的并发版本就是 1。

ALTER TABLE agreement
    ADD COLUMN renewal_reason varchar(500);

ALTER TABLE acceptance
    ADD COLUMN revision int NOT NULL DEFAULT 1;

ALTER TABLE acceptance
    ADD CONSTRAINT ck_acceptance_revision CHECK (revision BETWEEN 1 AND 999999999);
