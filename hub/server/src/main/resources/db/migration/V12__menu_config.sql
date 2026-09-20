-- ============================================================================
-- V12__menu_config.sql
-- spoke 集成底座（阶段 B）：公共菜单配置（menu_items）。
--   menu_items = 下发给 spoke 的公共菜单项，绑定到工作区（workspace_id，因 1:1 等价于按 project）；
--     **与组织无关**（菜单不挂 org，spoke 不按自身单独控制），同一工作区的菜单对所有 spoke 通用。
--     parent_id 支持层级（NULL=顶层），menu_key 为 spoke 侧稳定标识，visible_roles 逗号分隔角色
--     （空=全部角色可见），required_perm 为可见性权限码（空=不额外要求）。
--     注：menu_key 而非 key——key 是 PostgreSQL 保留字。
-- 沿用既有决策：全库无外键；唯一约束与普通查询索引保留。
-- 公共字段 id / created_at / updated_at 对应 common.BaseEntity。
-- ============================================================================

CREATE TABLE menu_items (
  id             BIGSERIAL PRIMARY KEY,
  workspace_id   BIGINT       NOT NULL,                          -- 无外键，一致性由应用层保证
  parent_id      BIGINT,                                         -- NULL=顶层菜单
  menu_key       VARCHAR(64)  NOT NULL,                          -- spoke 侧稳定标识（key 为 PG 保留字，故加前缀）
  label          VARCHAR(128) NOT NULL,
  icon           VARCHAR(64),
  path           VARCHAR(255),
  required_perm  VARCHAR(64)  NOT NULL DEFAULT '',               -- 可见性权限码，空=不要求
  visible_roles  VARCHAR(255) NOT NULL DEFAULT '',               -- 逗号分隔角色，空=全部可见
  sort_order     INT          NOT NULL DEFAULT 0,
  enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_menu_workspace_key UNIQUE (workspace_id, menu_key)    -- 同工作区内 menu_key 唯一
);
CREATE INDEX idx_menu_items_workspace ON menu_items(workspace_id, parent_id, sort_order);
