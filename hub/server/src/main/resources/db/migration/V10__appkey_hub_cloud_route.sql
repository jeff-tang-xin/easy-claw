-- ============================================================================
-- V10__appkey_hub_cloud_route.sql
-- 逻辑模型别名 hub_cloud 的 per-appkey 默认路由：
--   spoke（web 云端模式）恒定发送逻辑模型名 hub_cloud，hub 按 appkey 配置把它
--   解析到真实 provider + model，并在转发前改写请求体 model（设计 §4.1：
--   「请求中带逻辑 provider/model 标识，hub 解析为真实 base-url + key」）。
--
--   cloud_provider_id  为空 = 该 appkey 未配置 hub_cloud 路由（请求 hub_cloud 报 404）；
--                      非空必须指向 active 且该 appkey 已绑定的 provider（应用层校验）。
--   cloud_model_name   目标真实模型名，必须在 provider 声明的 models 清单内（应用层校验）。
--
-- 与 app_key_providers 绑定表的关系：绑定表框定 appkey 的【可见/可用模型面】，
-- 本表两列只在该面内选定 hub_cloud 这一个逻辑名的落点，二者口径一致（同 provider+model）。
-- 全库无外键（沿用 V3/V4 决策），引用一致性由应用层保证。
-- ============================================================================

ALTER TABLE app_keys
    ADD COLUMN cloud_provider_id BIGINT,
    ADD COLUMN cloud_model_name  VARCHAR(64) NOT NULL DEFAULT '';
