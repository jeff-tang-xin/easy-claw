-- ============================================================================
-- V5__provider_org_scope_api_type.sql
-- Provider 组织化 + 主流厂商协议类型：
--   org_id   NULL = 平台共享池（platformAdmin 维护）；非空 = 组织自有 provider（owner/admin 维护）。
--   api_type openai = OpenAI 兼容协议（OpenAI/DeepSeek/DashScope/Moonshot/智谱 等）；
--            anthropic = Anthropic 原生协议（/v1/messages，x-api-key 头）——A1 网关转发按此分流。
-- slug 唯一性从「全局唯一」放宽为「作用域内唯一」：平台池内唯一 + 每个组织内唯一
--   （PG 唯一索引把 NULL 视为互不相等，用 COALESCE(org_id,0) 归一）。
-- 沿用用户决策：无外键，一致性由应用层保证。
-- ============================================================================

ALTER TABLE llm_providers ADD COLUMN org_id BIGINT;
ALTER TABLE llm_providers ADD COLUMN api_type VARCHAR(20) NOT NULL DEFAULT 'openai';

ALTER TABLE llm_providers DROP CONSTRAINT IF EXISTS llm_providers_slug_key;
CREATE UNIQUE INDEX uk_llm_providers_scope_slug ON llm_providers (COALESCE(org_id, 0), slug);
CREATE INDEX idx_llm_providers_org ON llm_providers(org_id);
