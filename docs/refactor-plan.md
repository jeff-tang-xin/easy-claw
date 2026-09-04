# Easy-Claw 主流程重构：终态架构 + 分阶段执行计划

> **文档状态**：执行中 | **适用范围**：AgentService 主流程（2300 行 god object）、ws 层、前端协议
> **依据来源**：2026-09-03 全量调研（#8-#22），所有论断均附文件:行号
> **进度更新**：2026-09-04，见下方「0. 执行进度」

---

## 0. 执行进度

> 本节是唯一的进度事实来源。每完成一步就更新这里，不要只依赖 commit 记录。

### 重新审视：构架决定，不是搬家

本轮重构的目标（黑板 #22/#23）是终态架构：
- **框架 AgentEvent 直接序列化推前端**（`@JsonTypeInfo` 自描述形状）
- **CustomEvent(name,value)** 承载应用层扩展
- **翻译层六件套整体删除**（handleEvent/handleNonDeltaEvent 等 ~500 行）

所以每个 middleware 不能凭空求"搬"，而要问：**"这个东西在终态架构里真的需要存在吗？"**

下面是逐项审视的结果。

---

### ✅ 已完成（2 项，判定为真）

| # | 内容 | commit | 判定依据 |
|---|------|--------|----------|
| 1 | **FileChangeMiddleware** 正式接管 | `7e37ade` | 终态架构中 CustomEvent 是 file_changed 的唯一合法通道。middleware 观察 ToolResultEndEvent 发 CustomEvent，不可替代 |
| 2 | **ToolFailGuard** 正式接管 | `7e37ade` | 同。连续失败检测是跨会话的横切逻辑，middleware 是唯一合适的位置 |

**累计**：AgentService 净删 117 行；11 个 middleware 单测全绿。

| # | 内容 | commit | 判定依据 |
|---|------|--------|----------|
| 3 | **Phase 2 小修：原子写 + 路径 helper 去重** | 未提交 | `WorkspaceFileLayout` 新增 `sessionStateDir(ws,userId,sessionId)` 收口 `state/{userId}/{sessionId}` 拼接；新增 `atomicWriteString(Path,String)` 临时文件 + `ATOMIC_MOVE` 防半写损坏；`AgentService.prepareSessionState` 改走原子写；`ChatController.history` 与 `SessionHistoryService.deleteSession` 共 4 处硬编码路径全部收口到 helper。编译 SUCCESS，测试基线确认 4 个既有 Failure 非回归引入 |

---

### ❌ 取消迁移（3 项，判定为假）

| # | 原计划 | 判定 | 依据 |
|---|--------|------|------|
| 3 | **ExceedMaxItersMiddleware** | **不需要** | ExceedMaxItersEvent 是框架原生 AgentEvent（`core/event/`），在终态架构中由 NativeEventSerializer 直接序列化，无需 middleware 介入。当前翻译层里的 `EXCEED_MAX_ITERS` 分支在 Phase 5 随翻译层一起删除即可 |
| 4 | **OrchestrationAuditMiddleware** | **不适合** | 黑板 #30 结论：emitOrchestrationAudit 依赖 `mainTurn` 标志（续问流不触发），middleware 每次 streamEvents 都会触发 onAgent，无法复现过滤逻辑。保留在 AgentService 中作为后处理步骤 |
| 5 | ~~**ContextStatusMiddleware**~~ | **用户决定：保留** | 见下方「保留项」 |

---

### ✅ Phase 3a / 3b / 事件基础设施（2026-09-04 追加）

| # | 内容 | commit | 判定依据 |
|---|------|--------|----------|
| 11 | **Phase 3a：handleEvent 会话级副作用收敛** | `4d4d953` | 三处副作用（含 `sessionOwners` 维护）抽到顶层 `SideEffectSink` 接口 + `AgentService` 内部 `SessionSideEffects` 实现 + `NOOP`，`handleEvent` 向纯翻译器靠近一步。6 files +159/−15，23/23 绿。前置 commit `7e3d292` 先建回归安全网 |
| 12 | **Phase 3b：CustomEvent 翻译桥迁出 AgentService**（方案 A：只解耦不碰协议） | `88e942f` | 新建 `agent/event/CustomEventTranslator`（无状态，构造注入 `ObjectMapper`）承接 `translateCustomEvent`，`AgentService` 用 static 共享实例委派。新增 `CustomEventTranslatorTest`(7)。**未动协议形状**——形状变更留给 P4/P5，避免一次 commit 同时改结构与协议 |
| 13 | **事件出口协议收敛为可替换实现** | `c25f6e0` | 新建 `agent/event/EventSerializer`（接口）+ `LegacyEventSerializer`（当前线上协议，逐字节复刻原信封），`ChatWebSocketHandler.sendJson` 改委派、其余逻辑零改动。**纯重构，输出字节级不变**。核心交付物是 `LegacyEventSerializerTest`(11)——legacy 协议的**可执行规格**，v2 靠它证明等价。变异验证（调换信封键序）11/11 全红后复原 |

**Phase 3b 范围修正**：计划 `:456` 称「应一并收口 `ChatWebSocketHandler` 内 17 处 `StreamEvent` 构造」。逐行核实后**不采纳**——那 17 处全是 `error`/`end`/`status`/`pendingInfo`/`stopped`，属**纯 WS 控制面事件，不经过 `handleEvent`、不需要翻译**，收口它们对 P4 无增益。真正需要统一的是**序列化出口**（全仓仅 `sendJson` 一处），已由 #13 完成。另：计划 `:442` 提及的 `EventStreamPublisher` 从未创建，`AgentService` 尾部 Javadoc 对它的引用是失效描述。

### ⛔ P4 / P5 按原计划不可执行（三项硬阻断，逐行核实）

1. ~~**SSE 通道未收敛**~~ → **已解除（`a7798c2`）**。原状：`ChatController` 的 `POST /api/chat/stream` 走 `safeSend` 交给 Spring `SseEmitter` 自行序列化，信封结构与 WS 不同，是 v2 的第二个出口。经四路取证（黑板 #79/#80）确认零消费者后整段删除，详见下方「Phase 3c」。
2. **`TranscriptRecorder` 绑定 legacy 形状**（计划完全未提）：`implements Consumer<StreamEvent>`，用 `private static final char SEP = '\u0001'` 解析 `subagent_text`，按 legacy type 字符串 switch 聚合成 `BoxMessage` 写 `transcript.jsonl`，被 `GET /api/chat/history` 直接读取——这是**已落盘的持久化格式**，不是内存结构。建议 P5 把「线上协议」与「转录持久化格式」显式分离，legacy 序列化器永久保留作转录内部格式，而非随协议一起删。
3. **6 个测试类反射硬绑私有内部结构**：`Class.forName("AgentService$ToolTrace")`、`getDeclaredMethod("handleEvent")`（`ConcurrentToolEventPairingTest`/`HandleEventSideEffectsTest`/`SubagentLoopGuardTest`/`ToolTraceStateTest`/`ParallelSubagentIsolationTest`）。删翻译层后**编译期查不出、运行时才炸**。且 `ToolTrace`/`handleEvent` 本身因 `guardSubagentLoop`、`closeInFlightTool` 依赖而不能直接删。

详见 `docs/refactor-plan-ph4-5-revised.md` 与黑板 #70–#78。

---

### ✅ Phase 3c：删除 SSE 死通道 + 附件校验迁移（2026-09-04）

| # | 内容 | commit | 判定依据 |
|---|------|--------|----------|
| 14 | **删除 `POST /api/chat/stream` 及其配套** | `a7798c2` | 解除硬阻断 #1。四路取证证明零消费者：① 前端 `api.ts` 的 `streamChat` 全仓零调用点，且已被 Vite tree-shaking 从构建产物 `index-CWQKO1AW.js` 中完全消除（findstr 无命中）；② 仓库无 .http/.rest/Postman/脚本引用；③ `README.md:36` 明文「WebSocket 流式输出（替代传统 SSE）」；④ 无 ChatController 测试。删除范围：`stream`/`confirm`/`pending`/`stop` 四端点 + `emitters` 表 + `safeSend`/`safeComplete` + `sseTimeoutMs` + 4 个请求 record + 死字段 `objectMapper`。WS 侧 confirm/pending/stop 分支功能等价（同一 `rejectUnknown` 白名单、同一 `resumeChat`/`allowTurn`/`allowPermanently`）。`/history`、`/status` 前端在用故保留 |

**该 commit 的关键不是删除，而是删除前的迁移**：`validateAttachments` 是 `maxAttachments`/`maxAttachmentBytes` 两配置项的**全仓唯一使用者**，而其唯一调用点在即将删除的死路径上（`stream():122`）；真正在用的 WS `handleChat` 只过滤 base64 空串，**无数量与体积上限**。纯删会让附件限额从「隐蔽失效」变成「彻底无防护」。故先把该校验逐行迁到 `ChatWebSocketHandler`（判定语义、错误文案、边累加边短路的行为均未改动），再删 SSE。教训见黑板 #84：**任何「删死代码」任务都要先查被删代码里有没有唯一在生效的校验/防护**。

净变更 3 files +44/−286。验证：真编译 web 126 主 + 56 测试 BUILD SUCCESS；10 个回归类 43 例全绿（条数与基线一致）；`tsc --noEmit` 零错误。

**遗留待决**：`sseTimeoutMinutes`(`AgentScopeProperties:333`) 与 `maxSseConnections`(`:345`) 现已成完全死配置（除自身 getter/setter 外零读取点），但暴露在 `application.yml:61/:66` 并绑定环境变量 `AGENTSCOPE_SSE_TIMEOUT_MINUTES`/`AGENTSCOPE_MAX_SSE_CONNECTIONS`，属用户可见配置面，未擅自删除（黑板 #86）。

---

### 📌 v2 协议地基（已取证，实现时直接引用）

读 vendored 框架源码确认的事实，不是推断：

| 事实 | 证据 | 对 v2 的含义 |
|------|------|-------------|
| 原生 `AgentEvent` 的自描述形状 | `AgentEvent.java:33-35`：`@JsonIgnoreProperties(ignoreUnknown=true)` + `@JsonInclude(NON_NULL)` + `@JsonTypeInfo(use=Id.NAME, include=As.PROPERTY, property="type")` | 判别字段名**就叫 `type`**，与 legacy 同名但取值风格不同（原生大写蛇形 `TEXT_BLOCK_DELTA`，legacy 小写 `text`）→ 前端可用同一 `raw.type` 分流后再判风格，**信封无需改动** |
| 注册了 29 个子类型 | `AgentEvent.java:36-71` `@JsonSubTypes` | 含 AGENT_START/END/RESULT、MODEL_CALL_*、TEXT\|THINKING\|DATA_BLOCK_*、TOOL_CALL_*、TOOL_RESULT_*、EXCEED_MAX_ITERS、REQUIRE_USER_CONFIRM、REQUEST_STOP、SUBAGENT_EXPOSED、HINT_BLOCK、ALL_TOOLS_DENIED、**CUSTOM** |
| 基类自带 `metadata` + 实例身份常量 | `AgentEvent.java:100-103` `@JsonInclude(NON_EMPTY) Map<String,Object> metadata`；`:86` `METADATA_PARENT_SESSION_ID`、`:96` `METADATA_AGENT_INSTANCE_ID` | **legacy 用 `\u0001` 打包 subagent 身份的问题，框架已有一等公民解法**。其 Javadoc(:89-95) 明说 `source` 对并行同名 subagent 完全相同，消费者必须靠 `agentInstanceId` 隔离 per-instance 状态——与本项目 `DeltaBatcher` 桶隔离、`ParallelSubagentIsolationTest` 保护的是同一问题 |
| `CustomEvent` 契约 | `CustomEvent.java:38-57`：仅 `name` + `value:Map`，value 为 null 时兜底 `Map.of()` | 应用层扩展 type（file_changed/blackboard/context/status/pending_info/auto_confirm，原生 29 类型中无对应）统一走此通道，**无需新增自定义事件类、无需改 vendored 源码** |
| 未知 name 静默跳过是**框架规定的前端义务** | `CustomEvent.java:28-29` Javadoc 明文 "Front-end implementations should handle unknown getName() values gracefully — skip with no error" | 前端 v2 解析器必须实现未知 name 容错，这不是可选优化 |

**由此得出的 v2 设计要点**：① subagent 类事件改用「原生事件 + `metadata.agentInstanceId`」结构化承载，废弃 `\u0001` 字符串打包，同时消除 `TranscriptRecorder` 的 `SEP` 与线上协议的隐式耦合；② 原生自带 `ignoreUnknown` 的前向兼容性优于自研 `StreamEvent`。

---

### 🔧 应删除，而非迁移（根因修复，净收益最大）

`prepareSessionState`（`:882-1092`，约 220 行）逐动作复核结果：**真正属于业务、框架给不了的只有 `applyPlanFile` 那十来行**，其余全是三类自伤。三个根因修掉后约 **150–170 行可删**，连带 `needRebuild → rebuildAgent` 重建链路也能去掉。

| # | 净化动作 | 行号(约) | 它在防什么 | 判定 | 依据 |
|---|----------|----------|-----------|------|------|
| 6 | 裸读写 `agent_state.json` | `:886-905` | 无——只是拿状态的手段 | 🔴 **绕过框架，可删** | 框架已装配 `JsonFileAgentStateStore`（`WorkspaceAgentBuilder:214`），`AgentStateStore:61` 提供 `getVersioned/saveIfVersion` 乐观并发。现有裸写**无版本校验**，与 Agent 自身 `saveStateToSession` 并发时后写覆盖先写。全应用 4 处同样裸写 |
| 7 | 清理 ASKING 消息（`removeAskingMessages`） | `:918-935` | 刷新/离页后 ASKING 被持久化，新消息抛 `Agent is paused for HITL confirmation` | **已修复** | **不再删除 ASKING 消息**。改为 `purgePollutedContext` 统一为悬空 tool_call 补配对：ASKING→DENIED，RUNNING→INTERRUPTED。其要求 ToolUseBlock 结构保留（避免孤儿结果），又因 pending 集归零走正常 coreAgent() 路径（`ReActAgent:1751-1758`）。删除 `removeAskingMessages` 约 25 行，连带 `WorkspaceAgentBuilder:230` 的 `enablePendingToolRecovery(false)` 恢复为默认开启。`fe024b5` |
| 8 | 孤儿 `ToolResultBlock` 清理 | `:1006-1013` | 方舟等 OpenAI 兼容 API 严校验配对，孤儿 result 报 `tool_call_id is not found` | 🟡 **随 #7 消失，需实测** | 框架内无等价清理入口，若污染真会产生则必须留。但它**唯一产生源就是 #7 删 assistant 消息**——框架自身写上下文时 tool_use/tool_result 总是配对的。#7 改掉后需实测确认 |
| 9 | 悬空 tool_call 补配对 | `:1029-1057` | 同上 | **已修复** | 保留该机制（不再可删），增加 ASKING/RUNNING 双态区分，补配对结果语义与框架一致。`fe024b5` |
| 10 | 空 user 消息清理 | `:993-997`+`:1076-1086` | 早期 `autoConfirmResume` 注入 `content=""`，模型见连续空输入输出困惑回复 | 🟢 **根因已修，降级为一次性旧数据兼容** | 根因已在 `resumeChat:1155` 修掉 |

**根因链条**（已修复）：`removeAskingMessages` 删含 ASKING 的 assistant 消息 → 制造孤儿 ToolUseBlock → 不得不 `enablePendingToolRecovery(false)` 防补配对 → 自己手写 `purgePollutedContext` 补回来。删除 `removeAskingMessages` 后链条瓦解，框架 pending-tool-recovery 回归正常工作。

---

### 🧩 需重新设计（2 项，非机械迁移）

| # | 内容 | 现状 | 方向 |
|---|------|------|------|
| 8 | **SubagentLoopGuard** | 骨架已建，未启用 | 子 Agent 循环截断是合法的 middleware 职责。但需重新审视阈值逻辑——当前是硬编码，应改为可配置 |
| 9 | **TranscriptRecordingMiddleware** | 空壳 | 转录的核心问题不是"用 middleware 还是不用"，而是"转录什么、写到哪里"。需先明确需求再设计 |

---

### 📐 消费侧问题：translateCustomEvent 是临时桥

当前的做法是：
```
middleware 发 CustomEvent → AgentService.handleNonDeltaEvent(CUSTOM) → translateCustomEvent → StreamEvent
```

这在终态架构中是一个**两步绕路**，因为终态是：
```
middleware 发 CustomEvent → NativeEventSerializer 直接序列化 → JSON
```

但 LegacyEventSerializer（Phase 3 建）还不存在，所以当前必须有一个临时桥。正确做法：
- **保留** `translateCustomEvent` 作为独立方法，与 `handleNonDeltaEvent` 同文件但物理分离
- **明确标记**为 `// TEMPORARY BRIDGE: will be replaced by LegacyEventSerializer in Phase 3`
- Phase 3 建 LegacyEventSerializer 时，把这个映射逻辑迁过去，然后删掉临时桥

**当前 translateCustomEvent 不应继续扩展**——后续新 middleware 加自定义事件时，应直接发 CustomEvent，等待 Phase 3 的序列化器处理，而非继续往临时桥里加 case。

---

### 🗺 实际情况

```
AgentService 职责分类（黑板 #16）：
  A（应归还框架/middleware）：④上下文净化 ⑤自建流式合批 ⑥事件翻译 ⑦编排审计
  B（真业务）：①会话生命周期 ②权限同步 ③用户消息构建

终态分配：
  middleware 层：FileChange ✅  ToolFailGuard ✅  SubagentLoopGuard（待定）  TranscriptRecording（待定）
  AgentService 保留：NO_REPLY fallback  DeltaBatcher（协议无关化）  OrchestrationAudit  会话生命周期  权限同步  用户消息构建
  删除（根因修复后）：prepareSessionState 的净化逻辑  purgePollutedContext 的 tool_call 补丁
  Phase 3/5 删除：整个翻译层（handleEvent/handleNonDeltaEvent ~500 行）
```

---

### 下一阶段优先级

| 优先级 | 内容 | 理由 |
|--------|------|------|
| **P0** | 端到端验证已迁移的两条 | 只有单测背书，未在真实链路跑过 |
| ~~**P1**~~ | ~~修 ASKING 自伤循环（表中 #7→#9→#8→#6）~~ | **已完成 `fe024b5`**。实际净删约 25 行（非预估 150–170 行）：#9 补配对经核实是**必需机制**而非冗余（框架的 `maybePatchPendingToolCalls` 显式跳过 ASKING 态，覆盖不到刷新场景），故保留并增强；`needRebuild → rebuildAgent` 因 purge 仍会改上下文而**保留**。真实收益是自伤链瓦解 + `enablePendingToolRecovery` 回归默认 |
| **P2** | 重设计 SubagentLoopGuard + TranscriptRecording | 需先明确需求 |
| **P3** | Phase 3 协议层（EventStreamPublisher + 双序列化器） | 翻译层删除的前提；`translateCustomEvent` 临时桥届时迁入 LegacyEventSerializer |

**注**：P1 比继续做 middleware 收益大一个数量级——middleware 迁移两条只净删 117 行，而 P1 一条链路就能删 150+ 行，且修的是真 bug（并发覆盖写、HITL 恢复走偏门）。

### 明确不做

- 5 个既有测试失败（`SubagentScenarioIsolationTest` 等）：源自 `50e232e` 白名单未同步，与本重构无关。
- `ExceedMaxItersMiddleware`：框架原生事件，无需 middleware。
- `OrchestrationAuditMiddleware`：mainTurn 依赖无法在 middleware 中复现，保留在 AgentService。
- `ContextStatusMiddleware`：有事件洪水 bug，且功能价值存疑，待定。

---

### 📌 调研中发现的现存 bug（与重构无关，独立记录）

| bug | 位置 | 描述 | 建议 |
|-----|------|------|------|
| **并行同名子 Agent 串桶** | `AgentService.java:1184-1185` | `DeltaBatcher` 的 `subagentBufs` / `subagentThinkBufs` 按**子 Agent 名字字符串**分桶。同一类型的多个并行子 Agent（如同时派两个 `coder`）名字相同，它们的流式输出会混进同一个缓冲区，输出交错错乱 | 分桶键改用框架事件 metadata 里的 `agentInstanceId`（黑板 #21：框架自己就用这个键区分并行同名实例）。DeltaCoalescer 协议无关化时一并修 |

**顺手可做的小清理**（影响半径仅限 DeltaBatcher 内部，回滚成本近零）：
`subagentBufs` 与 `subagentThinkBufs` 两个 map 结构相同、生命周期一致，`flush()` 里 `:1249-1265` 两段代码逐字重复。
可合并为 `Map<BucketKey, StringBuilder>`，`BucketKey` 携带 `(agentInstanceId, channel)`——正好和上面的 bug 修复是同一处改动。

---

## 1. 终态架构

### 1.1 数据流（文字图）

```
┌──────────────────────────────────────────────────────────────────┐
│  ReActAgent (框架)                                              │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Middleware Chain (洋葱模型, order() 排序)                  │  │
│  │  ┌─ 1. SubagentLoopGuard (#2111) ─────────────────┐      │  │
│  │  │  onActing: 拦截子Agent循环, 超限截断 → CustomEvent  │      │  │
│  │  └────────────────────────────────────────────────┘      │  │
│  │  ┌─ 2. ToolFailGuard (#1990) ─────────────────────┐      │  │
│  │  │  onToolResult: 连续失败检测 → 终止/降级            │      │  │
│  │  └────────────────────────────────────────────────┘      │  │
│  │  ┌─ 3. TranscriptRecorder (#1598) ────────────────┐      │  │
│  │  │  onAgent/onReasoning/onActing: 全量转录写入       │      │  │
│  │  └────────────────────────────────────────────────┘      │  │
│  │  ┌─ 4. ContextStatusMiddleware (#2252) ───────────┐      │  │
│  │  │  onModelCall: 上下文状态 → CustomEvent            │      │  │
│  │  └────────────────────────────────────────────────┘      │  │
│  │  ┌─ 5. OrchestrationAuditMiddleware (#2205) ──────┐      │  │
│  │  │  onToolCall: 编排审计 → CustomEvent               │      │  │
│  │  └────────────────────────────────────────────────┘      │  │
│  │  ┌─ 6. FileChangeMiddleware (#file_changed) ──────┐      │  │
│  │  │  onToolResult: 写类工具→ CustomEvent(name="file_changed")│  │
│  │  └────────────────────────────────────────────────┘      │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                   │                              │
│                    AgentEvent 流 (Flux<AgentEvent>)               │
│                                   │                              │
│                                   ▼                              │
│  ┌─ AgentEventPublisher ─────────────────────────────────────┐  │
│  │  doOnNext → 事件序列化 → 前端协议 (JSON)                     │  │
│  │  CustomEvent(name,value) 直接透传 (value 即原有 content)   │  │
│  │  ToolResultBlock → 结构化 error 态 (ToolResultBlock.error())│  │
│  │  ExceedMaxItersEvent → 携带 GenerateReason 标记            │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                   │                              │
│                            WebSocket                             │
│                                   │                              │
│                                   ▼                              │
│  ┌─ ChatWebSocketHandler (保留, 瘦身) ────────────────────────┐  │
│  │  sendJson(sessionId, String)  ← 直接序列化后的 JSON 字符串   │  │
│  │  PendingBuffer, flushPendingEvents, 重连机制 保留不变        │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                   │                              │
│                            +--------------------------------+   │
│                            | 前端 chatStore + ChatPage       |   │
│                            | DATA_EVENT_TYPES 适配新 type 值  |   │
│                            | 32ms 合批 (ChatPage:1047) 保留   |   │
│                            +--------------------------------+   │
└──────────────────────────────────────────────────────────────────┘
```

### 1.2 新协议定义

**终态协议**：框架 `AgentEvent` 直接序列化，经 `@JsonTypeInfo(property="type")` 携带 type 字段（AgentEvent.java:34-71），与现有 `StreamEvent{type,content,toolCallId}` 形状同构。`CustomEvent(name,value)` 承载应用层扩展事件（CustomEvent.java:21-37 Javadoc）。

**JSON 示例**（终态，与现有 StreamEvent 形状同构，type 取值改名）：

```json
// 正文增量（原 type="text"，终态 type="TEXT_BLOCK_DELTA"）
{"type":"TEXT_BLOCK_DELTA","content":"你好","metadata":{"source":"main"}}

// 推理增量（原 type="reasoning"，终态 type="THINKING_BLOCK_DELTA"）
{"type":"THINKING_BLOCK_DELTA","content":"让我想想...","metadata":{"source":"main"}}

// 工具调用开始（原 type="tool" + toolCallId，终态 type="TOOL_CALL_BLOCK_START"）
{"type":"TOOL_CALL_BLOCK_START","content":"write_file","toolCallId":"call_xxx","metadata":{"id":"evt_1","createdAt":1234567890}}

// 工具调用结果（原 type="tool_result"，终态 type="TOOL_RESULT_BLOCK_END"）
{"type":"TOOL_RESULT_BLOCK_END","content":"文件已写入","toolCallId":"call_xxx","resultState":"SUCCESS"}

// 工具错误（原 type="tool_result" 内容含 "Error: " 前缀，终态结构化）
{"type":"TOOL_RESULT_BLOCK_END","content":"[ERROR] 文件不存在","toolCallId":"call_xxx","resultState":"ERROR"}

// 子Agent 输出（原 type="subagent_text" + \\u0001 编码，终态分拆）
{"type":"SUBAGENT","content":"child_agent","metadata":{"source":"subagent"}}
{"type":"TEXT_BLOCK_DELTA","content":"子Agent输出","metadata":{"source":"subagent:child_agent"}}

// 应用层自定义事件（终态，替代原有 file_changed/blackboard/context_status/orchestration_audit）
{"type":"CUSTOM","name":"file_changed","value":"src/main.js"}
{"type":"CUSTOM","name":"blackboard","value":"{\"seq\":1,\"type\":\"finding\",\"author\":\"sub\",\"content\":\"发现根因\"}"}
{"type":"CUSTOM","name":"context_status","value":"{\"msgCount\":42,\"tokenEstimate\":8500,\"compressed\":false}"}
{"type":"CUSTOM","name":"orchestration_audit","value":"{\"dispatchTarget\":\"sub\",\"reason\":\"...\"}"}

// 里程碑事件
{"type":"AGENT_END","id":"evt_1"}
{"type":"EXCEED_MAX_ITERS","id":"evt_1"}
```

**协议兼容窗口设计**（Phase 3–5）：

1. WS register 消息携带 `protocolVersion` 字段（默认 `"legacy"`，可选 `"v2"`）
2. `ChatWebSocketHandler.sendJson` 根据 connection 的 protocolVersion 选择序列化器：
   - `"legacy"` → `LegacyEventSerializer`（保持现有 StreamEvent 形状，保留现有 20+ type 取值）
   - `"v2"` → `NativeEventSerializer`（框架 AgentEvent 直接序列化 + CustomEvent 透传）
3. 前端按 `protocolVersion` 适配：`"legacy"` 走现有 switch，`"v2"` 走新 switch
4. Phase 5 移除 `"legacy"` 序列化器及前端对应分支

### 1.3 变更清单（逐类）

#### 删除（11 个类/方法组）

| 类/方法 | 文件:行号 | 删除原因 |
|---------|----------|----------|
| `StreamEvent` record | `agent/domain/StreamEvent.java:29` | 被框架 AgentEvent 直接序列化替代 |
| `AgentService.handleEvent` | `AgentService.java:1847-2034` | 翻译层，框架事件流直达 ws |
| `AgentService.handleNonDeltaEvent` | `AgentService.java:1899-1946` | 同上 |
| `AgentService.handleSubagentEvent` | `AgentService.java:1598-1650` | 同上 |
| `AgentService.inferToolResultState` | `AgentService.java:1793-1800` | 框架 ToolResultBlock.error() 自带结构化错误态 |
| `AgentService.ToolTrace` | `AgentService.java:1530-1596` | 框架事件内含 id/createdAt/source |
| `AgentService.ToolCallSlot` | `AgentService.java:1510-1528` | 同上 |
| `AgentService.DeltaBatcher` | `AgentService.java:1178-1310` | 被 `DeltaCoalescer`（协议无关，见下文）替代 |
| `AgentService.startStream` 中事件翻译逻辑 | `AgentService.java:1302-1457` | 翻译逻辑移至 middleware |
| `AgentStateBoxReader` | `agent/AgentStateBoxReader.java` | 统一走框架 AgentStateStore API |
| `AgentService.prepareSessionState` 中裸读写 `agent_state.json` | `AgentService.java:882-1092` | 统一走框架 AgentStateStore API |

#### 新建（7 个类）

| 类 | 用途 | 关键设计 |
|----|------|---------|
| `middleware/SubagentLoopGuard.java` | 子Agent循环护栏（#2111），检查 ExceedMaxItersEvent → CustomEvent | 不依赖 vendored AgentSpawnTool |
| `middleware/ToolFailGuard.java` | 工具连续失败检测（#1990-2005），触发终止/降级 | 植入 MiddlewareBase.onToolResult |
| `middleware/ContextStatusMiddleware.java` | 上下文状态推送（#2252），onModelCall 后 emit CustomEvent | 仅当状态变化时推送，不做每轮全量 |
| `middleware/OrchestrationAuditMiddleware.java` | 编排审计推送（#2205），onToolCall 后 emit CustomEvent | 跟踪 dispatchTarget |
| `middleware/FileChangeMiddleware.java` | 写类工具完成 → CustomEvent("file_changed") | 检查 ToolResultBlock 含 write_file/edit_file |
| `middleware/TranscriptRecordingMiddleware.java` | 全量转录写入（替代 TranscriptRecorder） | 比 TranscriptRecorder 更轻量，直接读框架事件 |
| `event/DeltaCoalescer.java` | 协议无关 delta 合并：8ms burst + 60 字符 cap | 输入 AgentEvent，输出 AgentEvent（不翻译），不依赖 StreamEvent |
| `event/EventStreamPublisher.java` | 统一流式发布：AgentEvent → 序列化 → ws JSON | 持 protocolVersion 选择序列化器 |
| `event/NativeEventSerializer.java` | 框架 AgentEvent → JSON（新协议） | 直接 ObjectMapper.writeValueAsString |
| `event/LegacyEventSerializer.java` | 框架 AgentEvent → StreamEvent 形状 JSON（兼容旧前端） | 保持现有 type 取值 + content 编码 |
| ~`event/SubagentTruncationMiddleware.java`~ | 见 Phase 1 说明 | 整合到 `SubagentLoopGuard` |

#### 保留但瘦身（5 个类）

| 类 | 当前行数 | 预计剩余 | 瘦身内容 |
|----|---------|---------|----------|
| `AgentService` | 2325 | ~800 | 删除事件翻译、合批、状态裸读写、护栏逻辑，保留会话生命周期 + 权限同步 + 用户消息构建 |
| `ChatWebSocketHandler` | 471 | ~400 | 删除 sendJson(StreamEvent) 重载，改为 sendJson(String)；保留 PendingBuffer/flush/重连 |
| `WorkspaceAgentBuilder` | 591 | ~620 | 新增 middleware 链装配 + DeltaCoalescer 装配 + EventStreamPublisher 装配 |
| `TranscriptRecorder` | ~200 | 删除 | 被 TranscriptRecordingMiddleware 替代 |
| `SessionRegistry` | ~300 | ~300 | 不变，仅用法由 AgentService 直调改为 middleware 回调 |

### 1.4 AgentService 重构后预期

**职责**：会话生命周期管理（`scheduleGraceDispose`、`recoverStuckAgent`、`touchSession`）+ 权限同步（`syncRulesToLiveSessions`）+ 用户消息构建（`buildUserMessage`、`sniffImageMime`、`saveAttachment`）+ 入口 `chat()` 方法（`AgentService.java:2286-2320`）

**规模**：~800 行（从 2325 行 ~ -65%）

**删除的方法**：`prepareSessionState`、`handleEvent`、`handleNonDeltaEvent`、`handleSubagentEvent`、`inferToolResultState`、`guardSubagentLoop`、`emitOrchestrationAudit`、`emitContextStatus`、`startStream`（翻译部分）、`DeltaBatcher` 内部类、`ToolTrace` 内部类、`ToolCallSlot` 内部类

---

## 2. 职责归类总表

### 归类说明

- **A类（归还框架）**：框架已提供等价能力，应用层绕过框架手写导致重复造轮子或 bug
- **B类（真业务）**：应用层特有逻辑，框架不关心，必须保留
- **C类（历史原因需保留）**：框架能力残缺或不适配，但短期内不可改动框架（vendored `agentscope-java` 2.0.3-SNAPSHOT）

| 当前逻辑块 | 文件:行号 | 归类 | 终态去向 | 依据 |
|-----------|----------|------|---------|------|
| 事件翻译：AgentEvent → StreamEvent | `AgentService.java:1847-2034` | **A** | 删除，框架事件直达 ws | #22：框架事件带 @JsonTypeInfo，形状同构 |
| 子Agent循环护栏 | `AgentService.java:2111-2150` | **A** | → `SubagentLoopGuard` middleware | #15：MiddlewareBase.onAgent 五拦截点 |
| 工具连续失败检测 | `AgentService.java:1990-2005` | **A** | → `ToolFailGuard` middleware | #15：MiddlewareBase.onToolResult |
| 上下文状态推送 | `AgentService.java:2252-2280` | **A** | → `ContextStatusMiddleware` | #22：CustomEvent 官方扩展口 |
| 编排审计推送 | `AgentService.java:2205-2250` | **A** | → `OrchestrationAuditMiddleware` | #22：CustomEvent 官方扩展口 |
| 文件变更推送 | `AgentService.java` (file_changed) | **A** | → `FileChangeMiddleware` | #22：CustomEvent 官方扩展口 |
| 流式 delta 合批（8ms burst） | `AgentService.java:1178-1310` | **A** | → `DeltaCoalescer`（协议无关） | 框架事件流仍需要 delta 合并，但不应在翻译层做 |
| 状态存储裸读写 agent_state.json | `AgentService.java:882-1092` | **A** | 统一走框架 AgentStateStore API | #19：4 处绕过且无版本协调 |
| 状态存储裸读写 | `SessionTranscriptStore.java:161` | **A** | 统一走框架 AgentStateStore API | #19：绕过框架直接 I/O |
| 状态存储裸读写 | `AgentStateBoxReader.java:30` | **A** | 删除，统一走框架 | #19 |
| 状态存储裸读写 | `ChatController.java:239` | **A** | 删除，统一走框架 | #19 |
| 全量转录 | `TranscriptRecorder.java` | **A** | → `TranscriptRecordingMiddleware` | #15：MiddlewareBase 五拦截点 |
| 框架反射注册 MCP 工具 | `AgentFactory.java` | **A** | 替换为 `toolkit.registration().mcpClient().enableTools().apply()` | #9：反射可被官方 fluent API 替代 |
| 会话生命周期管理 | `AgentService.java:348-494` | **B** | 保留在 AgentService（瘦身后） | 框架不管理会话超时/卡死恢复 |
| 权限同步 | `AgentService.java:161-248` | **B** | 保留在 AgentService | 框架权限模型不匹配应用层需求 |
| 用户消息构建+附件 | `AgentService.java:769-880` | **B** | 保留在 AgentService | 框架不关心文件上传/附件 |
| 入口 chat() | `AgentService.java:2286-2320` | **B** | 保留在 AgentService | 应用层入口 |
| WS 连接管理+重连 | `ChatWebSocketHandler.java:51-64` | **B** | 保留（瘦身） | 框架不管理 WS 连接 |
| 前端 32ms 合批+rAF | `ChatPage.tsx:1047` | **B** | 保留不变 | 前端 UI 帧率，框架不关心 |
| 前端 file_changed 250ms 防抖 | `ChatPage.tsx:1021` | **B** | 保留不变 | 前端 UI 优化 |
| 前端停止 3s grace | `ChatPage.tsx:1018` | **B** | 保留不变 | 业务决策 |
| 前端重连拉全量 | `chatSocket.ts` | **B** | 保留不变 | 业务决策 |
| 子Agent 结果截断检测 | `AgentSpawnTool.java:975-978` | **C** | 保留（通过 middleware 感知 ExceedMaxItersEvent 解决） | 框架官方 SubAgentTool 同样未处理截断（#13），且 AgentSpawnTool 是 vendored 代码（fork 成本高） |
| tool_call_id 丢失规避 | `WorkspaceAgentBuilder.java:224` (`enablePendingToolRecovery(false)`) | **C** | 保留，但修复根因（见 Phase 6） | 根因是 clearStaleConfirmation 破坏框架不变式（#8），框架 2.0.3 无补丁前不可贸然开回 |
| OrchestrationAuditVerifier | `orchestrator/OrchestrationAuditVerifier.java` | **C** | 保留，逻辑移植到 middleware 后删除原文件 | 核心编排审核逻辑需保留，但执行位置从事件流移动到 middleware |
| OrchestrationPromptBuilder | `orchestrator/OrchestrationPromptBuilder.java` | **C** | 保留 | 静默降级防护，框架不提供 |

---

## 3. 分阶段执行计划

### 重要设计纠错

**planner 初稿将 ToolFailGuard / FileChangeMiddleware / OrchestrationAuditMiddleware 挂在 `onToolResult` / `onToolCall` 钩子上——这些钩子不存在。** 亲验 `MiddlewareBase.java` 完整接口，只有 5 个方法：

| 钩子 | 签名 | 用途 |
|------|------|------|
| `onAgent` | `Flux<AgentEvent>` | 拦截整个 Agent 调用 |
| `onReasoning` | `Flux<AgentEvent>` | 拦截推理/模型调用阶段 |
| `onActing` | `Flux<AgentEvent>` | 拦截工具调用执行阶段 |
| `onModelCall` | `Flux<AgentEvent>` | 拦截裸模型 API 调用 |
| `onSystemPrompt` | `Mono<String>` | 转换系统提示词（管道模式） |

**修正**：工具相关横切逻辑一律挂 `onActing`，在返回的 `Flux<AgentEvent>` 上 `doOnNext` 观察 `ToolResultEndEvent` / `ToolCallEndEvent` 实现功能。`ContextStatusMiddleware` 挂 `onModelCall`（Javadoc 明确说可改写返回流中的 `TextBlockDeltaEvent`）。`SubagentLoopGuard` 挂 `onAgent`（拦截整个调用做迭代计数）。`TranscriptRecordingMiddleware` 分拆：`onReasoning` 录推理输入 + `onActing` 录工具结果。

---

### Phase 1：新建 middleware 与事件基础设施（零行为变更）

**目标**：创建所有新类并在 `WorkspaceAgentBuilder` 中装配，但**不启用**（不替换现有逻辑），确保编译通过，为后续阶段提供挂载点。

**改动文件**：

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `event/DeltaCoalescer.java` | 协议无关的 delta 合并器，输入 AgentEvent，输出 AgentEvent（不翻译）。**判定：真需要**——黑板 #32/#33 已穷尽搜索框架 core，无等价物；ContentAccumulator 是构建消息对象的累加器不是运输层合批器，StreamOptions 只有 incremental/cumulative 无定时合批，publishEvent 直接 sink.next 无缓冲。与前端 32ms 合批**正交不冗余**（服务端减帧数，客户端减渲染次数），删掉会让每个 2 字符 delta 独立成帧 |
| 新建 | `event/EventStreamPublisher.java` | 统一流式发布，持 protocolVersion 选序列化器 |
| 新建 | `event/NativeEventSerializer.java` | 框架 AgentEvent → JSON（ObjectMapper.writeValueAsString） |
| 新建 | `event/LegacyEventSerializer.java` | 框架 AgentEvent → StreamEvent 形状 JSON（兼容旧前端）。**注**：`AgentService.translateCustomEvent` 的映射逻辑届时迁入此处，然后删掉那座临时桥 |
| 待定 | `middleware/SubagentLoopGuard.java` | 挂 onAgent，子 Agent 循环计数 + 超限截断。**需重新设计**，见 0 节 #8 |
| ✅ 完成 | `middleware/ToolFailGuard.java` | **已正式接管**（`7e37ade`）。挂 onActing，观察 ToolResultEndEvent 做连续失败检测 |
| ❌ 取消 | ~~`middleware/ContextStatusMiddleware.java`~~ | **有事件洪水真 bug 且功能价值存疑**，见 0 节 #5 |
| ❌ 取消 | ~~`middleware/OrchestrationAuditMiddleware.java`~~ | **mainTurn 过滤无法在 middleware 复现**，保留在 AgentService，见 0 节 #4 |
| ✅ 完成 | `middleware/FileChangeMiddleware.java` | **已正式接管**（`7e37ade`）。挂 onActing，观察写类工具结果推 CustomEvent("file_changed") |
| 待定 | `middleware/TranscriptRecordingMiddleware.java` | 挂 onReasoning + onActing，全量转录。**当前空壳，需先明确需求**，见 0 节 #9 |
| 修改 | `WorkspaceAgentBuilder.java` | 装配 middleware 链 + EventStreamPublisher（不激活） |
| 修改 | `pom.xml` (web) | 确保 middleware 包路径被扫描 |

**验证方式**：
```
mvn -pl web -am compile -DskipTests -o
```
新类编译通过即可。此阶段不涉及运行时行为变更，无需运行测试。

**回滚方式**：`git checkout -- .` 恢复所有新文件。

**依赖**：无

---

### Phase 2：统一状态存储

**目标**：消除 4 处绕过框架 `AgentStateStore` 裸读写 `agent_state.json`，统一走 `AgentStateStore` API（含乐观并发 CAS）。

**改动文件**：

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `AgentService.java:882-1092` `prepareSessionState` | 删除裸读写，改用 `stateStore.save()` / `stateStore.get()` |
| 修改 | `SessionTranscriptStore.java:161` `seedIfAbsent` | 同上 |
| 删除 | `AgentStateBoxReader.java` | 不再需要，功能合并到 stateStore 调用 |
| 修改 | `ChatController.java:239` | 改用 `stateStore` |
| 修改 | `WorkspaceAgentBuilder.java` | 确保 `JsonFileAgentStateStore` 装配正确，暴露 bean 供其他组件注入 |

**验证方式**：
```
mvn -pl web -am compile -DskipTests -o
```
然后手工验证：启动应用，创建会话，发送消息，确认 agent_state.json 能正常读写且无 shell 文件。

**回滚方式**：`git revert HEAD` 回到 Phase 1 状态。

**依赖**：Phase 1

---

### Phase 3：协议层——新建 EventStreamPublisher + 双序列化器

> **⚠️ 2026-09-03 实证修正：本阶段原计划的前提不成立，已拆为 3a → 3b 两步执行。**
>
> 原计划假设 `handleEvent` 是纯翻译器，可整体替换为 `publish(event)`。实读
> `AgentService:1793-1975` 后确认它同时承担 **4 类业务副作用**，直接替换会让它们
> 静默丢失（编译通过、线上失效）：
>
> | 事件 | 副作用 | 丢失后果 |
> |------|--------|----------|
> | `TEXT/THINKING_BLOCK_DELTA` | 写 `trace.hasText/hasOutput` + `appendReply()` | `AGENT_END` 的 NO_REPLY 自动续问兜底失效 |
> | `TOOL_CALL_END` | `guardSubagentLoop()`（内含 `agent.interrupt()`） | 子 Agent 循环调度不再被拦截 |
> | `TOOL_RESULT_END` | `markSubagentDelivered()` + `emitBlackboardIfAppend()` + 移除 `trace.calls` 在途条目 | 循环防护计数错乱、黑板 UI 不更新、`closeInFlightTool` 补收尾失效 |
> | `REQUIRE_USER_CONFIRM` | `sessions.armPendingConfirm(...)` 登记待确认 + 超时截止 | HITL 生命周期断裂，SSE 连接永久常驻（OOM 路径） |
>
> 另有一处**不可改的短路顺序**（黑板 #53 实测）：`handleEvent` 入口先判
> `fromSubagent`（`source` 含 `/`）再进 `switch`，子 Agent 事件完全不碰主回合 `trace`。
> 若剥离时打乱此顺序，子 Agent 的文本会把主回合 `hasOutput` 置真，导致主 Agent
> 真正无回复时兜底续问失效。`RequireUserConfirmEvent` 被显式排除在隔离之外
> （HITL 由主控统一弹窗管理）。
>
> **拆分后的执行顺序**：
> - **Phase 3a（先做，零协议变更）**：把上述 4 类副作用从 `handleEvent` 剥离为独立的
>   `handleEventSideEffects(...)`，由 `startStream` 的 `doOnNext` 在翻译之后调用。
>   `handleEvent` 降级为纯翻译器（只保留 `trace` 内部状态突变与 `onEvent.accept`）。
>   行为必须完全不变，以测试基线不变作为验收。
> - **Phase 3b（后做）**：在 `handleEvent` 已是纯翻译器的前提下，引入
>   `EventStreamPublisher` + 双序列化器，并**一并收口 `ChatWebSocketHandler` 中
>   20 处 `StreamEvent` 直接构造**（见下方"额外范围"）。
>
> **Phase 3a 前置回归测试（已完成，全绿）**：
> | 测试文件 | 覆盖副作用 | 结果 |
> |----------|-----------|------|
> | `HandleEventSideEffectsTest` | `REQUIRE_USER_CONFIRM` → `armPendingConfirm` + 截止时间 + confirm 事件 JSON | 3/3 |
> | `ToolTraceStateTest` | `hasText`/`hasOutput`/`toolCalled` 语义、thinking-only 边界、`appendReply` 8192 滑窗保尾 | 5/5 |
> | `SubagentLoopGuardTest` | `guardSubagentLoop` 的"无交付记录不计数"语义与 `loop_warning` 触发 | 见执行进度 |
>
> **额外范围（原计划低估）**：`StreamEvent` 在 `ChatWebSocketHandler` 有 20 处直接构造
> （`:94/:288/:306/:346/:394` 等，error/end/status/stopped/pendingInfo/confirm），属 WS
> 控制面事件、不经过 `handleEvent`。若只切 Agent 事件流协议，前端会同时收到两套形状，
> 故 Phase 3b 应一并收口，否则 Phase 4「前端 v2 适配」工作量将显著超出计划描述。

**目标**：引入 `EventStreamPublisher`，将 `AgentService.startStream` 的事件出口从`AgentEvent → handleEvent → StreamEvent → onEvent` 改为`AgentEvent → EventStreamPublisher → JSON String → onEvent`。**默认走 `LegacyEventSerializer` 保持现有前端行为不变**，`NativeEventSerializer` 仅作单元测试验证。

**改动文件**：

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `AgentService.java:1302` `startStream` | 将 `agent.streamEvents(msg, context).doOnNext(event -> handleEvent(...))` 改为 `.doOnNext(event -> eventStreamPublisher.publish(event, sessionId))` |
| 修改 | `EventStreamPublisher.java` | 完整实现：持 `protocolVersion` 选择序列化器，调用 `ChatWebSocketHandler.sendJson(sessionId, json)` |
| 修改 | `ChatWebSocketHandler.java` | 新增 `sendJson(sessionId, String)` 方法（直接发送 JSON 字符串），保留旧 `sendJson(sessionId, StreamEvent)` 重载 |
| 修改 | `WebSocketHandler` 注册逻辑 | 处理 WS register 消息中的 `protocolVersion` 字段，默认 `"legacy"` |

**验证方式**：
```
mvn -pl web -am compile -DskipTests -o
```
单元测试：`EventStreamPublisherTest` 验证 `LegacyEventSerializer` 输出与当前 `StreamEvent` 形状一致。

**回滚方式**：`git revert HEAD`。若 Phase 3 与其他阶段合并提交，则单独 revert 该 commit。

**依赖**：Phase 1

---

### Phase 4：前端 v2 协议适配

**目标**：前端 `chatStore.ts` / `chatSocket.ts` / `ChatPage.tsx` 新增 `protocolVersion: "v2"` 模式，处理框架 `AgentEvent` 直接序列化后的 JSON 形状。`file_changed` / `blackboard` / `context_status` / `orchestration_audit` 等应用层事件从 `CUSTOM` type 中解析 `name` 和 `value`。

**改动文件**：

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `chatSocket.ts` | 注册时发送 `protocolVersion: "v2"`，处理新 type 值 |
| 修改 | `chatStore.ts` | 新增 `DATA_EVENT_TYPES_V2` 映射，处理 33 种框架事件 type |
| 修改 | `ChatPage.tsx` | 按 type 分流：`TEXT_BLOCK_DELTA` → 正文追加，`THINKING_BLOCK_DELTA` → 推理显示，`CUSTOM` → 按 `name` 分流 |
| 修改 | `ChatPage.tsx` | 流畅度机制（32ms 合批 / rAF / 250ms 防抖 / 3s grace / 重连拉全量）**保持不变**，只改事件解析入口 |

**验证方式**：
```
cd web\frontend && "C:\Users\xinl.tang\.vfox\cache\nodejs\v-20.18.0\nodejs-20.18.0\node.exe" node_modules\vite\bin\vite.js build
```
前端构建通过。手工验证：前端以 `v2` 协议连接，确认实时对话流畅度与原来一致。

**回滚方式**：后端 `protocolVersion` 切回 `"legacy"`，前端回退到旧协议分支。

**依赖**：Phase 3

---

### Phase 5：移除翻译层 + 遗留序列化器

**目标**：删除 `StreamEvent`、`handleEvent`、`handleNonDeltaEvent`、`handleSubagentEvent`、`inferToolResultState`、`ToolTrace`、`ToolCallSlot`、`DeltaBatcher`、`LegacyEventSerializer`。激活 middleware 链替换原有护栏/转录/审计逻辑。

**改动文件**：

| 操作 | 文件 | 说明 |
|------|------|------|
| 删除 | `agent/domain/StreamEvent.java` | 被框架 AgentEvent 直接序列化替代 |
| 删除 | `AgentService.java:1847-2034` `handleEvent` | 翻译层删除 |
| 删除 | `AgentService.java:1899-1946` `handleNonDeltaEvent` | 同上 |
| 删除 | `AgentService.java:1598-1650` `handleSubagentEvent` | 同上 |
| 删除 | `AgentService.java:1793-1800` `inferToolResultState` | 框架自带结构化错误态 |
| 删除 | `AgentService.java:1530-1596` `ToolTrace` | 框架事件自带 id/source |
| 删除 | `AgentService.java:1510-1528` `ToolCallSlot` | 同上 |
| 删除 | `AgentService.java:1178-1310` `DeltaBatcher` | 被 `DeltaCoalescer` 替代 |
| 删除 | `LegacyEventSerializer.java` | 不再需要 |
| 修改 | `WorkspaceAgentBuilder.java` | 激活 middleware 链，设置 `protocolVersion: "v2"` |
| 修改 | `AgentService.java:1302` `startStream` | 简化：只保留 `agent.streamEvents(msg, context).doOnNext(event -> eventStreamPublisher.publish(...))` |
| 修改 | `AgentService.java` | 删除 `guardSubagentLoop`、`emitOrchestrationAudit`、`emitContextStatus`、`debugLogContext`、`closeInFlightTool`、`finishTurn` 中不再需要的分支 |

**验证方式**：
```
mvn -pl web -am compile -DskipTests -o
```
然后启动应用，运行完整对话流程（含子 Agent、工具调用、文件变更），确认正常。

**回滚方式**：`git revert HEAD`。此阶段是最大变更，建议分多次 commit。

**依赖**：Phase 4

---

### Phase 6：修复遗留问题

**目标**：处理 C 类项——`enablePendingToolRecovery` 根因修复、`AgentFactory` 反射替换为 fluent API。

**改动文件**：

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `WorkspaceAgentBuilder.java:224` | `enablePendingToolRecovery(true)` 并修复 `clearStaleConfirmation` 框架不变式破坏 |
| 修改 | `AgentFactory.java:422` | 删除反射，替换为 `toolkit.registration().mcpClient(c).enableTools(list).apply()` |
| 修改 | `AgentFactory.java` | 在 `apply()` 外层加 `Mono.timeout` 防死锁 |

**验证方式**：
```
mvn -pl web -am compile -DskipTests -o
```
启动应用，手工测试 MCP 工具注册与工具调用确认正常运行。

**回滚方式**：`git revert HEAD`。

**依赖**：Phase 5

---

## 4. 风险清单

| 风险 | 触发条件 | 缓解措施 | 止损 |
|------|---------|---------|------|
| Phase 3 中 LegacyEventSerializer 输出与现有 StreamEvent 形状不一致，导致前端异常 | 映射遗漏字段或 type 取值错误 | 写 `LegacyEventSerializerTest` 逐条验证映射表；Phase 3 期间不切换协议，只验证 | 切回旧代码，修复映射后重新部署 |
| Phase 5 删除翻译层后，middleware 未覆盖某条横切逻辑 | 调研遗漏 | Phase 1-4 期间保留旧逻辑并行运行，日志对比新旧路径输出；Phase 5 前做一次全量事件用例覆盖检查 | 恢复该条 middleware，或补写后再上线 |
| 前端 v2 协议适配后流畅度下降 | 新事件解析路径引入了额外开销 | Phase 4 前端构建后做性能对比测试（同输入、同场景，对比帧率/延迟） | 后端切回 `"legacy"`，前端回退 |
| 状态存储统一（Phase 2）导致现有会话数据丢失 | 框架 `AgentStateStore` 格式与应用层手写格式不兼容 | 迁移前做数据备份；先写读兼容层（双读），确认无误后再切写 | 从备份恢复 `agent_state.json`，回退代码 |
| `enablePendingToolRecovery(true)` 后重现 `tool_call_id is not found` | 框架 2.0.3 未修复该问题 | Phase 6 先做隔离测试：在测试环境开 `enablePendingToolRecovery(true)` 跑工具调用，确认无问题再上线 | 立即切回 `false`，记录故障上下文提 issue |
| 重构过程中 `main` 分支出现无法编译的中间状态 | 某阶段提交时漏文件或多文件冲突 | **每阶段独立编译验证**，未通过不提交；commit 前执行 `mvn -pl web -am compile -DskipTests -o` | 回退到上一阶段 commit，修复后重提 |

---

## 5. 本次不做（明确排除）

| 项 | 原因 |
|----|------|
| 前端 UI 重构（ChatPage 2400 行瘦身） | 与主流程解耦，可独立进行；本次只改事件解析入口 |
| `AgentSpawnTool`（vendored 代码）整体替换为官方 `SubAgentTool` | 框架官方 SubAgentTool 同样未处理截断（#13），且替换涉及 vendored 代码 fork 管理，复杂度高；P0-1 通过 middleware 读取 ExceedMaxItersEvent 解决，无需替换 |
| `enablePendingToolRecovery` 框架层修复 | 框架 2.0.3-SNAPSHOT 是 vendored 代码，修复涉及框架源码修改，风险高；Phase 6 只做应用层规避改进 |
| 前端 chatStore 重构 | 与主流程解耦 |
| 权限模型重构 | 应用层特有逻辑，非本次范畴 |
| 前端 TypeScript 类型系统 | 与主流程解耦 |
| 测试覆盖率提升 | 本次重构会引入新类，新增单元测试是必须的，但不在本次范围做全面覆盖提升 |
| 同步 API `chat()` 的 `.block()` 消除 | 唯一裸 `.block()` 在同步入口，不在框架事件流内，且同步 API 本身需要阻塞等待结果；等 Phase 5 完成后再评估是否可改为异步 |