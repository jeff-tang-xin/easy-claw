-- ============================================================================
-- V24__blackboard_unify_workspace.sql
-- 黑板统一（对齐 V23 知识库统一模式）：spoke 工作区黑板并入平台黑板 blackboard_entries，
-- Agent 与人类共享同一块板（按 project 归属，跨 source 读写），为团队协作做准备。
--   1) blackboard_entries 加列：
--      source(platform|workspace) / source_workspace_id / entry_type / book_key。
--      book_key = spoke 分本语义（平台条目固定 'main'）；归档统一为 status 标签，
--      不再用 <key>.archived-<ts> 改写 book_key 的旧方案。
--   2) 存量迁移：workspace_blackboard 中 project_id 非空的行迁入 blackboard_entries
--      （author_user_id=0 占位；原归档本 <key>.archived-<ts> → status='archived' +
--      book_key 去后缀；created_at/updated_at 保留原值）。同板无唯一键冲突问题
--      （黑板是追加型，不做合并去重）。旧表保留不 drop（历史 null 行不迁移）。
-- 沿用既有决策：全库无外键，一致性由应用层保证。
-- ============================================================================

ALTER TABLE blackboard_entries
  ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'platform';

ALTER TABLE blackboard_entries
  ADD COLUMN source_workspace_id VARCHAR(64);

ALTER TABLE blackboard_entries
  ADD COLUMN entry_type VARCHAR(32);

ALTER TABLE blackboard_entries
  ADD COLUMN book_key VARCHAR(192);

-- 存量平台条目归入 main 本（与 spoke append 的默认本一致）
UPDATE blackboard_entries SET book_key = 'main' WHERE book_key IS NULL;

-- 存量 spoke 黑板迁入（project_id 非空才归属；归档本转 status 标签 + book_key 去后缀）
INSERT INTO blackboard_entries (created_at, updated_at, project_id, content,
                                author_user_id, status, source, source_workspace_id, entry_type, book_key)
SELECT wb.created_at,
       wb.created_at,
       wb.project_id,
       wb.content,
       0,
       CASE WHEN wb.book_key LIKE '%.archived-%' THEN 'archived' ELSE 'active' END,
       'workspace',
       wb.workspace_id,
       wb.entry_type,
       CASE
         WHEN wb.book_key LIKE '%.archived-%'
           THEN SUBSTRING(wb.book_key, 1, POSITION('.archived-' IN wb.book_key) - 1)
         ELSE wb.book_key
       END
FROM workspace_blackboard wb
WHERE wb.project_id IS NOT NULL;
