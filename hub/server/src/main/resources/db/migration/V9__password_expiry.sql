-- V9：密码有效期管理（2 个月到期 + 临期提醒）
-- 起算点 = password_changed_at：建号 / 管理员重置 / 自助改密均刷新为当前时刻。
-- 存量用户平滑迁移：默认 now()，即上线后统一获得一个完整有效期窗口，不立即判过期。
-- 到期判定与临期窗口在应用层 PasswordPolicy 计算（有效期 60 天、临期窗口 7 天），不额外落库冗余列。
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();
