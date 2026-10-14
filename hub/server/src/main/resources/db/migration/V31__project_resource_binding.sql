-- ============================================================================
-- V31__project_resource_binding.sql
-- 项目-资源绑定 N:N：db_connections / ops_servers 与 projects 的多对多归属。
--   旧模型「资源上单值 project_id」是 1:N（一个项目多个资源，一个资源只归一个项目），
--   与实际不符：一个微服务项目的库/服务器可能同时服务多个项目（共享的订单库、
--   共用的跳板机）。升级为关联表，绑定操作移到项目目录页（资源添加页不再选项目）。
--   旧列保留不删（回滚安全；代码停用后成死数据，后续版本再清理）。
--   存量迁移：project_id > 0 且项目存在的行搬入关联表。
-- 沿用既有决策：全库无外键；公共字段 id/created_at/updated_at 对应 BaseEntity。
-- ============================================================================

CREATE TABLE project_resources (
  id            BIGSERIAL PRIMARY KEY,
  project_id    BIGINT      NOT NULL,                            -- projects.id
  resource_type VARCHAR(16) NOT NULL,                            -- db | ops
  resource_id   BIGINT      NOT NULL,                            -- db_connections.id / ops_servers.id
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_project_resource UNIQUE (project_id, resource_type, resource_id)
);
CREATE INDEX idx_project_resources_resource ON project_resources (resource_type, resource_id);

-- 存量迁移：单值归属 → 关联表（项目已删除的脏数据不搬，等价于解除绑定）
INSERT INTO project_resources (project_id, resource_type, resource_id)
SELECT project_id, 'db', id FROM db_connections
WHERE project_id > 0 AND project_id IN (SELECT id FROM projects)
ON CONFLICT DO NOTHING;

INSERT INTO project_resources (project_id, resource_type, resource_id)
SELECT project_id, 'ops', id FROM ops_servers
WHERE project_id > 0 AND project_id IN (SELECT id FROM projects)
ON CONFLICT DO NOTHING;
