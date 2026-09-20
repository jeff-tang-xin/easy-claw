-- ============================================================================
-- V8__knowledge.sql  (A3-S1)
-- 项目知识库：项目级知识条目（topic/summary/content）+ 追加型版本历史 + 乐观锁。
--   knowledge_items        = 条目当前态（topic 项目内唯一、version 乐观锁、软删 status=deleted）。
--   knowledge_item_events  = 追加型版本历史（每次 create/update 一行快照，不可变，不继承 BaseEntity 口径）。
-- 与 V7 docs/doc_events 同构，仅多向量相关列（A3 语义检索预留；S1 不读写 embedding 列本身）。
-- 沿用 V4 用户决策：无外键，一致性由应用层保证；project/user 被删后 id 快照仍可查历史。
--
-- pgvector：A3 是全库第一个用到 vector 的迁移，须先启用扩展（装在 DB 级，默认 public schema）。
-- ⚠️ vector(N) 列与 <=> 检索仅存在于 PostgreSQL；SQLite 集成测试不跑本脚本、实体也不映射 embedding
--    列（生产 ddl-auto=none，表结构以本脚本为准），向量近邻须在真 PG 专项验证（A3-S3）。
-- 初版不建 ANN 索引：团队项目语料量级下精确近邻（顺序扫描 + <=>）足够，上万条后再加 HNSW。
-- ============================================================================

-- 全库首个 vector 使用点：幂等启用扩展（已装则跳过）。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_items (
    id               BIGSERIAL PRIMARY KEY,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    project_id       BIGINT       NOT NULL,
    topic            VARCHAR(200) NOT NULL,          -- 项目内唯一（应用层 + 部分唯一索引保证）
    summary          VARCHAR(500) NOT NULL DEFAULT '',
    content          TEXT         NOT NULL DEFAULT '',
    version          BIGINT       NOT NULL DEFAULT 1,
    owner_user_id    BIGINT       NOT NULL,
    updated_by       BIGINT       NOT NULL,          -- 最近一次编辑者（首版=owner）
    status           VARCHAR(20)  NOT NULL DEFAULT 'active',  -- active|deleted（软删，保留历史）
    -- 向量相关（S1 只维护状态列；embedding 列由 A3-S3 经原生 SQL 读写，JPA 不映射）
    embedding        vector(1536),                   -- 维度随 embedding 模型固定（text-embedding-3-small）
    embedding_model  VARCHAR(120),                   -- 生成该向量所用模型，换模型据此识别陈旧向量
    embedding_status VARCHAR(20)  NOT NULL DEFAULT 'pending'  -- pending|ready|failed
);

-- 项目内 topic 唯一（仅未删除条目）：部分唯一索引，允许软删后重建同名条目。
CREATE UNIQUE INDEX uk_knowledge_project_topic
    ON knowledge_items (project_id, topic) WHERE status = 'active';
CREATE INDEX idx_knowledge_project
    ON knowledge_items (project_id, status, updated_at DESC);

CREATE TABLE knowledge_item_events (
    id            BIGSERIAL PRIMARY KEY,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    item_id       BIGINT       NOT NULL,
    project_id    BIGINT       NOT NULL,
    version       BIGINT       NOT NULL,
    topic         VARCHAR(200) NOT NULL,
    summary       VARCHAR(500) NOT NULL DEFAULT '',
    content       TEXT         NOT NULL DEFAULT '',
    actor_user_id BIGINT
);

CREATE INDEX idx_knowledge_events_item_version
    ON knowledge_item_events (item_id, version);
