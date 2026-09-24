-- ============================================================================
-- V23__knowledge_unify_workspace.sql
-- 知识库统一（方案 B）：spoke 工作区同步知识库并入项目知识库 knowledge_items，
-- hub 平台用户与 spoke AI Agent 按 (project_id, topic) 读写同一份数据。
--   1) knowledge_items 加来源列：source（platform=hub 平台创建 | workspace=spoke 同步）、
--      source_workspace_id（spoke 同步来源工作区标识，平台条目为 null）。
--   2) 存量迁移：workspace_knowledge 中 project_id 非空的行迁入 knowledge_items
--      （owner_user_id/updated_by=0 = Agent 写入占位，version=1，source='workspace'）；
--      同 (project_id, topic) 多行（旧表按 workspace 隔离允许同名）保留 updated_at 最新。
--      project_id 为 null 的未归属行不迁移，留在旧表可追溯。
--   3) workspace_knowledge 表保留不 drop（历史数据可追溯），代码层不再读写。
-- 语义：Agent 写入 = 后写赢（不校验乐观锁，version+1 记历史 actor=null）；
--       hub 平台用户编辑照旧走乐观锁。topic 项目内唯一（部分唯一索引）由 upsert 归一。
-- ============================================================================

ALTER TABLE knowledge_items
  ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'platform';

ALTER TABLE knowledge_items
  ADD COLUMN source_workspace_id VARCHAR(64);

-- 存量迁移：每 (project_id, topic) 取 updated_at 最新一行；部分唯一索引 ON CONFLICT 兜底。
INSERT INTO knowledge_items
  (created_at, updated_at, project_id, topic, summary, content, version,
   owner_user_id, updated_by, status, source, source_workspace_id)
SELECT DISTINCT ON (project_id, topic)
  created_at, updated_at, project_id, topic,
  LEFT(summary, 500), content, 1, 0, 0, 'active', 'workspace', workspace_id
FROM workspace_knowledge
WHERE project_id IS NOT NULL
ORDER BY project_id, topic, updated_at DESC
ON CONFLICT (project_id, topic) WHERE status = 'active' DO NOTHING;
