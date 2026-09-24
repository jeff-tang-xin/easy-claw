-- ============================================================================
-- V27__provider_credits.sql
-- Provider 积分池（在 V26 授权模型上叠加总量维度）：
--   1) provider_grants 加周期发放计划三列（全部可空，NULL = 不发放该周期积分）：
--      daily_credits / monthly_credits / yearly_credits。
--      不回填默认值：存量授权行为零变化（积分池未启用），管理员在授权上配置
--      发放计划后该授权才启用积分池（推荐每日 500 起）。
--   2) provider_credits 积分流水表：每笔发放一行（周期积分惰性发放 + 管理员临时积分），
--      消耗按 FIFO 分摊到行（consumed），过期时间越早越先消耗；过期未耗尽部分作废
--      （惰性过期：剩余计算按 expires_at > now() 过滤，无需定时任务）。
--      period_key 为当期标识（daily=2026-09-24 / monthly=2026-09 / yearly=2026）防重复
--      发放；temp 行 period_key 为 NULL（SQL 唯一约束对 NULL 不去重，临时积分可多笔）。
--   3) 扣减口径：按请求模型的积分比例扣减（model_catalog.credit_cost，未登记的模型默认 1），
--      积分池启用（grant 配置了任一周期发放或存在积分行）时与 daily_limit 叠加校验
--      （先每日次数后积分余额），未启用维持现状。
--   4) model_catalog 模型目录：平台级维护模型与积分比例（platformAdmin），provider 的
--      models 清单按名称引用目录模型；credit_consumptions 消耗记录：每次请求一条
--      （模型、消耗分值），供积分使用情况页面展示使用记录。
-- 沿用既有决策：全库无外键，一致性由应用层保证。
-- ============================================================================

ALTER TABLE provider_grants
  ADD COLUMN daily_credits INT;

ALTER TABLE provider_grants
  ADD COLUMN monthly_credits INT;

ALTER TABLE provider_grants
  ADD COLUMN yearly_credits INT;

CREATE TABLE provider_credits (
  id          BIGSERIAL PRIMARY KEY,
  grant_id    BIGINT         NOT NULL,                         -- 无外键，一致性由应用层保证
  period_type VARCHAR(10)    NOT NULL,                         -- daily | monthly | yearly | temp
  period_key  VARCHAR(20),                                     -- 当期标识；temp 行为 NULL
  credits     NUMERIC(12, 1) NOT NULL,                         -- 本笔发放面额（支持 1 位小数）
  consumed    NUMERIC(12, 1) NOT NULL DEFAULT 0,               -- 本笔已消耗（FIFO 分摊）
  expires_at  TIMESTAMPTZ    NOT NULL,                         -- 本笔积分有效期至（必填）
  created_by  BIGINT,                                          -- 发放操作人（周期发放为 NULL）
  created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
  CONSTRAINT uk_provider_credits_period UNIQUE (grant_id, period_type, period_key)
);
CREATE INDEX idx_provider_credits_grant ON provider_credits(grant_id);

-- 模型目录：平台级模型清单与积分比例（1 次请求消耗 credit_cost 分；未登记模型默认 1 分）
-- credit_cost 支持 1 位小数（如 0.5），存储时多余位数舍弃不进位
CREATE TABLE model_catalog (
  id          BIGSERIAL PRIMARY KEY,
  model_name  VARCHAR(255)   NOT NULL UNIQUE,                  -- 模型名（provider.models 按名称引用）
  credit_cost NUMERIC(10, 1) NOT NULL DEFAULT 1,               -- 每次请求消耗积分
  remark      VARCHAR(255),
  created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- 积分消耗记录：每次请求一条（追加型，无 updated_at），供使用记录查询
CREATE TABLE credit_consumptions (
  id          BIGSERIAL PRIMARY KEY,
  grant_id    BIGINT         NOT NULL,                         -- 无外键，一致性由应用层保证
  provider_id BIGINT         NOT NULL,
  model_name  VARCHAR(255)   NOT NULL,
  cost        NUMERIC(12, 1) NOT NULL,                         -- 本次消耗积分（按模型比例）
  created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_credit_consumptions_grant ON credit_consumptions(grant_id, id DESC);
