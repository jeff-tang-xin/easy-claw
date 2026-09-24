-- ============================================================================
-- V22__ops_menu_path_fix.sql
-- 修正 spoke 运维菜单的前端路由：/workspaces/ops → /ops。
--
-- 背景：
--   V17 把运维作为「工作区分类」种子，path 写为 /workspaces/ops。
--   后运维改为后端唯一固定内置 workspace（id=default-ops，工作地址
--   ~/.easyClaw/ops，启动自动初始化），不再是可创建/列表展示的工作区分类；
--   前端运维入口改为无参路由 /ops（OpsPage 内部用写死 id）。
--   旧 path /workspaces/ops 命中 WorkspacesPage 后因 ops 已不在分类表，
--   会被兜底回退成 SOLO 列表，造成点「运维」看到 SOLO 工作区的错误。
-- 本迁移只改菜单项 path，幂等（WHERE 限定旧值，重复执行无副作用）。
-- ============================================================================

UPDATE menu_items
SET path = '/ops',
    updated_at = now()
WHERE menu_key = 'ops'
  AND path = '/workspaces/ops';
