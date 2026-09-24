-- ============================================================================
-- V19__ops_server_password.sql
-- 运维服务器登录密码（临时运维分发场景）：hub 侧保存密码密文，随目录下发 spoke。
--   1) ops_servers 加 password_enc：AES-256-GCM 密文（CryptoService，与 llm_providers.api_key 同机制），
--      NULL = 未设置（spoke 仍走「录入凭证」本地建连流程，向后兼容）。
--   2) 回显红线：平台目录清单（OpsServerDto）只回 passwordSet 布尔位，绝不回显明文/密文；
--      下发 spoke（SpokeOpsServer）时才解密为明文随目录下发（临时运维场景，用户已确认）。
-- ============================================================================

ALTER TABLE ops_servers ADD COLUMN password_enc VARCHAR(1024);
