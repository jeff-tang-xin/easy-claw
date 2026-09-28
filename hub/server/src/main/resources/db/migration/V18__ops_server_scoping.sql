-- ============================================================================
-- V18__ops_server_scoping.sql
-- 运维服务器归属与授权（需求：服务器绑定组织+项目；用户授权带时效；solo 工作区绑定 hub 项目）。
--   1) ops_servers 加归属：org_id / project_id（NOT NULL DEFAULT 0 = 未归属遗留行，
--      不满足下发条件，spoke 永远看不到，须由 platformAdmin 在管理面补全归属）。
--      对齐 V11 workspaces 的口径：project 必填、org 冗余便于按组织列举与鉴权。
--   2) ops_server_grants：用户 × 服务器授权，带时效窗口（valid_from/valid_until）。
--      一人一服务器一条（uk），续期 = 更新 valid_until；过期行保留作历史，查询按 now() 过滤。
--      授权管理 platformAdmin 专属（与目录 CRUD 同 guard）。
--   3) workspaces.spoke_workspace_id：spoke 本地（solo）工作区绑定 hub 工作区。
--      workspaces 本就与 project 1:1（uk_workspace_project），绑定即建立
--      spoke 工作区 ↔ hub 项目的映射；UNIQUE(org_id, spoke_workspace_id)：
--      同一组织内一个 spoke 工作区至多绑一个项目（跨组织互不影响）。
-- 沿用既有决策：全库无外键，一致性由应用层保证。
-- ============================================================================

-- 1) ops_servers 归属
ALTER TABLE ops_servers
  ADD COLUMN org_id     BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN project_id BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_ops_servers_org ON ops_servers(org_id);

-- 2) 用户 × 服务器授权（时效）
CREATE TABLE ops_server_grants (
  id          BIGSERIAL PRIMARY KEY,
  server_id   BIGINT      NOT NULL,                             -- ops_servers.id，无外键
  user_id     BIGINT      NOT NULL,                             -- hub users.id，无外键
  org_id      BIGINT      NOT NULL,                             -- 冗余自服务器归属，便于按组织鉴权
  granted_by  BIGINT      NOT NULL,                             -- 操作人（platformAdmin）
  valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
  valid_until TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_ops_grant_server_user UNIQUE (server_id, user_id)
);
CREATE INDEX idx_ops_grants_user ON ops_server_grants(user_id);
CREATE INDEX idx_ops_grants_org ON ops_server_grants(org_id);

-- 3) spoke 工作区绑定（solo 工作区 ↔ hub 项目）
ALTER TABLE workspaces ADD COLUMN spoke_workspace_id VARCHAR(128);
CREATE UNIQUE INDEX uk_workspace_spoke ON workspaces(org_id, spoke_workspace_id)
  WHERE spoke_workspace_id IS NOT NULL;
