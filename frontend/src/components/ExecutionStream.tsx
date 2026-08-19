import {memo, useEffect, useRef, useState, useCallback} from 'react';

/**
 * 执行流瀑布流组件 v2
 * 完整支持 21 种 Embabel 事件类型
 * 打字机效果：每个 Agent 独立累积 text/reasoning 内容
 */

export interface StreamEvent {
  type: string;
  timestamp: number;
  data?: any;
  content?: string;
  agentName?: string;
  processId?: string;
  agentCategory?: string;
  parentAgentName?: string;
}

interface AgentGroup {
  agentName: string;
  processId: string;
  agentCategory?: string;
  parentAgentName?: string;
  events: StreamEvent[];
}

/** 每个事件类型的元数据（图标/标签/颜色类名） */
const EVENT_META: Record<string, { icon: string; label: string; color: string }> = {
  // --- 规划 ---
  plan: { icon: '🎯', label: 'GOAP 规划', color: 'exec-event-plan' },
  // --- Action 执行 ---
  agent_action: { icon: '▶️', label: 'Action', color: 'exec-event-action' },
  step: { icon: '👣', label: 'Step', color: 'exec-event-step' },
  // --- 工具调用 ---
  tool_call: { icon: '🔧', label: 'Tool', color: 'exec-event-tool' },
  tool_loop: { icon: '🔄', label: 'Tool Loop', color: 'exec-event-tool-loop' },
  // --- LLM 调用 ---
  llm_call: { icon: '🧠', label: 'LLM', color: 'exec-event-llm' },
  llm_thinking: { icon: '💭', label: 'Thinking', color: 'exec-event-thinking' },
  // --- 子 Agent ---
  subagent_lifecycle: { icon: '🔗', label: 'SubAgent', color: 'exec-event-subagent' },
  // --- 对话输出 ---
  text: { icon: '💬', label: '回复', color: 'exec-event-text' },
  reasoning: { icon: '💭', label: '思考', color: 'exec-event-reasoning' },
  // --- 进度/状态 ---
  progress_update: { icon: '📊', label: '进度', color: 'exec-event-progress' },
  state_transition: { icon: '🔀', label: '状态转换', color: 'exec-event-state' },
  goal_achieved: { icon: '🎯', label: '目标达成', color: 'exec-event-goal' },
  replan_requested: { icon: '🔄', label: '重新规划', color: 'exec-event-replan' },
  // --- 生命周期 ---
  error: { icon: '⚠️', label: '错误', color: 'exec-event-error' },
  end: { icon: '✅', label: '完成', color: 'exec-event-end' },
  stuck: { icon: '⏱️', label: '超时', color: 'exec-event-stuck' },
  failed: { icon: '❌', label: '失败', color: 'exec-event-failed' },
  completed: { icon: '✅', label: '完成', color: 'exec-event-completed' },
  llm_invocation: { icon: '⚙️', label: 'LLM 配置', color: 'exec-event-invocation' },
};

const AGENT_EMOJI: Record<string, string> = {
  'orchestrator-agent': '🎯', 'code-agent': '💻', 'file-agent': '📁',
  'research-agent': '🔍', 'content-agent': '✍️', 'data-agent': '📊',
  'mail-agent': '📧', 'interaction-agent': '💬', 'devops-agent': '🚀',
  'verifier-agent': '✅', 'web-agent': '🌐',
};

/** 僵尸事件兜底：事件 timestamp 距今超过此时间仍显示 running 时，自动强制为 done，避免 spinner 常转（毫秒） */
const STALE_TIMEOUT_MS = 120_000;

function getAgentEmoji(name: string): string {
  if (!name) return '🤖';
  if (AGENT_EMOJI[name]) return AGENT_EMOJI[name];
  for (const [key, emoji] of Object.entries(AGENT_EMOJI)) {
    if (name.includes(key.replace('-agent', ''))) return emoji;
  }
  return '🤖';
}

function formatAgentName(name: string): string {
  if (!name) return 'Unknown';
  return name.replace(/-agent$/, '').replace(/-/g, ' ').replace(/^./, c => c.toUpperCase());
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}`;
}

/** 打字机累加器：为每个 agent 维护 text/reasoning 的累积文本 */
function useTypewriterAccumulator(events: StreamEvent[]) {
  const [accumulated, setAccumulated] = useState<Map<string, { text: string; reasoning: string }>>(new Map());
  const prevLenRef = useRef(0);

  useEffect(() => {
    if (events.length <= prevLenRef.current) {
      if (events.length === 0) setAccumulated(new Map());
      prevLenRef.current = events.length;
      return;
    }
    const newEvents = events.slice(prevLenRef.current);
    prevLenRef.current = events.length;

    setAccumulated(prev => {
      const next = new Map(prev);
      for (const evt of newEvents) {
        if (evt.type === 'text' || evt.type === 'reasoning') {
          const key = `${evt.agentName || 'unknown'}::${evt.processId || 'unknown'}`;
          const curr = next.get(key) || { text: '', reasoning: '' };
          if (evt.type === 'text') {
            next.set(key, { ...curr, text: curr.text + (evt.content || '') });
          } else {
            next.set(key, { ...curr, reasoning: curr.reasoning + (evt.content || '') });
          }
        }
      }
      return next;
    });
  }, [events.length]);

  return accumulated;
}

interface Props {
  events: StreamEvent[];
  workspaceId?: string;
  sessionId?: string;
  /** 会话是否已结束（收到 COMPLETED/FAILED/end），true 时所有 agent 强制显示完成态 */
  completed?: boolean;
}

function ExecutionStreamImpl({events, workspaceId, sessionId, completed}: Props) {
  const [agentGroups, setAgentGroups] = useState<AgentGroup[]>([]);
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [expandedEvents, setExpandedEvents] = useState<Set<string>>(new Set());
  const bottomRef = useRef<HTMLDivElement>(null);

  const accumulated = useTypewriterAccumulator(events);

  // 事件统计
  const eventStats = useRef({ llmCalls: 0, toolCalls: 0, actions: 0, thinking: 0 });

  useEffect(() => {
    const groups = new Map<string, AgentGroup>();
    const stats = { llmCalls: 0, toolCalls: 0, actions: 0, thinking: 0 };
    for (const evt of events) {
      const agent = evt.agentName || 'unknown';
      const pid = evt.processId || 'unknown';
      const key = `${agent}::${pid}`;
      if (!groups.has(key)) {
        groups.set(key, {
          agentName: agent,
          processId: pid,
          agentCategory: evt.data?.agentCategory,
          parentAgentName: evt.data?.parentAgentName,
          events: []
        });
      }
      groups.get(key)!.events.push(evt);

      // 统计
      if (evt.type === 'llm_call' && evt.data?.type?.includes('end')) stats.llmCalls++;
      if (evt.type === 'tool_call' && evt.data?.type?.includes('end')) stats.toolCalls++;
      if (evt.type === 'agent_action' && evt.data?.status === 'done') stats.actions++;
      if (evt.type === 'llm_thinking') stats.thinking++;
    }
    setAgentGroups(Array.from(groups.values()));
    eventStats.current = stats;

    const newExpanded = new Set(expandedGroups);
    for (const key of groups.keys()) {
      if (!newExpanded.has(key)) newExpanded.add(key);
    }
    setExpandedGroups(newExpanded);
    bottomRef.current?.scrollIntoView({behavior: 'smooth'});
  }, [events.length]);

  const toggleGroup = useCallback((key: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  }, []);

  const toggleEvent = useCallback((id: string) => {
    setExpandedEvents(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }, []);

  const isEventActive = (evt: StreamEvent): boolean => {
    // 会话已结束：所有事件强制为非活跃态
    if (completed) return false;
    // 超时兜底：若事件超过 STALE_TIMEOUT_MS 仍停留在 running 状态，视为僵尸事件强制 done。
    // 典型场景：ToolCall START=readfile（小写） / END=readFile（驼峰）名不一致导致 START 永远收不到 END，
    // 120s 后自动褪色为 done，UI 不再 spinner 常转。
    if (evt.timestamp && Date.now() - evt.timestamp > STALE_TIMEOUT_MS) return false;
    const s = evt.data?.status;
    return s === 'running' ||
      (evt.type === 'llm_call' && evt.data?.type === 'llm_call_start') ||
      (evt.type === 'tool_call' && evt.data?.type === 'tool_call_start') ||
      (evt.type === 'agent_action' && s === 'running') ||
      (evt.type === 'tool_loop' && evt.data?.type === 'tool_loop_start');
  };

  return (
    <div className="exec-stream">
      {/* 顶部元数据栏 */}
      <div className="exec-stream-meta-bar">
        <span className="exec-stream-title">⚡ 执行流</span>
        {workspaceId && <span className="exec-meta-item"><strong>workspace</strong>: {workspaceId}</span>}
        {sessionId && <span className="exec-meta-item"><strong>session</strong>: {sessionId}</span>}
        <span className="exec-meta-divider">│</span>
        <span className="exec-meta-stat"><span className="dot llm" />LLM {eventStats.current.llmCalls}</span>
        <span className="exec-meta-stat"><span className="dot tool" />Tool {eventStats.current.toolCalls}</span>
        <span className="exec-meta-stat"><span className="dot action" />Action {eventStats.current.actions}</span>
        <span className="exec-meta-stat"><span className="dot thinking" />Thinking {eventStats.current.thinking}</span>
        <span className="exec-meta-total">共 {events.length} 事件</span>
      </div>

      {agentGroups.length === 0 && (
        <div className="exec-stream-empty">
          <div className="empty-icon">🤖</div>
          <div>等待 Agent 事件流...</div>
          <div className="empty-hint">发送消息后，LLM 调用、工具执行、思考过程将在此实时展示</div>
        </div>
      )}

      {/* Agent 分组瀑布流 */}
      {agentGroups.map((group) => {
        const key = `${group.agentName}::${group.processId}`;
        const expanded = expandedGroups.has(key);
        const emoji = getAgentEmoji(group.agentName);
        const displayName = formatAgentName(group.agentName);
        const latestEvent = group.events[group.events.length - 1];
        const active = group.events.some(isEventActive);
        const acc = accumulated.get(key);

        return (
          <div key={key} className={`exec-agent-group ${active ? 'active' : ''} ${expanded ? 'expanded' : ''}`}>
            {/* Agent 头部 */}
            <div className="exec-agent-header" onClick={() => toggleGroup(key)}>
              <span className="exec-agent-icon">{emoji}</span>
              <span className="exec-agent-name">{displayName}</span>
              {group.agentCategory && (
                <span className={`exec-agent-category cat-${getCategoryColor(group.agentCategory)}`}>
                  {group.agentCategory}
                </span>
              )}
              {group.parentAgentName && (
                <span className="exec-agent-parent">
                  ↳ {formatAgentName(group.parentAgentName)}
                </span>
              )}
              <span className="exec-agent-badge badge-{getAgentColor(group.agentName)}">{displayName.split(' ')[0]}</span>
              {active && (
                <span className="exec-agent-running">
                  <span className="spinner" /> 执行中
                </span>
              )}
              {!active && latestEvent && (
                <span className="exec-agent-status-done">✓ 完成</span>
              )}
              <span className="exec-agent-count">{group.events.length} events</span>
              <span className="exec-agent-toggle">{expanded ? '▼' : '▶'}</span>
            </div>

            {/* Agent 事件列表 */}
            {expanded && (
              <div className="exec-agent-body">
                {/* 打字机文本区域 */}
                {(acc?.text || acc?.reasoning) && (
                  <div className="exec-typewriter-section">
                    {acc.reasoning && (
                      <div className="exec-typewriter-block reasoning">
                        <div className="exec-typewriter-label">💭 思考过程</div>
                        <div className="exec-typewriter-content">{acc.reasoning}<span className="cursor" /></div>
                      </div>
                    )}
                    {acc.text && (
                      <div className="exec-typewriter-block text">
                        <div className="exec-typewriter-label">💬 回复内容</div>
                        <div className="exec-typewriter-content">{acc.text}<span className="cursor" /></div>
                      </div>
                    )}
                  </div>
                )}

                {/* 事件时间线 */}
                <div className="exec-timeline">
                  {group.events.map((evt, i) => (
                    <ExecEventItem
                      key={`${key}-${i}`}
                      evt={evt}
                      index={i}
                      expanded={expandedEvents.has(`${key}::${i}`)}
                      onToggle={() => toggleEvent(`${key}::${i}`)}
                      isActive={isEventActive(evt)}
                    />
                  ))}
                </div>
              </div>
            )}
          </div>
        );
      })}
      <div ref={bottomRef} />
    </div>
  );
}

function getAgentColor(name: string): string {
  const lower = (name || '').toLowerCase();
  if (lower.includes('orchestrator')) return 'orchestrator';
  if (lower.includes('code')) return 'code';
  if (lower.includes('content')) return 'content';
  if (lower.includes('file')) return 'file';
  if (lower.includes('data')) return 'data';
  if (lower.includes('mail')) return 'mail';
  if (lower.includes('devops')) return 'devops';
  if (lower.includes('verifier')) return 'verifier';
  if (lower.includes('research')) return 'research';
  if (lower.includes('interaction')) return 'interaction';
  return 'default';
}

function getCategoryColor(category: string): string {
  const map: Record<string, string> = {
    '代码': 'code', '文件': 'file', '验证': 'verifier', '交互': 'interaction',
    '邮件': 'mail', '内容': 'content', '数据': 'data', '网页': 'web',
    '运维': 'devops', '调研': 'research', '周报': 'weekly',
    '内容创作': 'content-create', '邮件分拣': 'mail-triage',
    '数据分析': 'data-analysis', '默认': 'default'
  };
  return map[category] || 'default';
}

/** 单个事件渲染项 */
function ExecEventItem({evt, index, expanded, onToggle, isActive}: {
  evt: StreamEvent;
  index: number;
  expanded: boolean;
  onToggle: () => void;
  isActive: boolean;
}) {
  const {type, data, content} = evt;
  const time = formatTime(evt.timestamp);
  const status = data?.status || data?.type?.toString().toLowerCase() || '';

  const meta = EVENT_META[type] || { icon: '📦', label: type, color: 'exec-event-unknown' };

  // 计算事件摘要
  const summary = getEventSummary(type, data, content, index);

  return (
    <div className={`exec-event-item ${meta.color} ${isActive ? 'active' : ''} ${status}`}>
      {/* 事件头部 */}
      <div className="exec-event-header" onClick={onToggle}>
        <span className="exec-event-dot" />
        <span className="exec-event-icon">{meta.icon}</span>
        <span className="exec-event-label">{meta.label}</span>
        {summary && <span className="exec-event-summary">{summary}</span>}
        {isActive && <span className="exec-event-spinner" />}
        <span className="exec-event-time">{time}</span>
        <span className="exec-event-caret">{expanded ? '▼' : '▶'}</span>
      </div>

      {/* 展开详情 */}
      {expanded && (
        <div className="exec-event-detail">
          <EventDetailRenderer type={type} data={data} content={content} />
        </div>
      )}
    </div>
  );
}

/** 生成事件摘要文本 */
function getEventSummary(type: string, data: any, content: string | undefined, index: number): string {
  if (!data && !content) return '';

  switch (type) {
    case 'plan': return data?.goal || '';
    case 'step': return `${data?.name || ''} (${data?.index ?? index + 1}/${data?.total ?? '?'})`;
    case 'agent_action': return data?.action || '';
    case 'tool_call': return data?.tool ? `${data.tool}${data.status === 'running' ? ' 调用中...' : ''}` : '';
    case 'llm_call': return data?.model ? `${data.model}${data.status === 'running' ? ' 推理中...' : ` ${data.durationMs || 0}ms`}` : '';
    case 'subagent_lifecycle': return `${data?.name || ''} ${data?.status === 'start' ? '启动' : '结束'}`;
    case 'llm_thinking': return data?.thinkingContent ? `${truncateStr(data.thinkingContent, 80)}` : '';
    case 'progress_update': return `${data?.name || ''} ${data?.current || 0}/${data?.total || 0}`;
    case 'goal_achieved': return data?.goal ? `达成: ${data.goal}` : '';
    case 'tool_loop': return `${data?.action || ''} ×${data?.totalIterations || data?.maxIterations || '?'}`;
    case 'state_transition': return `${data?.previousState || '?'} → ${data?.newState || '?'}`;
    case 'replan_requested': return data?.reason ? `原因: ${truncateStr(data.reason, 60)}` : '';
    case 'reasoning': return truncateStr(content || '', 60);
    case 'text': return truncateStr(content || '', 60);
    case 'error': return truncateStr(content || data?.toString() || '', 80);
    default: return content ? truncateStr(content, 50) : '';
  }
}

function truncateStr(s: string, max: number): string {
  if (!s) return '';
  return s.length <= max ? s : s.slice(0, max) + '...';
}

/** 事件详情渲染器（按类型分发） */
function EventDetailRenderer({type, data, content}: {type: string; data: any; content?: string}) {
  switch (type) {
    case 'plan':
      return <PlanDetail data={data} />;
    case 'llm_call':
      return <LlmCallDetail data={data} />;
    case 'llm_thinking':
      return <ThinkingDetail data={data} />;
    case 'tool_call':
      return <ToolCallDetail data={data} />;
    case 'agent_action':
      return <ActionDetail data={data} />;
    case 'tool_loop':
      return <ToolLoopDetail data={data} />;
    case 'progress_update':
      return <ProgressDetail data={data} />;
    case 'goal_achieved':
      return <GoalDetail data={data} />;
    case 'state_transition':
      return <StateTransitionDetail data={data} />;
    case 'replan_requested':
      return <ReplanDetail data={data} />;
    case 'subagent_lifecycle':
      return <SubagentDetail data={data} />;
    case 'reasoning':
    case 'text':
      return <TextDetail content={content} />;
    case 'error':
      return <ErrorDetail content={content} data={data} />;
    default:
      return <GenericDetail data={data} content={content} />;
  }
}

/* ========== 各类型详情组件 ========== */

function PlanDetail({data}: {data: any}) {
  return (
    <div className="detail-section">
      <div className="detail-row"><span className="label">目标</span><span className="value">{data?.goal}</span></div>
      {data?.goalDescription && <div className="detail-row"><span className="label">描述</span><span className="value">{data.goalDescription}</span></div>}
      <div className="detail-row"><span className="label">步骤数</span><span className="value">{data?.totalSteps}</span></div>
      {data?.steps?.length > 0 && (
        <div className="detail-steps">
          <div className="detail-subtitle">执行步骤：</div>
          {data.steps.map((s: any, i: number) => (
            <div key={i} className="detail-step">
              <span className="step-num">{i + 1}.</span>
              <span className="step-name">{s.name}</span>
              {s.description && <span className="step-desc">— {s.description}</span>}
            </div>
          ))}
        </div>
      )}
      {data?.agent && (
        <div className="detail-agent-info">
          <div className="detail-subtitle">Agent 信息：</div>
          <div className="detail-row"><span className="label">名称</span><span className="value">{data.agent.name}</span></div>
          {data.agent.actions?.length > 0 && (
            <div className="detail-actions-list">
              {data.agent.actions.map((a: any, i: number) => (
                <span key={i} className="action-chip">{a.name}</span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function LlmCallDetail({data}: {data: any}) {
  return (
    <div className="detail-section">
      <div className="detail-row"><span className="label">模型</span><span className="value">{data?.model}</span></div>
      <div className="detail-row"><span className="label">Action</span><span className="value">{data?.action}</span></div>
      <div className="detail-row"><span className="label">状态</span><span className={`value status-${data?.status}`}>{data?.status}</span></div>
      {data?.messageCount !== undefined && <div className="detail-row"><span className="label">消息数</span><span className="value">{data.messageCount}</span></div>}
      {data?.durationMs !== undefined && <div className="detail-row"><span className="label">耗时</span><span className="value">{data.durationMs}ms</span></div>}
      {data?.responseLength !== undefined && <div className="detail-row"><span className="label">响应长度</span><span className="value">{data.responseLength} 字符</span></div>}
      {data?.promptPreview && (
        <div className="detail-block">
          <div className="detail-subtitle">Prompt 预览：</div>
          <pre className="code-block">{data.promptPreview}</pre>
        </div>
      )}
      {data?.responsePreview && (
        <div className="detail-block">
          <div className="detail-subtitle">Response 预览：</div>
          <pre className="code-block">{data.responsePreview}</pre>
        </div>
      )}
    </div>
  );
}

function ThinkingDetail({data}: {data: any}) {
  return (
    <div className="detail-section thinking">
      <div className="detail-row"><span className="label">类型</span><span className="value">LLM Thinking/Reasoning</span></div>
      {data?.hasThinking !== undefined && <div className="detail-row"><span className="label">有思考</span><span className="value">{data.hasThinking ? '是' : '否'}</span></div>}
      {data?.thinkingBlocks?.length > 0 && (
        <div className="detail-block">
          <div className="detail-subtitle">Thinking Blocks：</div>
          {data.thinkingBlocks.map((b: any, i: number) => (
            <div key={i} className="thinking-block-item">
              <div className="thinking-block-type">{b?.type || 'unknown'}</div>
              <div className="thinking-block-content">{b?.content || ''}</div>
            </div>
          ))}
        </div>
      )}
      {data?.thinkingContent && (
        <div className="detail-block">
          <div className="detail-subtitle">完整思考内容：</div>
          <pre className="code-block thinking">{data.thinkingContent}</pre>
        </div>
      )}
    </div>
  );
}

function ToolCallDetail({data}: {data: any}) {
  return (
    <div className="detail-section">
      <div className="detail-row"><span className="label">工具</span><span className="value">{data?.tool}</span></div>
      <div className="detail-row"><span className="label">Action</span><span className="value">{data?.action}</span></div>
      <div className="detail-row"><span className="label">状态</span><span className={`value status-${data?.status}`}>{data?.status}</span></div>
      {data?.durationMs !== undefined && <div className="detail-row"><span className="label">耗时</span><span className="value">{data.durationMs}ms</span></div>}
      {data?.correlationId && <div className="detail-row"><span className="label">Correlation</span><span className="value mono">{data.correlationId}</span></div>}
      {data?.input && (
        <div className="detail-block">
          <div className="detail-subtitle">Input：</div>
          <pre className="code-block">{data.input}</pre>
        </div>
      )}
      {data?.output !== undefined && (
        <div className="detail-block">
          <div className="detail-subtitle">Output：</div>
          <pre className="code-block">{String(data.output).slice(0, 5000)}</pre>
        </div>
      )}
    </div>
  );
}

function ActionDetail({data}: {data: any}) {
  return (
    <div className="detail-section">
      <div className="detail-row"><span className="label">Action</span><span className="value">{data?.action || data?.name}</span></div>
      {data?.description && <div className="detail-row"><span className="label">描述</span><span className="value">{data.description}</span></div>}
      <div className="detail-row"><span className="label">状态</span><span className={`value status-${data?.status}`}>{data?.status}</span></div>
      {data?.index !== undefined && <div className="detail-row"><span className="label">Step</span><span className="value">{data.index + 1}/{data?.total || '?'}</span></div>}
      {data?.durationMs !== undefined && <div className="detail-row"><span className="label">耗时</span><span className="value">{data.durationMs}ms</span></div>}
    </div>
  );
}

function ToolLoopDetail({data}: {data: any}) {
  return (
    <div className="detail-section">
      <div className="detail-row"><span className="label">Action</span><span className="value">{data?.action}</span></div>
      {data?.toolNames?.length > 0 && (
        <div className="detail-row">
          <span className="label">可用工具</span>
          <span className="value">
            {data.toolNames.map((t: string, i: number) => (
              <span key={i} className="tool-chip">{t}</span>
            ))}
          </span>
        </div>
      )}
      <div className="detail-row"><span className="label">最大迭代</span><span className="value">{data?.maxIterations}</span></div>
      {data?.totalIterations !== undefined && <div className="detail-row"><span className="label">实际迭代</span><span className="value">{data.totalIterations}</span></div>}
      {data?.replanRequested !== undefined && <div className="detail-row"><span className="label">需要重规划</span><span className="value">{data.replanRequested ? '是' : '否'}</span></div>}
      {data?.durationMs !== undefined && <div className="detail-row"><span className="label">耗时</span><span className="value">{data.durationMs}ms</span></div>}
    </div>
  );
}

function ProgressDetail({data}: {data: any}) {
  const pct = data?.total > 0 ? Math.round((data.current / data.total) * 100) : 0;
  return (
    <div className="detail-section">
      <div className="detail-row"><span className="label">任务</span><span className="value">{data?.name}</span></div>
      <div className="detail-row"><span className="label">进度</span><span className="value">{data?.current}/{data?.total} ({pct}%)</span></div>
      <div className="progress-bar-container">
        <div className="progress-bar" style={{width: `${pct}%`}} />
      </div>
    </div>
  );
}

function GoalDetail({data}: {data: any}) {
  return (
    <div className="detail-section goal">
      <div className="detail-row"><span className="label">达成目标</span><span className="value highlight">🎯 {data?.goal}</span></div>
      {data?.worldState && (
        <div className="detail-block">
          <div className="detail-subtitle">World State：</div>
          <pre className="code-block">{data.worldState}</pre>
        </div>
      )}
    </div>
  );
}

function StateTransitionDetail({data}: {data: any}) {
  return (
    <div className="detail-section">
      <div className="detail-row"><span className="label">原状态</span><span className="value">{data?.previousState}</span></div>
      <div className="state-arrow">↓</div>
      <div className="detail-row"><span className="label">新状态</span><span className="value highlight">{data?.newState}</span></div>
      {data?.initial && <div className="detail-row"><span className="label">初始状态</span><span className="value">是</span></div>}
    </div>
  );
}

function ReplanDetail({data}: {data: any}) {
  return (
    <div className="detail-section replan">
      <div className="detail-row"><span className="label">原因</span><span className="value warn">{data?.reason}</span></div>
      <div className="detail-hint">系统将重新进行 GOAP 规划</div>
    </div>
  );
}

function SubagentDetail({data}: {data: any}) {
  return (
    <div className="detail-section">
      <div className="detail-row"><span className="label">子Agent</span><span className="value">{data?.name}</span></div>
      <div className="detail-row"><span className="label">状态</span><span className={`value status-${data?.status}`}>{data?.status === 'start' ? '启动' : data?.status === 'end' ? '结束' : '失败'}</span></div>
      {data?.processId && <div className="detail-row"><span className="label">ProcessId</span><span className="value mono">{data.processId}</span></div>}
      {data?.parentProcessId && <div className="detail-row"><span className="label">ParentId</span><span className="value mono">{data.parentProcessId}</span></div>}
      {data?.error && <div className="detail-row"><span className="label">错误</span><span className="value error">{String(data.error)}</span></div>}
    </div>
  );
}

function TextDetail({content}: {content?: string}) {
  return (
    <div className="detail-section text-content">
      <pre className="code-block">{content}</pre>
    </div>
  );
}

function ErrorDetail({content, data}: {content?: string; data?: any}) {
  return (
    <div className="detail-section error">
      <div className="error-message">{content || data?.toString() || '未知错误'}</div>
      {data && typeof data === 'object' && (
        <pre className="code-block">{JSON.stringify(data, null, 2)}</pre>
      )}
    </div>
  );
}

function GenericDetail({data, content}: {data?: any; content?: string}) {
  return (
    <div className="detail-section">
      {content && <div className="detail-block"><pre className="code-block">{content}</pre></div>}
      {data && typeof data === 'object' && (
        <div className="detail-block">
          <div className="detail-subtitle">Data：</div>
          <pre className="code-block">{JSON.stringify(data, null, 2)}</pre>
        </div>
      )}
    </div>
  );
}

export const ExecutionStream = memo(ExecutionStreamImpl);
