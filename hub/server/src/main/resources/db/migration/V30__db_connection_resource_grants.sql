-- ============================================================================
-- V30__db_connection_resource_grants.sql
-- 数据库连接目录 + 通用资源授权 + 数据库查询审计：
--   1) db_connections：数据库连接目录（hub 统一维护，platformAdmin CRUD，spoke 只读消费），
--      照 ops_servers 形态：server_key 全局唯一创建后不可改；db_type 限四类
--      （mysql/postgresql/sqlserver/oracle）；database_name 为默认库（Oracle 存 service_name）；
--      readonly_hint 提示「必须只读账号」（物理防线，随目录下发注入提示词）；
--      password_enc AES-256-GCM（同 ops_servers V19 机制），仅下发 spoke 直连，平台永不回显。
--   2) resource_grants：通用资源授权表（资源下放能力沉淀，db-connection 是第一个类型）。
--      与 ops_server_grants（V18，ops 专用）并存：ops 存量链路不动，后续迁移后退役。
--      (resource_type, resource_id, user_id) 唯一；过期行保留作历史，下发查询按 now 过滤。
--   3) db_query_logs：数据库查询审计（照 ops_command_logs V25 形态）：追加型、不可变，
--      spoke 执行 AI 查询后批量上报；sql_text 为上报时快照。
--   4) menu_items 种子：数据库工作区入口（path=/db，工作区组内 ops 之后）。
-- 沿用既有决策：全库无外键；公共字段 id/created_at/updated_at 对应 BaseEntity。
-- ============================================================================

-- 1. db_connections：数据库连接目录
CREATE TABLE db_connections (
  id            BIGSERIAL PRIMARY KEY,
  server_key    VARCHAR(64)  NOT NULL,                           -- spoke 侧稳定标识，全局唯一、创建后不可改
  name          VARCHAR(128) NOT NULL,
  db_type       VARCHAR(32)  NOT NULL,                           -- mysql / postgresql / sqlserver / oracle
  host          VARCHAR(255) NOT NULL,
  port          INT          NOT NULL,
  database_name VARCHAR(128) NOT NULL,                           -- 默认库；Oracle 存 service_name
  username      VARCHAR(64)  NOT NULL,
  description   VARCHAR(255) NOT NULL DEFAULT '',
  readonly_hint BOOLEAN      NOT NULL DEFAULT TRUE,              -- 录入时提示必须只读账号；随目录下发注入提示词
  sort_order    INT          NOT NULL DEFAULT 0,
  enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
  org_id        BIGINT       NOT NULL DEFAULT 0,                 -- 0 = 未归属遗留行，永不下发
  project_id    BIGINT       NOT NULL DEFAULT 0,                 -- 0 = 不限定项目
  password_enc  VARCHAR(1024) NULL,                              -- AES-256-GCM；NULL = 未设置
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_db_connection_key UNIQUE (server_key)
);

-- 2. resource_grants：通用资源授权（resource_type + resource_id 定位目录行）
CREATE TABLE resource_grants (
  id           BIGSERIAL PRIMARY KEY,
  resource_type VARCHAR(32) NOT NULL,                            -- db-connection / ops-server / ...
  resource_id  BIGINT      NOT NULL,                            -- 各类型目录表 id
  user_id      BIGINT      NOT NULL,
  org_id       BIGINT      NOT NULL,                            -- 冗余自资源归属组织（一致性由应用层保证）
  granted_by   BIGINT      NOT NULL,
  valid_from   TIMESTAMPTZ NOT NULL,
  valid_until  TIMESTAMPTZ NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_resource_grant UNIQUE (resource_type, resource_id, user_id)
);
CREATE INDEX idx_resource_grants_user ON resource_grants (resource_type, user_id, valid_until);

-- 3. db_query_logs：数据库查询审计（追加型、不可变）
CREATE TABLE db_query_logs (
  id           BIGSERIAL PRIMARY KEY,
  org_id       BIGINT       NOT NULL DEFAULT 0,
  server_key   VARCHAR(64)  NOT NULL,
  server_name  VARCHAR(128) NOT NULL DEFAULT '',
  db_type      VARCHAR(32)  NOT NULL DEFAULT '',
  host         VARCHAR(255) NOT NULL DEFAULT '',
  database_name VARCHAR(128) NOT NULL DEFAULT '',
  sql_text     TEXT         NOT NULL,
  source       VARCHAR(16)  NOT NULL,                            -- ai | user
  operator     VARCHAR(128) NOT NULL DEFAULT '',
  executed_at  TIMESTAMPTZ  NOT NULL,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_db_query_server ON db_query_logs (org_id, server_key, executed_at DESC);

-- 4. 菜单种子：数据库工作区入口（工作区组内，运维之后）
INSERT INTO menu_items (menu_key, label, icon, path, parent_id, sort_order) VALUES
  ('db', '数据库', '🗄️', '/db', (SELECT id FROM menu_items WHERE menu_key = 'group-workspace'), 25);
