-- ============================================================================
-- V14__blackboard.sql  (A4)
-- 项目共享黑板：团队成员的 agent 实时共享同一块记录本，按 project_id 归类。
--   blackboard_entries = 追加型条目（每条独立，append-only 语义），归档是条目的一个状态标签
--   （active|archived，见设计文档 §8.5），不另建归档表。
-- 沿用 V4 用户决策：无外键，一致性由应用层保证；project/user 被删后 id 快照仍可查。
-- ============================================================================

CREATE TABLE blackboard_entries (
    id             BIGSERIAL PRIMARY KEY,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    project_id     BIGINT       NOT NULL,
    content        TEXT         NOT NULL,
    author_user_id BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'active'  -- active|archived
);

CREATE INDEX idx_blackboard_project_status
    ON blackboard_entries (project_id, status, id DESC);