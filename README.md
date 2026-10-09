# Easy Claw - AI Work Assistant

> 基于 AgentScope 2.0 构建的可配置 AI 助手平台：**Workspace 隔离**、**多模型 Provider**、**MCP 双向桥接**、**场景化多智能体**、**数据库/运维工作台**，配套 **hub 平台管理后端**（多组织/项目/积分/审计）。

一个 Spring Boot + React 全栈项目，采用 Maven 多模块结构：

- **web** — 主应用（单体助手 + 多智能体协作 + DB/运维工作台），SQLite 存储、独立可运行
- **hub** — 平台管理后端（组织/项目/用户/模型目录/积分网关/审计/资源下发），PostgreSQL + Flyway
- **agentscope-java** — AgentScope 2.0 框架源码（core / harness / extensions / distribution）

web 主应用所有元数据存于本地 SQLite，零外部依赖即可启动。

---

## ✨ 核心特性

### 🔒 Workspace 隔离
- 每个 Workspace 是独立的文件目录，AI 的所有读写都限制在该目录内
- 路径白名单/黑名单机制（`forbidden-paths`）：默认禁止访问 `.git`、`.env`、`.idea`、`.easyClaw`、AgentScope runtime 目录等
- 文件大小上限保护（默认 10MB）
- 支持多 Workspace 并存，互不污染

### 🧠 多模型 Provider（OpenAI 兼容协议）
- 统一走 OpenAI 兼容协议，AgentScope 扩展无需额外依赖
- 内置 7 个 Provider：`deepseek` / `deepseek-reasoner`（thinking 可视化）/ `openai` / `dashscope` / `ollama`（本地）/ `moonshot` / `zhipu`
- API Key 留空时按 `<PROVIDER>_API_KEY` 环境变量兜底
- 支持 `temperature`、`stream` 等参数配置

### 🎭 场景系统（Scenario：单体 / 多智能体 / 专项工作台）
- **single**：单体智能体，按需绑定 Skill / 子 Agent / MCP 服务
- **team**：多智能体编排——主 Agent 作为协调者拆解任务，按阶段并行派发子 Agent、统一验收；子 Agent 进度实时流式展示
- **db**：数据库工作台（见下文「DB 工作区」）
- **ops**：运维工作台——远程服务器管理、交互式终端、AI 模式（受控执行）/ SH 模式（直连 PTY）
- **schedule**：定时任务模式
- 场景绑定（Skill / 子 Agent / MCP / 能力档位 CapabilityTier）全部 UI 可配，即时生效

### 🤝 子 Agent 团队（SPI 插件化）
- 内置 9 个子 Agent：`main` / `coder` / `code-expert` / `reviewer` / `planner` / `researcher` / `file-expert` / `db` / `ops`
- 子 Agent 以独立 Maven 模块声明（`web/agents/agent-*`），SPI 自动发现，新增即插即用
- 团队协作基础设施：**共享黑板**（blackboard，跨 Agent 结论登记）、**本地知识库**（wiki 式条目，跨会话沉淀）
- 步数上限、超时提升（timeout promotion）、失控防护（orphan cancel / 循环调度守卫）内置

### 🗄 DB 工作区
- 数据库连接**只来自 hub 下发**（按组织 + 用户授权过滤），spoke 端不持久化任何凭证；手输密码经 RSA-OAEP 加密传输
- **只读双防线**：物理防线是 DB 只读账号；行为防线是 `DbQueryGuard`（首词白名单 + 危险子句定位匹配），写操作直接拒绝
- 查询工具（`db_query` / `db_schema` / `db_status`）静默放行，不打断工作流；结果敏感列自动脱敏、超长自动截断
- **连接上下文自动注入**：连接建立后，库类型/版本/主机/库/schema 清单随消息供给模型，免去盲目探测
- **右侧表面板**：连接成功即列出全部表（`schema.table` 复合名，四库型全覆盖），点击表名展开列结构
- 查询审计：执行前入队上报 hub（谁在什么时候对哪个库执行了什么）

### 🌐 实时对话
- WebSocket 流式输出（替代传统 SSE，连接更稳定）
- 支持推理模型 `deepseek-reasoner` 的 thinking 过程可视化
- 子 Agent 事件流（派发/文本/工具调用）实时渲染，顶栏徽标显示运行中的成员
- HITL 工具确认（require confirm）与「本轮/永久允许」授权机制

### 🐍 内置 Python 沙箱
- GraalPy（`polyglot`）嵌入式 Python 运行时，`run_python` 工具可直接执行 Python 3 代码
- 适合精确计算、数据变换、正则抽取等「LLM 硬算容易错」的场景

### 🏢 hub 平台管理后端
- 多组织 / 项目 / 用户 / appKey 管理，RBAC（platformAdmin 等）
- **模型目录与积分网关**：模型登记、按请求模型名扣积分、Provider 授权
- **资源下发管道**：运维服务器、数据库连接、知识库/黑板云同步统一经 spoke 端点下发
- 审计日志、功能开关（Feature Flags）、菜单配置

---

## 🛠 技术栈

### 后端
- **Spring Boot 3.4.1** + **Java 21**
- **AgentScope 2.0.2**（源码内嵌于 `agentscope-java/`：core / harness / extensions-model-openai / distribution）
- **web**：Spring Data JPA + **SQLite**（Hibernate `ddl-auto: update`），零外部依赖
- **hub**：Spring Data JPA + **PostgreSQL**（**Flyway** 管理 schema，禁用 ddl-auto）+ **JWT** 鉴权 + 敏感字段 AES-GCM 静态加密
- **Spring WebSocket**（流式对话）
- **GraalPy polyglot**（Python 沙箱）
- **Project Reactor**（响应式事件流，禁止 `.block()`）

### 前端
- **React 18.3** + **TypeScript 5.6**
- **Vite 5**（`frontend-maven-plugin` 集成）
- 纯原生 CSS（无 UI 框架依赖，语义色 token 化）

### 数据存储
- **web 主应用**：`~/.easyClaw/` 下 SQLite（零外部依赖）
- **hub 平台库**：PostgreSQL（环境变量配置连接，Flyway 管理 schema）
- **Workspace 状态/对话**：`<workspace>/.easyClaw/agent/` 目录下

---

## 🚀 快速开始

### 环境要求
- **JDK 21+**
- **Maven 3.8+**
- **Node.js 22+**（仅首次构建前端需要，之后由 `frontend-maven-plugin` 自动管理）

### 启动主应用（web）

```bash
# 1. 克隆
git clone https://github.com/jeff-tang-xin/easy-claw.git
cd easy-claw

# 2. 配置模型 Provider（任选其一）
export DEEPSEEK_API_KEY=sk-xxx          # DeepSeek
# 或
export DASHSCOPE_API_KEY=sk-xxx         # 阿里通义千问
# 或
export OPENAI_API_KEY=sk-xxx            # OpenAI 兼容服务

# 3. 构建 + 启动（首次会触发前端 npm install + vite build）
mvn install -DskipTests
mvn -pl web/api spring-boot:run
```

启动后访问：**http://localhost:18080**

### 启动平台管理后端（hub，可选）

hub 需要 PostgreSQL 实例，连接信息走环境变量：

```bash
export HUB_DB_URL=jdbc:postgresql://localhost:5432/easy_claw
export HUB_DB_USER=postgres
export HUB_DB_PASSWORD=xxx
export HUB_JWT_SECRET=<随机长串>       # 生产必须覆盖默认值
export HUB_MASTER_KEY=<随机长串>       # 敏感字段加密主密钥，生产必须覆盖

mvn -pl hub/server spring-boot:run
```

启动后访问：**http://localhost:18081**（schema 由 Flyway 自动迁移，首次启动自动建表）

### 配置 Provider

在 `web/api/src/main/resources/application.yml` 中选择当前激活的 Provider：

```yaml
agentscope:
  model:
    provider: deepseek        # 当前激活的 provider
    api-key:                  # 留空时按 DEEPSEEK_API_KEY 环境变量兜底
    base-url:                 # 留空时使用 provider 默认值
    temperature: 0.3
    stream: true
```

切换 Provider 只需改 `provider` 字段，其余 providers 表保持不动。

---

## 📁 项目结构

```
easy-claw/
├── pom.xml                                      # 父 POM（web / hub / agentscope-java 三聚合）
├── web/                                         # 主应用
│   ├── base/                                    # 基础公共层（跨模块共享类型）
│   ├── agent-core/                              # AgentScope 集成核心（agent / scenario / middleware）
│   ├── agents/                                  # 子 Agent SPI 模块（每个子 Agent 一个子模块）
│   │   ├── agent-main / agent-coder / agent-code-expert / agent-reviewer
│   │   ├── agent-planner / agent-researcher / agent-file-expert
│   │   └── agent-db / agent-ops
│   ├── modes/                                   # 模式 SPI 模块
│   │   ├── mode-single / mode-team              # 单体 / 多智能体编排
│   │   └── mode-db / mode-ops / mode-schedule   # 专项工作台
│   ├── api/                                     # 主应用（启动类 + REST/WS + 业务）
│   │   ├── src/main/java/com/xinl/easyclaw/
│   │   │   ├── agent/                           # AgentService（会话/事件流/副作用）
│   │   │   ├── api/                             # REST Controller
│   │   │   ├── blackboard/ knowledge/ memory/   # 黑板 / 知识库 / 记忆
│   │   │   ├── db/                              # DB 工作区（连接服务 / 查询编排 / 渲染）
│   │   │   ├── mcp/                             # MCP IN/OUT 桥接
│   │   │   ├── ops/                             # 运维（服务器管理 / 远程终端）
│   │   │   ├── permission/                      # 工具权限（确认 / 白名单 / 授权规则）
│   │   │   ├── python/                          # GraalPy 沙箱
│   │   │   ├── scenario/ workspace/             # 场景解析 / Workspace 安全
│   │   │   ├── tool/ tools/                     # 工具权限策略 / 内置工具
│   │   │   └── ws/                              # WebSocket 处理
│   │   └── frontend/                            # React 前端（11 个页面）
│   └── frontend/                                # 前端构建工作目录（node_modules 缓存）
├── hub/                                         # 平台管理后端
│   ├── contract/                                # hub↔spoke 契约（DTO / 端点约定）
│   ├── common/                                  # hub 公共库
│   └── server/                                  # 平台服务（端口 18081）
│       └── frontend/                            # hub 前端（18 个页面）
├── agentscope-java/                             # AgentScope 2.0 框架源码
│   ├── agentscope-core / agentscope-harness     # 核心 / harness（工具调度、子 Agent）
│   ├── agentscope-extensions/                   # 模型扩展（openai 兼容等）
│   └── agentscope-distribution / -bom / -examples
└── docs/                                        # 设计文档（DB 工作区 / 重构计划等）
```

---

## 🖥 页面一览

### web 前端（http://localhost:18080，11 个页面）

| 页面 | 用途 |
|------|------|
| **ChatPage** | 主对话界面，WebSocket 流式输出、子 Agent 进度、工具确认条 |
| **WorkspacesPage** | Workspace 增删改、切换激活工作区 |
| **ScenariosPage** | 场景管理：single/team 模式、Skill/子 Agent/MCP 绑定、编排工作流步骤 |
| **DbPage** | DB 工作台：连接管理、对话查询、右侧表清单（点击看表结构） |
| **OpsPage** | 运维工作台：服务器列表、交互式终端、AI 模式 / SH 模式 |
| **KnowledgePage** | 本地知识库：条目增删改查、全文检索 |
| **BlackboardPage** | 共享黑板：任务结论/风险/决策的时间线视图 |
| **RolesPage** | AI 角色/系统提示词管理 |
| **SkillsPage** | Skill 启停、查看详情、跨作用域管理、脚本执行 |
| **McpPage** | MCP Server 配置、IN/OUT 桥接管理 |
| **ToolsPage** | 内置工具列表与参数说明 |
| **SettingsPage** | 全局参数（超时、Shell 超时、模型参数、路径安全） |

### hub 前端（http://localhost:18081，18 个页面）

| 分组 | 页面 |
|------|------|
| 组织与用户 | OrgsPage / OrgDetailPage / ProjectsPage / ProjectSpacePage / UsersPage / AppKeysPage |
| 模型与网关 | ProvidersPage / ModelCatalogPage / GatewayPage / CreditsPage |
| 平台目录 | PlatformCatalogPage（运维服务器 / 数据库连接 / 标签字典） |
| 治理 | AuditLogsPage / FeatureFlagsPage / MenuConfigPage |
| 协作 | KnowledgePage / BlackboardPage / RolesPage（项目级云同步） |

---

## 🔐 Workspace 安全机制

每个 Workspace 启动时都会校验路径合法性。默认禁止 AI 工具访问以下路径：

```yaml
ai:
  workspace:
    security:
      forbidden-paths:
        - .easyClaw          # 自身元数据目录
        - .git
        - .env
        - .idea
        - .vscode
        - default-user       # AgentScope harness runtime
        - agents
      max-file-size: 10485760  # 10MB
```

可以在 SettingsPage 调整这些参数（运行期生效）。

工具权限分层（`ToolPermissionPolicy`，唯一权威来源）：
- **静默放行**：只读工具（读文件/检索/黑板/知识库/DB 查询三件套）
- **显式确认**：写文件 / 编辑 / Shell / 远程执行——每次弹确认，用户可选「本轮允许 / 永久允许」
- **未知工具** fail-closed：一律需要确认

---

## 🧩 Skill 系统详解

### 作用域优先级
```
SYSTEM > GLOBAL > WORKSPACE
```
- 同一 Skill 名在多作用域同时存在时，**高优先级覆盖低优先级**
- `SYSTEM` Skill 不可修改/删除

### Skill 文件格式
每个 Skill 是一个目录，包含 `SKILL.md`（必需）+ 可选脚本/资源：

```
<skill-name>/
├── SKILL.md              # 必需：描述 Skill 的用途、加载时机、使用规范
└── scripts/              # 可选：可被 AI 执行的脚本
```

AI 在对话中会根据 Skill 描述自动判断是否加载。

### 内置 Skill 列表

| Skill | 用途 |
|-------|------|
| `cursor-rules` | Agent 协作规范（沟通风格、原子操作、验证闭环） |
| `karpathy-guidelines` | Karpathy 风格编码原则（思考先于编码、外科手术式修改） |
| `clean-code` | 五维代码审查（正确性/可读性/架构/安全/性能） |
| `backend-architecture` | 后端架构标准（API 设计、错误处理、数据层、安全） |
| `code-refactor` | 重构指南（坏味道检测、手法速查、红牌警告） |
| `frontend-quality` | 前端质量标准（组件设计、性能、状态管理、A11y） |
| `vercel-react-best-practices` | React/Next.js 性能优化（Vercel 官方） |
| `antdesign-philosophy` | Ant Design 设计理念与 Token 体系 |
| `elementui-philosophy` | Element UI 设计理念与组件规范 |
| `devops-cicd` | DevOps 实践（流水线、Docker、GitHub Actions） |

---

## 🔌 MCP 桥接示例

### INBOUND：引入外部 MCP Server 的工具
在 `McpPage` 添加：
- **Name**: `github`
- **Transport**: `stdio` / `http` / `sse`
- **Command/URL**: `npx -y @modelcontextprotocol/server-github`
- **Env**: `GITHUB_TOKEN=ghp_xxx`

保存后 AI 立即可调用 GitHub 相关工具。场景绑定 MCP 服务时，运行时按服务展开工具名白名单。

### OUTBOUND：把内置工具暴露为 HTTP 端点
McpPage → "Expose Tool" → 选择工具 → 自动生成 `POST /mcp/http_tool/{name}` 端点。

---

## ⚙️ 关键配置项

```yaml
agentscope:
  agent:
    max-iters: 50                  # 单轮对话最大迭代次数
    model-timeout-minutes: 10      # 模型调用超时
    tool-timeout-minutes: 30       # 工具调用超时
    shell-timeout-seconds: 300     # Shell 工具超时
    max-shell-output-bytes: 200000 # Shell 输出截断
```

可在 SettingsPage 实时调整。

---

## 🗄 数据存储

| 数据 | 位置 |
|------|------|
| web 系统元数据库（Workspace/Provider/MCP/Skill 索引等） | `~/.easyClaw/`（SQLite） |
| hub 平台库（组织/项目/用户/模型目录/积分/审计/连接目录） | **PostgreSQL**（`HUB_DB_URL` 等环境变量配置连接，Flyway 管理 schema） |
| Workspace 自身状态、对话历史 | `<workspace>/.easyClaw/agent/` |
| 用户级 GLOBAL Skill | `~/.easyClaw/skills/` |
| AgentScope Harness runtime | `<workspace>/<userId>/agents/`（被禁访） |

> web 主应用零外部依赖（SQLite）；hub 需要 PostgreSQL 实例（连接信息走环境变量，不入仓）。

> 卸载/迁移时只需保留 `~/.easyClaw/` 目录即可带走所有配置与历史。

---

## 🧪 开发说明

### 常用命令

```bash
# 全量构建（约 11 分钟，含前端构建与全部测试）
mvn clean install

# 只编译主应用（离线、跳测试）
mvn -q -pl web/api -am compile -o -DskipTests

# 定向跑单个测试类（必须带 -am 与 failIfNoSpecifiedTests=false）
mvn -pl hub/server -am test -o -Dtest=<TestPattern> -Dsurefire.failIfNoSpecifiedTests=false

# 前端单独构建（web 前端）
cd web/api/frontend && npx vite build --logLevel warn

# 前端热更新开发
cd web/api/frontend && npm install && npm run dev
```

### 新增一个子 Agent（SPI）
1. 在 `web/agents/` 下新建 `agent-xxx` 模块（参照 `agent-coder`）
2. 实现 Agent 声明 SPI（名称/描述/系统提示词/工具白名单/步数）
3. `web/agents/pom.xml` 加入 `<module>`，重新构建——SPI 自动发现，ScenariosPage 即可绑定

### 新增一个模式（SPI）
`web/modes/` 下参照 `mode-db` 新建模块，声明模式名与 `whitelistEnabled` 等行为，SPI 自动注册。

### 添加新的内置 Skill
1. 在内置 Skill 目录下创建 `<skill-name>/SKILL.md`
2. `BuiltinSkillsInstaller` 启动时会自动播种到系统表
3. 重启后即可在 SkillsPage 看到

### 添加新的模型 Provider
所有 Provider 共享 OpenAI 兼容协议，**通常无需改代码**，只需在 `application.yml` 的 `agentscope.providers` 下加配置项。

---

## 📜 许可证

本项目基于 **MIT License** 开源。

---

## 🔗 相关链接

- 仓库：https://github.com/jeff-tang-xin/easy-claw
- AgentScope 文档：https://github.com/agentscope-ai/agentscope
- MCP 协议规范：https://modelcontextprotocol.io

---

<p align="center">
  <sub>Built with Spring Boot 3.4 · React 18 · AgentScope 2.0</sub>
</p>

