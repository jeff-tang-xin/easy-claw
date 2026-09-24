-- ============================================================================
-- V26__provider_grants.sql
-- Provider 授权模型：provider 可按用户授权（appkey 创建者维度），授权可选
-- 每日调用次数上限（NULL = 不限流，类似积分按次计）与有效期（NULL = 永久）。
--   1) provider_grants：授权清单。(provider_id, user_id) 唯一。
--      启用语义：provider 存在任意授权行 = 启用授权模式（仅清单内用户可用）；
--      未配置任何授权行 = 不启用（保持现状开放），存量 appkey 不受影响。
--   2) provider_grant_usage：每日调用计数（限流口径 = 请求次数，成功失败都算）。
--      (grant_id, usage_date) 唯一，网关路由时原子自增后与 daily_limit 比较。
-- 沿用既有决策：全库无外键，一致性由应用层保证。
-- ============================================================================

CREATE TABLE provider_grants (
  id          BIGSERIAL PRIMARY KEY,
  provider_id BIGINT      NOT NULL,                            -- 无外键，一致性由应用层保证
  user_id     BIGINT      NOT NULL,                            -- 被授权用户（appkey 创建者维度）
  daily_limit INT,                                             -- 每日调用次数上限；NULL = 不限流
  expires_at  TIMESTAMPTZ,                                     -- 授权有效期至；NULL = 永久
  created_by  BIGINT,                                          -- 授权操作人
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_provider_grants UNIQUE (provider_id, user_id)
);
CREATE INDEX idx_provider_grants_user ON provider_grants(user_id);

CREATE TABLE provider_grant_usage (
  id            BIGSERIAL PRIMARY KEY,
  grant_id      BIGINT      NOT NULL,                          -- 无外键，一致性由应用层保证
  usage_date    DATE        NOT NULL,                          -- 按自然日计数
  request_count INT         NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_provider_grant_usage UNIQUE (grant_id, usage_date)
);
