-- V2：认证体系改造（取消公开注册 → 平台管理员引导 + 首登强制改密）
ALTER TABLE users ADD COLUMN platform_admin BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
