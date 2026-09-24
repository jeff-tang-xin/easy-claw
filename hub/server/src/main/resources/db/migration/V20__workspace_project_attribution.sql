-- ============================================================================
-- V20__workspace_project_attribution.sql
-- 云端知识库/黑板数据按项目归属（需求：spoke cloud 模式创建 solo 工作区时 projectId 必选，
-- 同步知识库/黑板时携带 projectId，hub 端落库归属）。
--   1) workspace_knowledge 加 project_id：spoke 同步时携带，历史行 null = 未归属
--      （与 V18 workspaces.spoke_workspace_id 绑定冗余，便于直接按项目查询/过滤）。
--   2) workspace_blackboard 加 project_id：同上（追加型，行级归属）。
-- 沿用既有决策：全库无外键，一致性由应用层保证；旧 spoke 不传 projectId 不影响读写。
-- ============================================================================

ALTER TABLE workspace_knowledge
  ADD COLUMN project_id BIGINT;

ALTER TABLE workspace_blackboard
  ADD COLUMN project_id BIGINT;

-- 按项目查询归属数据（可选索引；数据量小，先不加复合索引，避免过度设计）
