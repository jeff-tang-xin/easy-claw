-- ============================================================================
-- V25__ops_command_logs.sql
-- 运维命令记录（审计）：spoke 执行运维命令后批量上报 hub 落库，platformAdmin 按服务器查询。
--   1) 新表 ops_command_logs：追加型、不可变（只有 created_at，无 updated_at）；
--      server_key/server_name/host 为上报时快照（服务器后续改名不回溯历史行）；
--      operator 为 spoke 侧操作者（appkey 创建人 userId）；source 区分 ai | user。
--   2) idx_ops_cmd_server：按组织+服务器+时间倒序查询（管理端分页主路径）。
--   3) ops_servers 加 os_type：系统类型（如 CentOS 7.9 / Ubuntu 22.04），选填，
--      随目录下发 spoke 供 AI 提示词注入。
-- ============================================================================

CREATE TABLE ops_command_logs (
    id BIGSERIAL PRIMARY KEY,
    org_id BIGINT NOT NULL DEFAULT 0,
    server_key VARCHAR(64) NOT NULL,
    server_name VARCHAR(128) NOT NULL DEFAULT '',
    host VARCHAR(255) NOT NULL DEFAULT '',
    command TEXT NOT NULL,
    source VARCHAR(16) NOT NULL,
    operator VARCHAR(128) NOT NULL DEFAULT '',
    executed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ops_cmd_server ON ops_command_logs (org_id, server_key, executed_at DESC);

ALTER TABLE ops_servers ADD COLUMN os_type VARCHAR(64) NOT NULL DEFAULT '';
