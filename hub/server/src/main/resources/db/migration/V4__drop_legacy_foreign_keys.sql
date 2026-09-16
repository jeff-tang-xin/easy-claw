-- ============================================================================
-- V4__drop_legacy_foreign_keys.sql
-- 用户决策：全库去外键，一致性由应用层保证。本迁移移除 V1 自动命名的外键约束；
-- IF EXISTS 兼容未应用过 V1 的新库。
--   注：V1 为已冻结的版本化迁移，不回改；新增表（V3 起）一律不写 REFERENCES。
-- ============================================================================

ALTER TABLE organizations  DROP CONSTRAINT IF EXISTS organizations_owner_user_id_fkey;
ALTER TABLE memberships    DROP CONSTRAINT IF EXISTS memberships_org_id_fkey;
ALTER TABLE memberships    DROP CONSTRAINT IF EXISTS memberships_user_id_fkey;
ALTER TABLE refresh_tokens DROP CONSTRAINT IF EXISTS refresh_tokens_user_id_fkey;
ALTER TABLE projects       DROP CONSTRAINT IF EXISTS projects_org_id_fkey;
ALTER TABLE projects       DROP CONSTRAINT IF EXISTS projects_owner_user_id_fkey;
