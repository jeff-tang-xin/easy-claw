-- ============================================================================
-- V13__drop_docs.sql
-- 移除 A2 文档（需求/任务）功能：docs / doc_events 表整体下线。
--   V7 已应用到既有环境，历史迁移文件不可改（Flyway 校验和），故以新迁移 DROP。
--   应用层代码（DocController/DocService/实体/仓库/DTO/测试）已同步删除。
--   黑板报（A4）建表见 V14__blackboard.sql。
-- ============================================================================

DROP TABLE IF EXISTS doc_events;
DROP TABLE IF EXISTS docs;