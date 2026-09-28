-- ============================================================================
-- V28__ops_server_category_tags.sql
-- 运维服务器分类标签字典（ops_server_categories）：platformAdmin CRUD，
-- 服务器目录（ops_servers.category，V21 自由文本）收口为受管标签：
--   1) 新表：name 全局唯一 + sort_order；存量 ops_servers 非空 category 自动入册（幂等）。
--   2) 服务端裁决：服务器创建/更新的 category 必须是受管标签；标签重命名同步引用行；
--      标签被服务器引用时禁止删除（409）。
-- ============================================================================

CREATE TABLE ops_server_categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ops_category_name UNIQUE (name)
);

-- 存量自由文本标签自动入册（幂等；空串 = 未标注，不入册）
INSERT INTO ops_server_categories (name, sort_order, created_at, updated_at)
SELECT DISTINCT category, 0, now(), now()
FROM ops_servers
WHERE category <> ''
ON CONFLICT (name) DO NOTHING;
