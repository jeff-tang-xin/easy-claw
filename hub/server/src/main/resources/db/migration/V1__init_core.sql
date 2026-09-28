-- ============================================================================
-- V1__init_core.sql  (A0)
-- 主子架构 hub 核心库结构：users / organizations / memberships / refresh_tokens / projects / audit_logs
-- 规约见 docs/hub-spoke-p0-breakdown.md §3.1：版本化迁移一旦应用到任何环境绝不修改，要改就新增更高版本。
--   注：本文件尚未在任何环境应用（A0 未上线、本地无 PG），故按最终结构定稿；一旦应用则冻结。
-- PostgreSQL：主键 BIGSERIAL、时间 TIMESTAMPTZ；updated_at 无 MySQL 的 ON UPDATE，由应用层 @PreUpdate 维护。
-- 公共字段统一：所有业务表均有 id / created_at / updated_at（对应 common.BaseEntity）；
--   audit_logs 为追加型、不可变，只有 id / created_at（无 updated_at）。
-- projects 是后续一切内容的归类锚点：docs(A2)/知识库(A3)/blackboard(A4) 都以 project_id 挂载。
-- ============================================================================

CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  email         VARCHAR(128) UNIQUE,
  password_hash VARCHAR(100) NOT NULL,           -- BCrypt
  display_name  VARCHAR(64),
  status        VARCHAR(20)  NOT NULL DEFAULT 'active',  -- active|disabled
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()      -- 由应用层 @PreUpdate 维护
);

CREATE TABLE organizations (
  id            BIGSERIAL PRIMARY KEY,
  name          VARCHAR(128) NOT NULL,
  slug          VARCHAR(64)  NOT NULL UNIQUE,
  owner_user_id BIGINT       NOT NULL REFERENCES users(id),
  plan          VARCHAR(20)  NOT NULL DEFAULT 'free',
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
  id          BIGSERIAL PRIMARY KEY,
  org_id      BIGINT NOT NULL REFERENCES organizations(id),
  user_id     BIGINT NOT NULL REFERENCES users(id),
  role        VARCHAR(20) NOT NULL DEFAULT 'member',   -- owner|admin|member|guest
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_membership UNIQUE (org_id, user_id)
);

CREATE TABLE refresh_tokens (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(id),
  device      VARCHAR(128),
  token_hash  VARCHAR(128) NOT NULL,                  -- 存 hash，不存明文
  expires_at  TIMESTAMPTZ  NOT NULL,
  revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_rt_user ON refresh_tokens(user_id);

CREATE TABLE projects (
  id            BIGSERIAL PRIMARY KEY,
  org_id        BIGINT       NOT NULL REFERENCES organizations(id),
  slug          VARCHAR(64)  NOT NULL,
  name          VARCHAR(128) NOT NULL,
  description   VARCHAR(500),
  visibility    VARCHAR(20)  NOT NULL DEFAULT 'team',   -- private|team|public
  owner_user_id BIGINT       NOT NULL REFERENCES users(id),
  status        VARCHAR(20)  NOT NULL DEFAULT 'active', -- active|archived
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_project_org_slug UNIQUE (org_id, slug)
);

-- 审计日志：追加型、不可变（无 updated_at）。按 module（模块）+ action（动作）维度区分，
-- 辅以 actor / org / target 定位「谁对什么做了什么、结果如何」。
CREATE TABLE audit_logs (
  id             BIGSERIAL PRIMARY KEY,
  module         VARCHAR(32) NOT NULL,                    -- 模块：auth|org|project|user...
  action         VARCHAR(48) NOT NULL,                    -- 动作：register|login|create_org|add_member...
  actor_user_id  BIGINT,                                  -- 操作者（可空=系统/匿名/注册或登录失败尚无身份）
  actor_username VARCHAR(64),                             -- 冗余便于检索
  org_id         BIGINT,                                  -- 相关组织（平台级事件可空）
  target_type    VARCHAR(32),                             -- 目标类型：user|organization|project...
  target_id      VARCHAR(64),                             -- 目标 id（字符串以兼容各类型主键）
  result         VARCHAR(16) NOT NULL DEFAULT 'success',  -- success|failure
  detail         TEXT,                                    -- 附加上下文（JSON 字符串；A0 用 TEXT，后续可迁 jsonb）
  ip             VARCHAR(64),
  user_agent     VARCHAR(255),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_module_created ON audit_logs(module, created_at);
CREATE INDEX idx_audit_org_created    ON audit_logs(org_id, created_at);
CREATE INDEX idx_audit_actor_created  ON audit_logs(actor_user_id, created_at);
CREATE INDEX idx_audit_target         ON audit_logs(target_type, target_id);
