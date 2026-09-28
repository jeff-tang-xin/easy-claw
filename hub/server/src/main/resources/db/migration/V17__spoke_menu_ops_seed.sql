-- ============================================================================
-- V17__spoke_menu_ops_seed.sql
-- spoke 功能配置上收 hub：菜单种子 + 运维服务器目录 + shell 命令白名单
--              + 工作区知识库/黑板云存储（cloud 模式实时同步的落点）。
--
-- 背景：
--   1) spoke 前端侧边栏菜单此前硬编码在 App.tsx；本迁移把全部功能菜单
--      作为平台级目录种子写入 menu_items（V16 重建后的空表），
--      cloud 模式下 spoke 经 GET /api/spoke/menus 拉取渲染，本地不再维护。
--   2) 运维（ops）是团队功能：可连的服务器清单由 hub 统一维护
--      （ops_servers，platformAdmin CRUD），spoke 只读消费、本地不能维护；
--      local 模式不展示运维菜单。
--   3) 运维 agent 模式的 shell 命令白名单此前硬编码在 OpsPage.tsx；
--      本迁移将其落库（shell_commands），cloud 模式下经
--      GET /api/spoke/shell-commands 下发，前端动态构建判定器。
--   4) 工作区知识库/黑板（AI 工具读写、spoke 页面浏览）在 cloud 模式下
--      以 hub 为唯一真源实时读写（workspace_knowledge / workspace_blackboard），
--      local 模式仍用 spoke 本地文件，两模式互不混写。
-- 沿用既有决策：全库无外键；公共字段 id/created_at/updated_at 对应 BaseEntity。
-- ============================================================================

-- 1. menu_items 种子：spoke 全部功能菜单（3 组 9 项，与原 App.tsx 硬编码一致）
--    组节点 path 为空；叶子项 path 为 spoke 前端路由。
INSERT INTO menu_items (menu_key, label, icon, path, parent_id, sort_order) VALUES
  ('group-workspace', '工作区',   '',   '',                   NULL, 10),
  ('group-tools',     '工具管理', '',   '',                   NULL, 20),
  ('group-system',    '系统管理', '',   '',                   NULL, 30);

INSERT INTO menu_items (menu_key, label, icon, path, parent_id, sort_order) VALUES
  ('solo',      'SOLO',    '👤', '/workspaces/single', (SELECT id FROM menu_items WHERE menu_key = 'group-workspace'), 10),
  ('ops',       '运维',    '🖥️', '/workspaces/ops',    (SELECT id FROM menu_items WHERE menu_key = 'group-workspace'), 20),
  ('scenario',  '场景',    '🎬', '/scenarios',         (SELECT id FROM menu_items WHERE menu_key = 'group-tools'),     10),
  ('skill',     'Skill',   '📚', '/skills',            (SELECT id FROM menu_items WHERE menu_key = 'group-tools'),     20),
  ('tools',     '工具',    '🔧', '/tools',             (SELECT id FROM menu_items WHERE menu_key = 'group-tools'),     30),
  ('mcp',       'MCP',     '🔌', '/mcp',               (SELECT id FROM menu_items WHERE menu_key = 'group-tools'),     40),
  ('blackboard','记录本',  '🗒️', '/blackboard',        (SELECT id FROM menu_items WHERE menu_key = 'group-system'),    10),
  ('knowledge', '知识库',  '🧠', '/knowledge',         (SELECT id FROM menu_items WHERE menu_key = 'group-system'),    20),
  ('settings',  '设置',    '⚙️', '/settings',          (SELECT id FROM menu_items WHERE menu_key = 'group-system'),    30);

-- 2. ops_servers：团队运维服务器目录（hub 统一维护，spoke 只读消费）
--    凭证红线：本表只存地址与账号，绝不存密码/私钥——凭证仍由 spoke 用户
--    连接时录入并加密落 spoke 本地（LocalCryptoService），hub 不碰凭证。
CREATE TABLE ops_servers (
  id          BIGSERIAL PRIMARY KEY,
  server_key  VARCHAR(64)  NOT NULL,                           -- spoke 侧稳定标识，全局唯一、创建后不可改
  name        VARCHAR(128) NOT NULL,
  host        VARCHAR(255) NOT NULL,
  port        INT          NOT NULL DEFAULT 22,
  username    VARCHAR(64)  NOT NULL DEFAULT '',
  description VARCHAR(255) NOT NULL DEFAULT '',
  sort_order  INT          NOT NULL DEFAULT 0,
  enabled     BOOLEAN      NOT NULL DEFAULT TRUE,              -- 平台总开关
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_ops_server_key UNIQUE (server_key)
);

-- 3. shell_commands：运维 agent 模式 shell 命令白名单（hub 维护，spoke 下发消费）
--    cmd = 命令首词；subcommands = 逗号分隔的子命令白名单（空 = 整命令放行）。
--    写类命令（rm/kill/systemctl restart 等）不入表：仍走智能体确认，与既有安全约定一致。
CREATE TABLE shell_commands (
  id          BIGSERIAL PRIMARY KEY,
  cmd         VARCHAR(64)  NOT NULL,
  subcommands TEXT         NOT NULL DEFAULT '',
  sort_order  INT          NOT NULL DEFAULT 0,
  enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_shell_cmd UNIQUE (cmd)
);

-- 3a. shell_commands 种子：自 OpsPage.tsx 硬编码白名单移植（2026-09-23 版）
--     首词级（subcommands 空 = 整命令放行，均为只读/排查类）
INSERT INTO shell_commands (cmd, subcommands, sort_order) VALUES
  -- 导航/回显
  ('ls', '', 10), ('ll', '', 11), ('la', '', 12), ('l', '', 13), ('pwd', '', 14),
  ('cd', '', 15), ('clear', '', 16), ('cls', '', 17), ('echo', '', 18), ('printf', '', 19), ('history', '', 20),
  -- 身份/系统信息
  ('whoami', '', 30), ('id', '', 31), ('groups', '', 32), ('hostname', '', 33), ('uname', '', 34),
  ('arch', '', 35), ('date', '', 36), ('cal', '', 37), ('uptime', '', 38), ('nproc', '', 39),
  ('lscpu', '', 40), ('lsmod', '', 41), ('lspci', '', 42), ('lsusb', '', 43), ('lsblk', '', 44),
  ('dmidecode', '', 45), ('env', '', 46), ('printenv', '', 47),
  -- 内存/磁盘/IO
  ('free', '', 60), ('df', '', 61), ('du', '', 62), ('vmstat', '', 63), ('iostat', '', 64),
  ('mpstat', '', 65), ('pidstat', '', 66),
  -- 进程（只查不杀）
  ('ps', '', 80), ('pgrep', '', 81), ('pidof', '', 82), ('pstree', '', 83),
  -- 文件查看/检索/文本处理
  ('cat', '', 100), ('head', '', 101), ('tail', '', 102), ('tac', '', 103), ('rev', '', 104),
  ('grep', '', 105), ('egrep', '', 106), ('fgrep', '', 107), ('zcat', '', 108), ('zgrep', '', 109),
  ('find', '', 110), ('stat', '', 111), ('file', '', 112), ('tree', '', 113), ('wc', '', 114),
  ('sort', '', 115), ('uniq', '', 116), ('cut', '', 117), ('tr', '', 118), ('comm', '', 119),
  ('column', '', 120), ('fold', '', 121), ('fmt', '', 122), ('sed', '', 123),
  -- 网络诊断
  ('ping', '', 140), ('ss', '', 141), ('netstat', '', 142), ('ifconfig', '', 143),
  ('traceroute', '', 144), ('tracepath', '', 145), ('mtr', '', 146),
  ('dig', '', 147), ('nslookup', '', 148), ('host', '', 149), ('arp', '', 150),
  -- 日志
  ('journalctl', '', 160), ('dmesg', '', 161),
  -- 哈希/编码/路径
  ('md5sum', '', 180), ('sha1sum', '', 181), ('sha256sum', '', 182), ('sha512sum', '', 183),
  ('cksum', '', 184), ('xxd', '', 185), ('od', '', 186), ('hexdump', '', 187),
  ('base64', '', 188), ('readlink', '', 189), ('realpath', '', 190),
  ('dirname', '', 191), ('basename', '', 192),
  -- 其他只读
  ('which', '', 200), ('whereis', '', 201), ('type', '', 202), ('alias', '', 203),
  ('seq', '', 204), ('expr', '', 205), ('sleep', '', 206);

-- 3b. shell_commands 种子：子命令级（多面手工具只放只读/诊断子命令）
INSERT INTO shell_commands (cmd, subcommands, sort_order) VALUES
  ('systemctl', 'status,show,is-active,is-enabled,is-failed,is-system-running,list-units,list-unit-files,list-timers,list-dependencies,list-sockets,cat,get-default,show-environment', 300),
  ('service',   'status,--status-all', 301),
  ('docker',    'ps,images,image,logs,inspect,stats,top,version,info,port,diff,history,search,exec', 302),
  ('podman',    'ps,images,image,logs,inspect,stats,top,version,info,port,diff,history,search,exec', 303),
  ('crictl',    'ps,images,pods,info,logs,inspect,stats,version,imagefsinfo', 304),
  ('kubectl',   'get,describe,logs,top,version,cluster-info,api-resources,api-versions,explain,events,exec', 305),
  ('ip',        'addr,a,link,l,route,r,neigh,n,netns', 306),
  ('git',       'status,log,diff,show,branch', 307);

-- 4. workspace_knowledge：工作区知识库云存储（cloud 模式唯一真源）
--    结构对齐 spoke KnowledgeEntry(topic, summary) + 全文 content；
--    (org_id, workspace_id, topic) 唯一——同组织同工作区内 topic 唯一，
--    团队成员经各自 spoke 读写同一份数据（workspace_id 由 spoke 上报）。
CREATE TABLE workspace_knowledge (
  id           BIGSERIAL PRIMARY KEY,
  org_id       BIGINT       NOT NULL,                            -- 无外键，一致性由应用层保证
  workspace_id VARCHAR(64)  NOT NULL,
  topic        VARCHAR(128) NOT NULL,
  summary      VARCHAR(512) NOT NULL DEFAULT '',
  content      TEXT         NOT NULL DEFAULT '',
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_ws_knowledge UNIQUE (org_id, workspace_id, topic)
);
CREATE INDEX idx_ws_knowledge_ws ON workspace_knowledge(org_id, workspace_id);

-- 5. workspace_blackboard：工作区记录本云存储（append-only，cloud 模式唯一真源）
--    结构对齐 spoke BlackboardEntry(seq, ts, author, type, content)；
--    归档语义与 spoke 本地一致：归档 = 该本全部行 book_key 改名为
--    <key>.archived-<时间戳>（UPDATE，不改不删任何条目），活跃本随之归零。
CREATE TABLE workspace_blackboard (
  id           BIGSERIAL PRIMARY KEY,
  org_id       BIGINT       NOT NULL,                            -- 无外键，一致性由应用层保证
  workspace_id VARCHAR(64)  NOT NULL,
  book_key     VARCHAR(192) NOT NULL,                            -- 含归档后缀 <key>.archived-<ts>
  seq          BIGINT       NOT NULL,                            -- 本内自增序号，从 1 开始，排序以此为准
  ts           VARCHAR(64)  NOT NULL DEFAULT '',                 -- ISO-8601 登记时刻，仅展示
  author       VARCHAR(64)  NOT NULL DEFAULT '',                 -- main / 子 Agent sessionId 尾段
  entry_type   VARCHAR(32)  NOT NULL DEFAULT 'note',             -- note/finding/risk/conclusion
  content      TEXT         NOT NULL DEFAULT '',
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_ws_blackboard UNIQUE (org_id, workspace_id, book_key, seq)
);
CREATE INDEX idx_ws_blackboard_ws ON workspace_blackboard(org_id, workspace_id, book_key);
