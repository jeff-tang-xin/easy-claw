# MCP 重新设计方案

## Context

当前 MCP 实现存在以下问题：
1. **HTTP_TOOL 不是真正的 MCP**：`HttpAgentTool` 直接实现 `AgentTool`，不走 MCP 协议，是自包装的 REST API 工具。用户要求保留此功能。
2. **没有工具级过滤**：`AgentFactory` 调用 `toolkit.registerMcpClient(client)` 只用最简形式，没有利用 AgentScope 的 `enableTools`/`disableTools` 能力。
3. **前端 UI 粗糙**：表格+弹窗，看不到工具列表，无法选择启用哪些工具。
4. **连接状态不实时**：点击连接后需要手动刷新才能看到状态变化。

AgentScope 的 MCP 机制（`McpClientManager.registerMcpClient`）支持：
- `enableTools` — 白名单，只注册指定工具
- `disableTools` — 黑名单，排除指定工具
- `groupName` — 工具分组
- `presetParametersMapping` — 预设参数

## 设计决策

- **HTTP_TOOL 保留**：在 MCP 页面保留，但卡片上标注"REST 桥接（非 MCP 协议）"
- **全局共享不变**：MCP 服务不按 workspace 隔离
- **工具级过滤**：连接 MCP 后展示所有工具，用户可勾选启用/禁用

## 实施步骤

### 1. 后端：实体增加工具过滤字段

**文件**: `src/main/java/com/xinl/easyclaw/mcp/entity/McpServiceEntity.java`

新增字段：
```java
/** 启用的工具名列表（JSON 数组）；null/空 = 全部启用 */
@Column(name = "enabled_tools", columnDefinition = "TEXT")
private String enabledTools;
```

### 2. 后端：McpConnectionService 增加工具过滤支持

**文件**: `src/main/java/com/xinl/easyclaw/mcp/service/McpConnectionService.java` + `McpConnectionServiceImpl.java`

- `connect(id)` 连接成功后，保存 `availableTools`（已有）并初始化 `enabledTools`（默认全部启用）
- 新增 `updateEnabledTools(id, List<String> enabledTools)` 方法
- `getConnectedWrappers()` 保持不变（全局共享）
- 新增 `getEnabledTools(serviceId)` 方法返回启用工具列表

### 3. 后端：AgentFactory 注册时传入工具过滤

**文件**: `src/main/java/com/xinl/easyclaw/config/AgentFactory.java`

`createWorkspaceToolkit()` 中注册 MCP client 时，从 `McpServiceEntity.enabledTools` 读取启用的工具列表，调用：
```java
toolkit.registerMcpClient(client, enableTools, null, null).block();
```

HTTP_TOOL 桥接保持不变（已是 AgentTool，不需要 MCP 过滤）。

### 4. 后端：MCP Controller 增加工具过滤 API

**文件**: 查找 `McpController` 或 `McpApiController`

新增端点：
- `PUT /api/mcp/{id}/tools` — 更新启用的工具列表
- `GET /api/mcp/{id}/tools` — 获取工具列表 + 启用状态

### 5. 前端：McpPage 重构为卡片式 UI

**文件**: `frontend/src/pages/McpPage.tsx`

**布局**：
```
┌─────────────────────────────────────┐
│ 🔌 MCP 服务管理    [导入JSON] [+添加] │
│ [服务列表] [模板库]                    │
├─────────────────────────────────────┤
│ ┌─────────────┐  ┌─────────────┐    │
│ │ 📦 filesystem │  │ 🌐 weather    │    │
│ │ STDIO        │  │ STREAMABLE   │    │
│ │ ● 已连接      │  │ ○ 未连接      │    │
│ │              │  │              │    │
│ │ 工具列表:     │  │ 工具列表:     │    │
│ │ ☑ read_file  │  │ ☑ get_weather│    │
│ │ ☑ write_file │  │ ☐ get_forecast│   │
│ │ ☐ list_dirs  │  │              │    │
│ │              │  │              │    │
│ │ [连接][编辑]  │  │ [连接][编辑]  │    │
│ │ [删除]        │  │ [删除]        │    │
│ └─────────────┘  └─────────────┘    │
└─────────────────────────────────────┘
```

**卡片内容**：
- 服务名 + 传输类型 badge
- 连接状态指示灯（绿色已连接 / 灰色未连接）
- 服务端描述
- 工具列表（连接后才显示）：每个工具一个 checkbox，勾选=启用
- 操作按钮：连接/断开、编辑、删除

**交互**：
- 连接成功后自动拉取工具列表并渲染 checkbox
- 勾选/取消勾选工具 → 调用 `PUT /api/mcp/{id}/tools` 更新
- 连接状态变化时卡片颜色实时变化（不需要手动刷新）

### 6. 前端：连接状态实时更新

**文件**: `frontend/src/pages/McpPage.tsx`

连接/断开操作后：
- 不再手动 `await load()` 刷新整个列表
- 直接更新对应卡片的 `isConnected` 状态
- 连接成功后自动拉取 `availableTools` 并渲染工具列表

## 验证方式

1. 启动应用，进入 MCP 管理页
2. 添加一个 STDIO 类型的 MCP 服务（如 filesystem）
3. 点击连接 → 卡片变绿，显示工具列表
4. 取消勾选某个工具 → 工具被禁用
5. 在聊天页发送消息触发 Agent → 确认被禁用的工具不出现在工具列表中
6. HTTP_TOOL 类型的服务也正常显示和操作
