-- ============================================================================
-- V15__org_feature_flag_and_menu.sql
-- 平台级目录模型（菜单/功能开关/工具）：
--   1) menu_items 重建为平台级目录：去掉 org_id 维度，menu_key 全局唯一；
--      workspace_id 列随表重建一并消失（V15 未发布、无历史数据，直接 DROP 重建）。
--   2) feature_flags 重建为平台级目录：org_id 与 built_in 列删除（全部条目皆平台内置）；
--      种子含既有 8 项 + 新增 allow_attachments（允许附件和图片）。
--   3) 新增 platform_tools：平台工具目录（对齐 web/api ToolRegistry，由 hub Java Seeder 播种）。
--   4) 新增组织开关三表 org_menu_settings / org_flag_settings / org_tool_settings：
--      惰性行（无行 = 默认可见/启用），生效语义 = 平台 enabled AND 组织 visible/enabled。
-- 沿用既有决策：全库无外键；唯一约束与普通查询索引保留。
-- 公共字段 id / created_at / updated_at 对应 common.BaseEntity。
-- ============================================================================

-- 1. menu_items：重建为平台级目录
DROP TABLE IF EXISTS menu_items;
CREATE TABLE menu_items (
  id            BIGSERIAL PRIMARY KEY,
  menu_key      VARCHAR(64)  NOT NULL,                           -- spoke 侧稳定标识，全局唯一、创建后不可改
  label         VARCHAR(128) NOT NULL,
  icon          VARCHAR(64)  NOT NULL DEFAULT '',
  path          VARCHAR(255) NOT NULL DEFAULT '',
  parent_id     BIGINT,                                          -- 无外键，一致性由应用层保证
  required_perm VARCHAR(64)  NOT NULL DEFAULT '',
  visible_roles VARCHAR(255) NOT NULL DEFAULT '',
  sort_order    INT          NOT NULL DEFAULT 0,
  enabled       BOOLEAN      NOT NULL DEFAULT TRUE,              -- 平台总开关
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_menu_key UNIQUE (menu_key)
);
CREATE INDEX idx_menu_items_parent ON menu_items(parent_id, sort_order);

-- 2. feature_flags：重建为平台级目录
DROP TABLE IF EXISTS feature_flags;
CREATE TABLE feature_flags (
  id          BIGSERIAL PRIMARY KEY,
  flag_key    VARCHAR(64)  NOT NULL,                             -- spoke 侧稳定标识，全局唯一、创建后不可改
  label       VARCHAR(128) NOT NULL,
  description VARCHAR(512) NOT NULL DEFAULT '',
  sort_order  INT          NOT NULL DEFAULT 0,
  enabled     BOOLEAN      NOT NULL DEFAULT TRUE,                -- 平台总开关
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_feature_flag_key UNIQUE (flag_key)
);

-- 3. feature_flags 种子：既有 8 项内置 + 新增 allow_attachments（组织只决定是否启用）
INSERT INTO feature_flags (flag_key, label, description, sort_order) VALUES
  ('knowledge', '知识库', '组织级项目知识库（条目检索与复用）', 10),
  ('blackboard', '黑板报', '团队共享黑板报（协作记录与公告）', 20),
  ('chat', '对话', 'AI 对话能力', 30),
  ('mcp', 'MCP', 'Model Context Protocol 工具接入', 40),
  ('web-search', '联网搜索', '对话中的联网检索能力', 50),
  ('team', '团队', '多成员团队协作', 60),
  ('skill', '技能', '技能（Skill）体系', 70),
  ('scenario', '场景', '场景化能力', 80),
  ('allow_attachments', '允许附件和图片', '关闭后该组织 spoke 客户端禁止上传附件与图片', 90);

-- 4. platform_tools：平台工具目录（种子由 hub Java Seeder 按 tool_key upsert，不在 SQL 里）
CREATE TABLE platform_tools (
  id           BIGSERIAL PRIMARY KEY,
  tool_key     VARCHAR(64)  NOT NULL,                           -- 对齐 web/api ToolRegistry 工具名，全局唯一
  display_name VARCHAR(128) NOT NULL,
  description  VARCHAR(512) NOT NULL DEFAULT '',
  tool_group   VARCHAR(32)  NOT NULL DEFAULT '',
  sort_order   INT          NOT NULL DEFAULT 0,
  enabled      BOOLEAN      NOT NULL DEFAULT TRUE,              -- 平台总开关
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_platform_tool_key UNIQUE (tool_key)
);
CREATE INDEX idx_platform_tools_group ON platform_tools(tool_group, sort_order);

-- 5. 组织开关三表：惰性行（无行 = 默认可见/启用）
CREATE TABLE org_menu_settings (
  id         BIGSERIAL PRIMARY KEY,
  org_id     BIGINT      NOT NULL,                               -- 无外键，一致性由应用层保证
  menu_id    BIGINT      NOT NULL,
  visible    BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_org_menu_setting UNIQUE (org_id, menu_id)
);
CREATE INDEX idx_org_menu_settings_org ON org_menu_settings(org_id);

CREATE TABLE org_flag_settings (
  id         BIGSERIAL PRIMARY KEY,
  org_id     BIGINT      NOT NULL,
  flag_id    BIGINT      NOT NULL,
  enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_org_flag_setting UNIQUE (org_id, flag_id)
);
CREATE INDEX idx_org_flag_settings_org ON org_flag_settings(org_id);

CREATE TABLE org_tool_settings (
  id         BIGSERIAL PRIMARY KEY,
  org_id     BIGINT      NOT NULL,
  tool_id    BIGINT      NOT NULL,
  enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_org_tool_setting UNIQUE (org_id, tool_id)
);
CREATE INDEX idx_org_tool_settings_org ON org_tool_settings(org_id);
