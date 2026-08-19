import {memo, useMemo} from 'react';
import type {ExecutionLogEntry, ToolEntry, LlmEntry, ActionEntry, SubagentEntry} from '../chatStore';

/**
 * 实时动作反馈行
 * 在消息气泡内显示当前正在执行的动作（工具调用、LLM 调用、子 Agent 等），
 * 类似 ChatGPT 的「正在使用 xxx」效果，消除黑盒感。
 * 
 * 同时显示已完成的动作摘要，让用户感知到"Agent 一直在干活"，减少等死感。
 */
function LiveActionsImpl({entries, running}: { entries: ExecutionLogEntry[]; running?: boolean }) {
  if (!entries || entries.length === 0) return null;

  // 正在执行的条目
  const runningEntries = entries.filter(e => e.status === 'running');
  // 已完成的条目（最多显示最近 3 条）
  const doneEntries = useMemo(() => 
    entries.filter(e => e.status === 'done').slice(-3),
    [entries]
  );

  const hasRunning = runningEntries.length > 0 && running;
  const hasDone = doneEntries.length > 0;

  // 如果既没有运行中也没有已完成，不显示
  if (!hasRunning && !hasDone) return null;

  // 统计
  const totalDone = entries.filter(e => e.status === 'done').length;
  const totalTools = entries.filter(e => e.kind === 'tool' && e.status === 'done').length;
  const totalLlms = entries.filter(e => e.kind === 'llm' && e.status === 'done').length;

  return (
    <div className="live-actions">
      <div className="live-actions-title">
        <span className="spinner-tiny" /> 实时进度
        <span className="live-actions-count">
          已完成 {totalDone} 步
          {totalTools > 0 && <span> · 🔧{totalTools}</span>}
          {totalLlms > 0 && <span> · 🧠{totalLlms}</span>}
        </span>
      </div>

      {/* 已完成的动作（淡化显示，给用户"一直在推进"的感觉） */}
      {hasDone && (
        <div className="live-actions-done">
          {doneEntries.map((entry, i) => (
            <DoneActionRow key={`d-${i}`} entry={entry} />
          ))}
        </div>
      )}

      {/* 正在执行的动作（高亮显示） */}
      {hasRunning && (
        <div className="live-actions-list">
          {runningEntries.map((entry, i) => (
            <LiveActionRow key={`r-${i}`} entry={entry} />
          ))}
        </div>
      )}
    </div>
  );
}

function LiveActionRow({entry}: { entry: ExecutionLogEntry }) {
  if (entry.kind === 'tool') {
    return <ToolActionRow entry={entry} />;
  }
  if (entry.kind === 'llm') {
    return <LlmActionRow entry={entry} />;
  }
  if (entry.kind === 'action') {
    return <ActionActionRow entry={entry} />;
  }
  if (entry.kind === 'subagent') {
    return <SubagentActionRow entry={entry} />;
  }
  return null;
}

function ToolActionRow({entry}: { entry: ToolEntry }) {
  const hasInput = entry.input && entry.input !== '{}';
  return (
    <div className="live-action-row tool">
      <span className="live-action-icon">🔧</span>
      <span className="live-action-name">{entry.tool}</span>
      {entry.action && <span className="live-action-action">· {entry.action}</span>}
      <span className="live-action-dots"><span /><span /><span /></span>
      {hasInput && (
        <details className="live-action-details">
          <summary>参数</summary>
          <pre>{truncate(entry.input, 500)}</pre>
        </details>
      )}
    </div>
  );
}

function LlmActionRow({entry}: { entry: LlmEntry }) {
  return (
    <div className="live-action-row llm">
      <span className="live-action-icon">🧠</span>
      <span className="live-action-name">思考中</span>
      <span className="live-action-sub">· {entry.model}</span>
      {entry.action && <span className="live-action-action">· {entry.action}</span>}
      {entry.messageCount != null && entry.messageCount > 0 && (
        <span className="live-action-sub">· {entry.messageCount} 条消息</span>
      )}
      <span className="live-action-dots"><span /><span /><span /></span>
    </div>
  );
}

function ActionActionRow({entry}: { entry: ActionEntry }) {
  const showProgress = typeof entry.index === 'number' && typeof entry.total === 'number' && entry.total > 1;
  return (
    <div className="live-action-row action">
      <span className="live-action-icon">🎯</span>
      <span className="live-action-name">{entry.name}</span>
      {entry.description && <span className="live-action-sub">· {entry.description}</span>}
      {showProgress && (
        <span className="live-action-progress">
          ({entry.index! + 1}/{entry.total})
        </span>
      )}
      <span className="live-action-dots"><span /><span /><span /></span>
    </div>
  );
}

function SubagentActionRow({entry}: { entry: SubagentEntry }) {
  return (
    <div className="live-action-row subagent">
      <span className="live-action-icon">🤖</span>
      <span className="live-action-name">{entry.name}</span>
      <span className="live-action-sub">· 子 Agent</span>
      <span className="live-action-dots"><span /><span /><span /></span>
    </div>
  );
}

function truncate(s: string, max: number): string {
  if (s.length <= max) return s;
  return s.slice(0, max) + `... (${s.length - max} 更多)`;
}

// 已完成动作行（淡化显示，显示对勾 + 耗时）
function DoneActionRow({entry}: { entry: ExecutionLogEntry }) {
  const label = entry.kind === 'tool' ? (entry as ToolEntry).tool
    : entry.kind === 'llm' ? `思考 · ${(entry as LlmEntry).model}`
    : entry.kind === 'action' ? (entry as ActionEntry).name
    : entry.kind === 'subagent' ? (entry as SubagentEntry).name
    : '完成';

  const icon = entry.kind === 'tool' ? '🔧'
    : entry.kind === 'llm' ? '🧠'
    : entry.kind === 'action' ? '🎯'
    : entry.kind === 'subagent' ? '🤖'
    : '✓';

  const durationMs = entry.durationMs;
  const durationText = durationMs != null
    ? durationMs >= 1000 ? `${(durationMs / 1000).toFixed(1)}s` : `${durationMs}ms`
    : '';

  return (
    <div className="live-action-row done-item">
      <span className="live-action-icon done-icon">✓</span>
      <span className="done-tool-icon">{icon}</span>
      <span className="done-name">{label}</span>
      {durationText && <span className="done-duration">{durationText}</span>}
    </div>
  );
}

export const LiveActions = memo(LiveActionsImpl);
