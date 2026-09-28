-- ============================================================================
-- V21__ops_server_category.sql
-- 运维服务器分类/标签（需求：服务器创建时必选分类标签，便于按分类维护/筛选）。
--   1) ops_servers 加 category：自由文本（如「数据库/网关/应用」），NOT NULL DEFAULT ''；
--      新增目录项必须显式提供（service 层 @NotBlank 校验），历史行空串 = 未标注。
--   2) 回显到 OpsServerDto 与表格；spoke 下发（SpokeOpsServer）暂不携带，
--      分类为平台管理属性，spoke 侧无消费场景。
-- ============================================================================

ALTER TABLE ops_servers
  ADD COLUMN category VARCHAR(64) NOT NULL DEFAULT '';
