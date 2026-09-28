-- ============================================================================
-- V6__gateway_logs.sql
-- A1 LLM 网关全量详单（设计 §4.2）：记录每一次经网关转发的请求/返回。
--   归属维度：app_key_id + org_id + user_id（appkey.created_by 快照，调用者即 appkey）。
--   正文：request_body / response_body 完整留档（SSE 为聚合后内容），应用层截断 64KB 并标注。
--   计量：prompt_tokens / completion_tokens / latency_ms，供 usage 聚合。
--   status：success | error（含上游 4xx/5xx 与网关自身拒绝，路由失败也留档便于排障）。
-- 沿用用户决策：无外键，一致性由应用层保证；provider 被删后 slug 快照仍可查。
-- ============================================================================

CREATE TABLE gateway_logs (
    id                BIGSERIAL PRIMARY KEY,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    app_key_id        BIGINT      NOT NULL,
    org_id            BIGINT      NOT NULL,
    user_id           BIGINT,
    key_prefix        VARCHAR(32),
    provider_id       BIGINT,
    provider_slug     VARCHAR(64),
    api_type          VARCHAR(20),
    model             VARCHAR(128),
    stream            BOOLEAN     NOT NULL DEFAULT FALSE,
    status            VARCHAR(20) NOT NULL,
    http_status       INT,
    latency_ms        BIGINT,
    prompt_tokens     INT,
    completion_tokens INT,
    request_body      TEXT,
    response_body     TEXT,
    error_message     TEXT,
    client_ip         VARCHAR(64)
);

CREATE INDEX idx_gateway_logs_org ON gateway_logs (org_id, id DESC);
CREATE INDEX idx_gateway_logs_appkey ON gateway_logs (app_key_id, id DESC);
