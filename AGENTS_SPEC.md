# Easy-Claw 多智能体协作规范

> 版本：1.0.0 · 适用于 Embabel Agent 1.5.0 · 最后更新 2026-08-18

## 目录
1. [架构概览](#1-架构概览)
2. [智能体清单](#2-智能体清单)
3. [Pre/Post 条件表](#3-prepost-条件表)
4. [运行机制](#4-运行机制)
5. [事件流](#5-事件流)
6. [失败降级策略](#6-失败降级策略)
7. [Plan 校验机制](#7-plan-校验机制)
8. [资源调度策略](#8-资源调度策略)
9. [最终汇总机制](#9-最终汇总机制)

---

## 1. 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    OrchestratorAgent                     │
│          (主智能体，为最终结果负责，最终汇总)             │
│                                                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │           GOAP Runner (Embabel 1.5.0)             │  │
│  │  plan_formulated → action_start → ... → completed │  │
│  └──────────────────────────────────────────────────┘  │
│                          │                              │
│    ┌─────┬─────┬─────┬────┴───┬─────┬─────┬─────┬─────┐ │
│    ▼     ▼     ▼     ▼        ▼     ▼     ▼     ▼     ▼ │
│  Code  File  Web Content Data Mail Inter DevOps Verifier │
│  Agent Agent Agent  Agent Agent Agent  Agent Agent Agent  │
│                                                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │              Subagent 工具调用                     │  │
│  │  (通过 @Action 的 pre/post 形成严格 GOAP 链条)    │  │
│  └──────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 核心设计原则

| 原则 | 说明 |
|------|------|
| **单一职责** | 每个子 Agent 只负责一个领域（代码/文件/内容/数据等） |
| **严格链条** | @Action 的 pre/post 约束确保执行顺序（先分析后修改，先修改后验证） |
| **最终负责** | OrchestratorAgent 在 GOAP 完成后做最终汇总，校验 goal 完成度 |
| **独立超时** | 子 Agent 90s 超时，根进程 5min 超时，LLM 调用 60s 超时 |
| **失败降级** | 子 Agent STUCK 不结束会话，由 OrchestratorAgent 兜底 |
| **Plan 校验** | plan_formulated 后校验 step.name 是否在 ActionRegistry 中 |

---

## 2. 智能体清单

| Agent | name | 领域 | 工具 | emoji |
|-------|------|------|------|-------|
| OrchestratorAgent | orchestrator-agent | 任务编排 | Subagent + MCP + Web + Math + File | 🎯 |
| CodeAgent | code-agent | 代码分析/修改 | FileTools + Math | 💻 |
| FileAgent | file-agent | 文件搜索/整理 | FileTools | 📁 |
| WebAgent | research-agent | 网络调研 | Web | 🔍 |
| ContentAgent | content-agent | 内容创作 | FileTools | ✍️ |
| DataAgent | data-agent | 数据分析 | FileTools | 📊 |
| MailAgent | mail-agent | 邮件处理 | Web + FileTools | 📧 |
| InteractionAgent | interaction-agent | 用户交互 | 无（仅对话） | 💬 |
| DevOpsAgent | devops-agent | CI/CD 部署 | FileTools | 🚀 |
| VerifierAgent | verifier-agent | 质量验证 | ShellTools（mvn compile/test） | ✅ |

---

## 3. Pre/Post 条件表

### CodeAgent（代码）
| Action | pre | post | readOnly | 说明 |
|--------|-----|------|----------|------|
| code-understand | — | code_understood | false | 理解需求（entry） |
| code-analyze | code_understood | code_analyzed | true | 分析代码（只读） |
| code-modify | code_analyzed | code_modified | false | 修改代码 |

### VerifierAgent（验证）
| Action | pre | post | readOnly | 说明 |
|--------|-----|------|----------|------|
| verify-compile | — | code_verified | false | mvn compile 验证 |
| verify-tests | — | tests_passed | false | mvn test 验证 |
| verify-report | — | verification_reported | false | 生成验证报告 |

### ContentAgent（内容）
| Action | pre | post | readOnly | 说明 |
|--------|-----|------|----------|------|
| outline | — | outline_ready | false | 创建大纲（entry） |
| draft | outline_ready | draft_ready | false | 撰写初稿 |
| revise | draft_ready | content_revised | false | 修改内容 |
| summarize | — | summary_ready | true | 总结提炼（独立 entry） |

### DataAgent（数据）
| Action | pre | post | readOnly | 说明 |
|--------|-----|------|----------|------|
| data-collect | — | data_collected | false | 收集数据（entry） |
| data-clean | data_collected | data_clean | false | 清洗数据 |
| data-analyze | data_clean | data_analyzed | false | 分析数据 |
| report | data_analyzed | report_ready | false | 生成报告 |

### MailAgent（邮件）
| Action | pre | post | readOnly | 说明 |
|--------|-----|------|----------|------|
| email-collect | — | emails_collected | true | 收集邮件（entry） |
| email-extract | emails_collected | info_extracted | false | 提取信息 |
| classify | info_extracted | emails_classified | false | 分类邮件 |
| send-email | emails_classified | email_sent | false | 发送邮件 |

### FileAgent（文件）
| Action | pre | post | readOnly | 说明 |
|--------|-----|------|----------|------|
| file-search | — | files_found | true | 搜索文件（entry） |
| file-organize | files_found | files_organized | false | 整理文件 |
| file-batch-edit | files_found | files_modified | false | 批量编辑 |

### DevOpsAgent（部署）
| Action | pre | post | readOnly | 说明 |
|--------|-----|------|----------|------|
| cicd-create | — | pipeline_created | false | 创建 CI/CD（entry） |
| deploy | pipeline_created | deployed | false | 部署服务 |
| monitor | deployed | monitored | false | 设置监控 |
| rollback | deployed | rollback_done | false | 回滚 |

### WebAgent（调研）
| Action | pre | post | readOnly | 说明 |
|--------|-----|------|----------|------|
| research | — | info_gathered | true | 网络调研（entry） |
| web-api-discovery | api_name_known, network_available | api_spec_found | true | API 发现 |

### InteractionAgent（交互）
| Action | pre | post | readOnly | 说明 |
|--------|-----|------|----------|------|
| annotate | — | feedback_received | true | 请求批注（entry） |
| confirm | — | plan_confirmed | true | 请求确认（entry） |

---

## 4. 运行机制

### 启动条件
- 用户发送消息 → `ChatWebSocketHandler.handleChat()`
- `AgentService.streamChat()` 创建 `AgentProcess`
- `ProcessOptionsFactory.forRoot(listener)` 配置根进程选项
- Embabel GOAP runner 启动，触发 `AgentProcessCreationEvent`

### 运行状态管理
| 状态 | 事件 | 说明 |
|------|------|------|
| CREATED | AgentProcessCreationEvent | 进程创建 |
| READY_TO_PLAN | AgentProcessReadyToPlanEvent | 就绪待规划 |
| PLANNING | AgentProcessPlanFormulatedEvent | 规划完成 |
| EXECUTING | ActionExecutionStartEvent | 执行 Action |
| PAUSED | AgentProcessPausedEvent | 暂停等待确认 |
| WAITING | AgentProcessWaitingEvent | 等待输入 |
| STUCK | AgentProcessStuckEvent | 卡住（超时） |
| COMPLETED | AgentProcessCompletedEvent | 成功完成 |
| FAILED | AgentProcessFailedEvent | 失败 |

### 终止机制
- **正常完成**：GOAP runner 达到 goal → COMPLETED → OrchestratorAgent 最终汇总
- **超时终止**：TimeBudgetPolicy 检查 → STUCK → 降级处理
- **LLM 超时**：LlmOptions timeout=60s → 自动失败
- **用户取消**：WebSocket 断开 → AgentService killChildren + cleanupSession

---

## 5. 事件流

```
用户消息
  │
  ▼
AgentProcessEventBridge.onProcessEvent()
  │
  ├─ AgentProcessCreationEvent → handleProcessCreation()
  ├─ AgentProcessReadyToPlanEvent → handleReadyToPlan()
  ├─ AgentProcessPlanFormulatedEvent → handlePlanFormulated()
  │     │
  │     ▼
  │   publish(PLAN_FORMULATED)
  │     │
  │     ▼
  │   AgentService.handleEvent(PLAN_FORMULATED)
  │     ├─ WebSocket 推送 plan 到前端
  │     └─ validatePlanActions() → 校验 step.name
  │
  ├─ ActionExecutionStartEvent → handleActionStart()
  │     │
  │     ▼
  │   publish(ACTION_START) → WebSocket 推送 agent_action
  │
  ├─ ToolCallRequestEvent → handleToolCallRequest()
  │     │
  │     ▼
  │   publish(TOOL_CALL_START) → WebSocket 推送 tool_call
  │
  ├─ LlmRequestEvent → handleLlmRequest()
  │     │
  │     ▼
  │   publish(LLM_CALL_START) → WebSocket 推送 llm_call
  │
  ├─ AgentProcessStuckEvent → handleStuck()
  │     │
  │     ▼
  │   publish(STUCK) → AgentService 降级处理
  │
  └─ AgentProcessCompletedEvent → handleCompleted()
        │
        ▼
      publish(COMPLETED) → OrchestratorAgent 最终汇总
```

---

## 6. 失败降级策略

### 子进程 STUCK（子 Agent 超时）
```
子 Agent STUCK (90s)
  │
  ├─ EventBridge.handleStuck() → publish(STUCK)
  │
  └─ AgentService.handleEvent(STUCK)
       ├─ 检测到 parentProcessId（子进程）
       ├─ 发送 subagent_lifecycle(failed) 到前端
       └─ return（不结束会话，让 GOAP runner 继续）
            │
            ▼
       GOAP runner 尝试其他路径或返回部分结果
            │
            ▼
       OrchestratorAgent.generateFinalSummary()
            └─ 兜底回答（说明未完成部分）
```

### 根进程 STUCK（主 Agent 超时）
```
根 Agent STUCK (5min)
  │
  └─ AgentService.handleEvent(STUCK)
       ├─ 无 parentProcessId（根进程）
       ├─ 发送 error 事件到前端
       ├─ killChildrenForSession()
       └─ cleanupSession() → 会话结束
```

### LLM 调用失败
```
LLM 调用超时 (60s)
  │
  └─ WorkspaceContextData.resolveLlmOptions() 设置了 timeout=60s
       └─ Embabel 框架自动抛出异常 → FAILED 事件
```

---

## 7. Plan 校验机制

### 触发时机
`AgentProcessEventBridge.handlePlanFormulated()` 发布 `PLAN_FORMULATED` 事件后。

### 校验逻辑
```java
AgentService.validatePlanActions(planData, ...)
  │
  ├─ 解析 plan.steps 中的每个 step.name
  ├─ 对比 ActionRegistry.getAll().keySet()
  │
  ├─ 全部有效 → log.info("Plan 校验通过")
  │
  └─ 存在无效 → 发送 error 事件
       └─ 包含: invalidSteps, validSteps, availableActions
            │
            ▼
       前端 PlanProgressBar 显示 ⚠ 警告
       ├─ 无效 step 标记为"未注册"（红色标签）
       └─ 展示可用 action 列表
```

### 前端展示
- PlanProgressBar 组件检测 `validation.valid === false`
- 显示橙色警告框，列出无效 step 和可用 action
- 无效 step 的图标变为 ⚠，名称添加删除线

---

## 8. 资源调度策略

### 超时配置
| 层级 | 超时 | 配置位置 |
|------|------|----------|
| LLM 调用 | 60s | `WorkspaceContextData.resolveLlmOptions()` |
| 子 Agent 进程 | 90s | `ProcessOptionsFactory.CHILD_MAX_TIME` |
| 根进程 | 5min | `ProcessOptionsFactory.ROOT_MAX_TIME` |
| Shell 命令（默认） | 180s | `ShellTools.DEFAULT_TIMEOUT_SEC` |
| Shell 命令（编译） | 300s | `ShellTools.COMPILE_TIMEOUT_SEC` |
| Shell 命令（测试） | 600s | `ShellTools.TEST_TIMEOUT_SEC` |

### Budget 配置
| 层级 | maxActions | maxCost (USD) |
|------|------------|---------------|
| 根进程 | 25 | 10.0 |
| 子进程 | 8 | 2.0 |

### 安全限制
- ShellTools 工作目录限定在 `workspaceRoot` 下
- ShellTools 输出截断 8000 字符
- ProcessOptionsFactory 使用 `TimeBudgetPolicy` 作为墙钟时间兜底

---

## 9. 最终汇总机制

### 触发时机
GOAP runner 返回结果后，OrchestratorAgent.chat() 末尾调用 `generateFinalSummary()`。

### 汇总流程
```
GOAP runner 返回结果 (goapResult)
  │
  ▼
generateFinalSummary(input, goapResult, ctx, llm)
  │
  ├─ 构造汇总 prompt:
  │    - 原始用户请求
  │    - GOAP 执行结果
  │    - 要求 LLM 校验完成度
  │
  ├─ 调用 LLM（使用相同模型）
  │
  ├─ 成功 → 返回汇总结果
  │    └─ 包含: 完成度校验、简洁总结、未完成说明
  │
  └─ 失败 → 降级返回 goapResult（原始结果）
       └─ log.warn("最终汇总失败")
```

### 汇总输出规范
1. 检查原始请求是否被完整解决
2. 已解决 → 简洁清晰的总结
3. 未完全解决 → 明确说明：
   - 已完成的部分
   - 未完成的部分和原因
   - 建议的后续步骤
4. 语气专业友好，Markdown 格式化

---

## 附录：关键文件索引

| 文件 | 说明 |
|------|------|
| `OrchestratorAgent.java` | 主智能体，GOAP 入口，最终汇总 |
| `AgentProcessEventBridge.java` | Embabel 事件 → 业务事件桥接 |
| `AgentService.java` | 会话管理、事件处理、Plan 校验、降级 |
| `ProcessOptionsFactory.java` | 根/子进程 ProcessOptions 工厂 |
| `ActionRegistry.java` | Action 元数据注册表 |
| `RoleAgentFactory.java` | @Agent Bean 扫描与部署 |
| `ShellTools.java` | 跨平台 Shell 工具（mvn compile/test） |
| `VerifierAgent.java` | 质量验证智能体 |
| `PlanProgressBar.tsx` | 前端 Plan 进度 + 校验警告组件 |
| `chatStore.ts` | 前端状态类型定义 |
