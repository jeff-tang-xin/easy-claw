-- ============================================================================
-- V11__workspace.sql
-- spoke 集成底座（阶段 B）：工作区（workspace）。
--   workspaces = project 面向 spoke 的扩展面，与 project **1:1 绑定**（uk_workspace_project）；
--     project 仍是纯归类锚点，工作区在其上叠加 spoke 侧配置（菜单等）。
--     org_id 冗余自 project，便于按组织列举与鉴权（一致性由应用层保证，不建外键）。
-- 沿用既有决策：全库无外键；唯一约束与普通查询索引保留。
-- 公共字段 id / created_at / updated_at 对应 common.BaseEntity，updated_at 由 @PreUpdate 维护。
-- ============================================================================

CREATE TABLE workspaces (
  id           BIGSERIAL PRIMARY KEY,
  project_id   BIGINT       NOT NULL,                            -- 无外键，1:1 绑定 project，一致性由应用层保证
  org_id       BIGINT       NOT NULL,                            -- 冗余自 project，便于列举/鉴权
  name         VARCHAR(128) NOT NULL,
  status       VARCHAR(20)  NOT NULL DEFAULT 'active',           -- active|archived
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_workspace_project UNIQUE (project_id)             -- 1:1：一个 project 至多一个工作区
);
CREATE INDEX idx_workspaces_org ON workspaces(org_id);
