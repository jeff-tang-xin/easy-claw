# Easy Claw - AI Work Assistant

> 基于 AgentScope 2.0 构建的可配置 AI 助手，支持 **Workspace 隔离**、**多模型 Provider**、**MCP 双向桥接**、**Skill 技能系统**。

一个轻量的 Spring Boot + React 全栈应用。AI 在被指定的工作区（Workspace）内运作，可调用工具、操作文件，并按内置/自定义的 Skill 完成多轮任务。所有元数据存于本地 SQLite，零外部依赖即可启动。

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

### 🔌 MCP（Model Context Protocol）双向桥接
- **INBOUND**：将外部 MCP Server 的工具接入 AI 工具集
- **OUTBOUND**：把内置的工具暴露为 HTTP 端点供其他 Agent 调用
- 提供可视化管理页面（`McpPage`），运行时增删改

### 📚 Skill 系统（SYSTEM / GLOBAL / WORKSPACE 三级作用域）
- **SYSTEM**：项目内置，只读
- **GLOBAL**：用户级（`~/.easyClaw/skills/`），跨 Workspace 复用
- **WORKSPACE**：工作区级，每个 Workspace 可独立装配自己的 Skill
- 提供 SkillsPage 可视化管理
- 内置 6 个 Skill：`cursor-rules` / `backend-architecture` / `code-refactor` / `frontend-quality` / `devops-cicd` / `vercel-react-best-practices`

### 🌐 实时对话
- WebSocket 流式输出（替代传统 SSE，连接更稳定）
- 支持推理模型 `deepseek-reasoner` 的 thinking 过程可视化
- 多轮对话上下文管理

### ⚙️ 全 UI 可配置
提供 7 个管理页面：

| 页面 | 用途 |
|------|------|
| **ChatPage** | 主对话界面，WebSocket 流式输出 |
| **WorkspacesPage** | Workspace 增删改、切换激活工作区 |
| **RolesPage** | AI 角色/系统提示词管理 |
| **SkillsPage** | Skill 启停、查看详情、跨作用域管理 |
| **McpPage** | MCP Server 配置、IN/OUT 桥接管理 |
| **ToolsPage** | 内置工具列表与参数说明 |
| **SettingsPage** | 全局参数（超时、Shell 超时、模型参数） |

---

## 🛠 技术栈

### 后端
- **Spring Boot 3.4.1** + **Java 21**
- **AgentScope 2.0.2**（`agentscope-core` / `agentscope-harness` / `agentscope-extensions-model-openai`）
- **Spring Data JPA** + **SQLite**（Hibernate `ddl-auto: update`）
- **Spring WebSocket**（流式对话）
- **Lombok** + **Jackson 2.16.1**

### 前端
- **React** + **TypeScript**
- **Vite**（`frontend-maven-plugin` 集成）
- 纯原生 CSS（无 UI 框架依赖）
- 状态管理：自研轻量 store

### 数据存储
- **系统元数据库**：`~/.easyClaw/ai-assistant.db`（SQLite，所有 Workspace 共用）
- **Workspace 状态/对话**：`<workspace>/.easyClaw/agent/` 目录下

---

## 🚀 快速开始

### 环境要求
- **JDK 21+**
- **Maven 3.8+**
- **Node.js 22.13+**（仅首次构建前端需要，之后由 `frontend-maven-plugin` 自动管理）

### 启动

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

# 3. 启动（首次会触发前端 npm install + vite build）
mvn spring-boot:run
```

启动后访问：**http://localhost:18080**

### 配置 Provider

在 `application.yml` 中选择当前激活的 Provider：

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
├── pom.xml                                  # Maven 配置（Spring Boot 3.4.1 / Java 21 / AgentScope 2.0.2）
├── src/main/java/com/xinl/easyclaw/
│   ├── AiAssistantApplication.java          # 启动类
│   ├── agent/                               # AgentScope 集成（ReActAgent、会话管理、模型工厂）
│   ├── config/                              # 配置类（WebSocket、CORS、SchemaMigration、BuiltinSkillsInstaller）
│   ├── controller/                          # REST + WebSocket Controller
│   ├── domain/                              # JPA Entity
│   ├── dto/                                 # 数据传输对象
│   ├── mcp/                                 # MCP IN/OUT 桥接
│   ├── repository/                          # Spring Data Repository
│   ├── service/                             # 业务逻辑
│   ├── skill/                               # Skill 加载/解析/作用域管理
│   ├── tool/                                # 内置工具实现
│   └── workspace/                           # Workspace 路径解析与安全检查
├── src/main/resources/
│   ├── application.yml                      # 主配置（端口 18080 / profile dev-sqlite）
│   └── static/                              # 前端构建产物
├── frontend/                                # React + Vite 前端
│   ├── src/
│   │   ├── pages/                           # 7 个页面（Chat/Mcp/Roles/Settings/Skills/Tools/Workspaces）
│   │   ├── components/                      # 公共组件
│   │   ├── api.ts                           # 后端 API 封装
│   │   ├── chatSocket.ts                    # WebSocket 客户端
│   │   ├── chatStore.ts                     # 对话状态管理
│   │   └── styles.css
│   ├── package.json
│   └── vite.config.ts
├── docs/
│   ├── required.md                          # 项目需求文档（V6 旧规划，已不适用）
│   └── tool.md                              # MCP / Skill / Tool 集成示例
└── README.md                                # 本文件
```

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
.cursor-rules/
├── SKILL.md              # 必需：描述 Skill 的用途、加载时机、使用规范
└── scripts/              # 可选：可被 AI 执行的脚本
```

AI 在对话中会根据 Skill 描述自动判断是否加载。

### 内置 Skill 列表

| Skill | 用途 |
|-------|------|
| `cursor-rules` | Agent 协作规范（沟通风格、原子操作、验证闭环） |
| `backend-architecture` | 后端架构标准（API 设计、错误处理、数据层、安全） |
| `code-refactor` | 重构指南（坏味道检测、手法速查、红牌警告） |
| `frontend-quality` | 前端质量标准（组件设计、性能、状态管理、A11y） |
| `devops-cicd` | DevOps 实践（流水线、Docker、GitHub Actions） |
| `vercel-react-best-practices` | React/Next.js 性能优化（Vercel 官方） |

---

## 🔌 MCP 桥接示例

### INBOUND：引入外部 MCP Server 的工具
在 `McpPage` 添加：
- **Name**: `github`
- **Transport**: `stdio` / `http` / `sse`
- **Command/URL**: `npx -y @modelcontextprotocol/server-github`
- **Env**: `GITHUB_TOKEN=ghp_xxx`

保存后 AI 立即可调用 GitHub 相关工具。

### OUTBOUND：把内置工具暴露为 HTTP 端点
McpPage → "Expose Tool" → 选择工具 → 自动生成 `POST /mcp/http_tool/{name}` 端点。

详细示例见 [`docs/tool.md`](docs/tool.md)。

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
| 系统元数据库（Workspace 列表、Provider 配置、MCP 配置、Skill 索引等） | `~/.easyClaw/ai-assistant.db`（SQLite） |
| Workspace 自身状态、对话历史 | `<workspace>/.easyClaw/agent/` |
| 用户级 GLOBAL Skill | `~/.easyClaw/skills/` |
| AgentScope Harness runtime | `<workspace>/<userId>/agents/`（被禁访） |

> 卸载/迁移时只需保留 `~/.easyClaw/` 目录即可带走所有配置与历史。

---

## 🧪 开发说明

### 后端开发
```bash
mvn spring-boot:run          # 启动后端（自动触发前端构建）
mvn test                     # 单元测试
mvn compile                  # 编译检查
```

### 前端开发（热更新）
```bash
cd frontend
npm install
npm run dev                  # Vite dev server，独立前端开发
# 单独启动后端时，前端请求会通过 Vite proxy 转发到 18080
```

### 添加新的内置 Skill
1. 在 `.easyClaw/agent/.skills-cache/.../<skill-name>/` 下创建 `SKILL.md`
2. `BuiltinSkillsInstaller` 启动时会自动播种到系统表
3. 重启后即可在 SkillsPage 看到

### 添加新的 MCP Provider
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
