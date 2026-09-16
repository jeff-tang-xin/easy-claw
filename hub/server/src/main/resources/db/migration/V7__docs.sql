-- ============================================================================
-- V7__docs.sql
-- A2 内容服务底座：项目内协作文档（需求 requirement / 任务 task）。
--   docs        = 文档当前态（标题/正文/类型/归属/版本号），乐观锁并发：UPDATE ... WHERE version=?。
--   doc_events  = 追加型版本历史（每次 create/update 一行快照，不可变，不继承 BaseEntity 口径）。
--   task 经 parent_doc_id 挂到同项目需求下（不强制层级，应用层校验同项目）。
-- 沿用 V4 用户决策：无外键，一致性由应用层保证；project/user 被删后 id 快照仍可查历史。
-- ============================================================================

CREATE TABLE docs (
    id               BIGSERIAL PRIMARY KEY,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    project_id       BIGINT       NOT NULL,
    parent_doc_id    BIGINT,
    title            VARCHAR(200) NOT NULL,
    content          TEXT         NOT NULL DEFAULT '',
    doc_type         VARCHAR(20)  NOT NULL DEFAULT 'requirement',
    status           VARCHAR(20)  NOT NULL DEFAULT 'active',
    owner_user_id    BIGINT       NOT NULL,
    assignee_user_id BIGINT,
    version          BIGINT       NOT NULL DEFAULT 1
);

CREATE INDEX idx_docs_project ON docs (project_id, doc_type, id);
CREATE INDEX idx_docs_parent ON docs (parent_doc_id);
CREATE INDEX idx_docs_assignee ON docs (assignee_user_id);

CREATE TABLE doc_events (
    id             BIGSERIAL PRIMARY KEY,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    doc_id         BIGINT       NOT NULL,
    project_id     BIGINT       NOT NULL,
    version        BIGINT       NOT NULL,
    title          VARCHAR(200) NOT NULL,
    content        TEXT         NOT NULL DEFAULT '',
    actor_user_id  BIGINT
);

CREATE INDEX idx_doc_events_doc_version ON doc_events (doc_id, version);
