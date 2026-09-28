-- ============================================================================
-- V3__llm_provider_appkey.sql
-- 配置模块：LLM provider 平台池 + 组织级 appkey + appkey↔provider 绑定。
--   llm_providers      平台级模型提供方（platformAdmin 维护），真实 api key 只存 AES-GCM 密文；
--   app_keys           组织级访问密钥（owner/admin 颁发），明文仅创建时返回一次，库中只存 SHA-256 hash；
--   app_key_providers  appkey 可用 provider（可选模型粒度，model_name='' 表示该 provider 全部模型）。
-- 用户决策：全库无外键（不写 REFERENCES、不建外键伴随索引），一致性由应用层保证；
--   V1 遗留外键由 V4 移除。唯一约束与普通查询索引保留。
-- 公共字段统一：三表均有 id / created_at / updated_at（对应 common.BaseEntity），updated_at 由应用层 @PreUpdate 维护。
-- ============================================================================

CREATE TABLE llm_providers (
  id                  BIGSERIAL PRIMARY KEY,
  slug                VARCHAR(64)  NOT NULL UNIQUE,
  name                VARCHAR(64)  NOT NULL,
  base_url            VARCHAR(255) NOT NULL,
  api_key_ciphertext  TEXT         NOT NULL,                     -- AES-GCM Base64(iv+密文)，绝不落明文
  models              VARCHAR(500) NOT NULL DEFAULT '',          -- 逗号分隔可用模型清单
  status              VARCHAR(20)  NOT NULL DEFAULT 'active',    -- active|disabled
  remark              VARCHAR(255),
  created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()        -- 由应用层 @PreUpdate 维护
);

CREATE TABLE app_keys (
  id           BIGSERIAL PRIMARY KEY,
  org_id       BIGINT       NOT NULL,                            -- 无外键，一致性由应用层保证
  name         VARCHAR(64)  NOT NULL,
  key_prefix   VARCHAR(16)  NOT NULL,                            -- 展示用前缀，如 eck-a1b2c3d4
  key_hash     VARCHAR(64)  NOT NULL UNIQUE,                     -- SHA-256 hex，绝不落明文
  status       VARCHAR(20)  NOT NULL DEFAULT 'active',           -- active|revoked
  created_by   BIGINT       NOT NULL,
  last_used_at TIMESTAMPTZ,                                      -- 网关转发校验时回写（后续阶段）
  revoked_at   TIMESTAMPTZ,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_app_keys_org ON app_keys(org_id);

CREATE TABLE app_key_providers (
  id          BIGSERIAL PRIMARY KEY,
  app_key_id  BIGINT      NOT NULL,                              -- 无外键，一致性由应用层保证
  provider_id BIGINT      NOT NULL,                              -- 无外键，一致性由应用层保证
  model_name  VARCHAR(64) NOT NULL DEFAULT '',                   -- '' = 该 provider 全部模型
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_akp UNIQUE (app_key_id, provider_id, model_name)
);
