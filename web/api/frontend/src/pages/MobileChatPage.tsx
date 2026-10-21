/**
 * 移动端聊天页（/m/chat/:workspaceId）。
 *
 * 独立轻量实现（不复用 ChatPage 组件）：只保留移动端核心能力——
 * 消息流、发送/停止、工具确认条、🎤 语音输入。桌面复杂能力
 * （文件/黑板/授权面板/队列/插队/子 Agent 时间线）不进入移动端。
 *
 * workspaceId 由 App.tsx 从 pathname 解析后以 prop 传入（/m/* 不走 <Route>
 * 参数定义，useParams 拿不到）；保留 useParams 兜底以兼容未来改为 Route 挂载。
 *
 * WS 事件处理是 ChatPage 的简化子集（text/tool/subagent/confirm/error/end/stopped），
 * 协议语义与 ChatPage 保持同步；改动消息协议时两处都要核对。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { getJson, postForm, postJson } from '../api';
import { getChatSocket, subscribeChatSocket } from '../chatSocket';
import { getActiveSession, setActiveSession } from '../chatStore';
import { createVoiceRecorder } from '../voiceRecorder';

interface SessionItem { id: string; title: string; }
/** 后端转录条目（/api/chat/history）：type 为大写枚举 */
interface HistMsg { type: string; content: string; toolName?: string; subagentName?: string; }
/** 消息统一用内部小写形态（历史经 toMsg 转换、实时事件直接产出），渲染层零分支重复 */
interface Msg { type: string; content: string; toolName?: string; toolResult?: string; subagentName?: string; running?: boolean; }
interface PendingConfirm { tools: { name: string; input: string }[]; raw: string; }

/** 历史转录条目 → 内部消息形态（THINKING/TOOL_RESULT/BLACKBOARD/SYSTEM 移动端不展示） */
function toMsg(b: HistMsg): Msg | null {
  switch (b.type) {
    case 'USER': return { type: 'user', content: b.content };
    case 'AI_TEXT': return { type: 'text', content: b.content };
    case 'TOOL_CALL': return { type: 'tool', toolName: b.toolName || '', content: '完成' };
    case 'SUBAGENT': return { type: 'subagent', subagentName: b.subagentName || '', content: '' };
    default: return null;
  }
}

/** 轻量 markdown 渲染（与 ChatPage 同库同净化策略） */
function md(text: string): string {
  return DOMPurify.sanitize(marked.parse(text, { async: false }) as string);
}

export default function MobileChatPage({ workspaceId: wsIdProp }: { workspaceId?: string }) {
  const params = useParams<{ workspaceId: string }>();
  const navigate = useNavigate();
  const wid = wsIdProp || params.workspaceId || '';

  const [sessionTitle, setSessionTitle] = useState('');
  const [sessionId, setSessionId] = useState('');
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [running, setRunning] = useState(false);
  const [pending, setPending] = useState<PendingConfirm | null>(null);
  const [input, setInput] = useState('');
  const [error, setError] = useState('');
  const [hint, setHint] = useState('');
  const [loaded, setLoaded] = useState(false);
  // 语音输入（与 ChatPage 同款三态）
  const [voiceState, setVoiceState] = useState<'idle' | 'recording' | 'transcribing'>('idle');
  const [voiceSeconds, setVoiceSeconds] = useState(0);
  const recorderRef = useRef<ReturnType<typeof createVoiceRecorder> | null>(null);
  const voiceTimerRef = useRef(0);
  const bottomRef = useRef<HTMLDivElement>(null);
  const sidRef = useRef('');
  sidRef.current = sessionId;

  const showHint = useCallback((msg: string, ms = 5000) => {
    setHint(msg);
    window.setTimeout(() => setHint((cur) => (cur === msg ? '' : cur)), ms);
  }, []);

  // ---- 会话初始化：取活跃会话（无则取列表第一个，再无则新建）----
  useEffect(() => {
    if (!wid) return;
    let cancelled = false;
    (async () => {
      try {
        const list = await getJson<SessionItem[]>(`/api/workspaces/${wid}/sessions`);
        if (cancelled) return;
        const active = getActiveSession(wid);
        let target = list.find((s) => s.id === active) || list[0];
        if (!target) {
          target = await postJson<SessionItem>(`/api/workspaces/${wid}/sessions`, { title: '新会话' });
        }
        setSessionTitle(target.title);
        setSessionId(target.id);
        setActiveSession(wid, target.id);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) setLoaded(true);
      }
    })();
    return () => { cancelled = true; };
  }, [wid]);

  // ---- 会话就绪：拉历史 + 状态恢复 + WS 注册 ----
  useEffect(() => {
    if (!wid || !sessionId) return;
    let cancelled = false;
    (async () => {
      try {
        const history = await getJson<HistMsg[]>(`/api/chat/history?workspaceId=${wid}&sessionId=${sessionId}`);
        if (!cancelled) setMsgs((Array.isArray(history) ? history : []).map(toMsg).filter((m): m is Msg => m !== null));
        const st = await getJson<{ running: boolean; pending: boolean }>(`/api/chat/status?workspaceId=${wid}&sessionId=${sessionId}`);
        if (!cancelled) setRunning(st.running);
      } catch { /* 历史拉取失败不阻塞对话，错误随发送暴露 */ }
    })();
    getChatSocket().send({ type: 'register', workspaceId: wid, sessionId });
    return () => { cancelled = true; };
  }, [wid, sessionId]);

  // ---- WS 事件（ChatPage 简化子集）----
  useEffect(() => {
    if (!wid || !sessionId) return;
    const apply = (evt: { type: string; content: string; toolCallId?: string }) => {
      if (evt.type === 'text') {
        setMsgs((prev) => {
          const last = prev[prev.length - 1];
          if (last && last.type === 'text' && last.running) {
            return [...prev.slice(0, -1), { ...last, content: last.content + evt.content }];
          }
          return [...prev, { type: 'text', content: evt.content, running: true }];
        });
      } else if (evt.type === 'tool') {
        setMsgs((prev) => [...prev, { type: 'tool', toolName: evt.content, content: '执行中…', running: true }]);
      } else if (evt.type === 'tool_result') {
        setMsgs((prev) => {
          const i = prev.map((m) => m.type).lastIndexOf('tool');
          if (i < 0) return prev;
          const m = prev[i];
          return [...prev.slice(0, i), { ...m, content: '完成', running: false, toolResult: evt.content.slice(0, 200) }, ...prev.slice(i + 1)];
        });
      } else if (evt.type === 'subagent') {
        setMsgs((prev) => [...prev, { type: 'subagent', subagentName: evt.content, content: '', running: true }]);
      } else if (evt.type === 'subagent_end') {
        setMsgs((prev) => prev.map((m) => (m.type === 'subagent' && m.running ? { ...m, running: false } : m)));
      } else if (evt.type === 'confirm') {
        setRunning(false);
        try {
          const json = JSON.parse(evt.content);
          const tools = (json.tools || []).map((t: { name: string; input: unknown }) => ({ name: t.name, input: JSON.stringify(t.input || {}) }));
          setPending({ tools, raw: evt.content });
        } catch {
          setPending({ tools: [{ name: '工具', input: evt.content }], raw: evt.content });
        }
      } else if (evt.type === 'error') {
        setRunning(false);
        setError(evt.content || '发生错误');
      } else if (evt.type === 'end' || evt.type === 'stopped') {
        setRunning(false);
        setMsgs((prev) => prev.map((m) => (m.running ? { ...m, running: false } : m)));
      }
    };
    return subscribeChatSocket((evtWid, evtSid, evt) => {
      if (!evtSid) { apply(evt); return; }
      if (evtSid === sidRef.current && (!evtWid || evtWid === wid)) apply(evt);
    });
  }, [wid, sessionId]);

  // ---- 空闲期 pending 兜底轮询（与 ChatPage 同策略：8 秒）----
  useEffect(() => {
    if (!wid || !sessionId || running || pending) return;
    const timer = setInterval(() => {
      getChatSocket().send({ type: 'pending', workspaceId: wid, sessionId });
    }, 8000);
    return () => clearInterval(timer);
  }, [wid, sessionId, running, pending]);

  // 新消息滚到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [msgs.length, running]);

  useEffect(() => () => {
    window.clearInterval(voiceTimerRef.current);
    try { recorderRef.current?.stop(); } catch { /* 未在录音 */ }
  }, []);

  const send = () => {
    const text = input.trim();
    if (!text || running || !wid || !sessionId) return;
    setMsgs((prev) => [...prev, { type: 'user', content: text }]);
    setInput('');
    setRunning(true);
    setError('');
    setPending(null);
    getChatSocket().send({ type: 'chat', workspaceId: wid, sessionId, message: text, attachments: [], skillName: null });
  };

  const stop = () => {
    getChatSocket().send({ type: 'stop', workspaceId: wid, sessionId });
    setRunning(false);
    setMsgs((prev) => prev.map((m) => (m.running ? { ...m, running: false } : m)));
  };

  const decideConfirm = (action: 'once' | 'turn' | 'always' | 'deny') => {
    if (!pending) return;
    getChatSocket().send({
      type: 'confirm', workspaceId: wid, sessionId,
      toolNames: pending.tools.map((t) => t.name), action,
    });
    setPending(null);
  };

  const newSession = async () => {
    try {
      const s = await postJson<SessionItem>(`/api/workspaces/${wid}/sessions`, { title: '新会话' });
      setSessionTitle(s.title);
      setSessionId(s.id);
      setActiveSession(wid, s.id);
      setMsgs([]);
      setRunning(false);
      setPending(null);
    } catch (e) {
      showHint(`新建失败：${e instanceof Error ? e.message : String(e)}`);
    }
  };

  // ---- 语音输入（与 ChatPage 同流程：录音 → /api/asr/transcribe → 回填）----
  const toggleVoice = async () => {
    if (voiceState === 'transcribing') return;
    if (voiceState === 'idle') {
      try {
        const rec = createVoiceRecorder();
        await rec.start();
        recorderRef.current = rec;
        setVoiceSeconds(0);
        window.clearInterval(voiceTimerRef.current);
        voiceTimerRef.current = window.setInterval(() => setVoiceSeconds((s) => s + 1), 1000);
        setVoiceState('recording');
      } catch (e) {
        showHint(e instanceof DOMException && e.name === 'NotAllowedError'
          ? '🎤 麦克风权限被拒绝' : `🎤 无法录音：${e instanceof Error ? e.message : String(e)}`, 6000);
      }
      return;
    }
    const rec = recorderRef.current;
    if (!rec) { setVoiceState('idle'); return; }
    window.clearInterval(voiceTimerRef.current);
    setVoiceState('transcribing');
    try {
      const blob = rec.stop();
      if (blob.size < 2000) throw new Error('录音太短');
      const form = new FormData();
      form.append('file', blob, 'voice.wav');
      const { text } = await postForm<{ text: string }>('/api/asr/transcribe', form);
      if (text) setInput((prev) => (prev ? `${prev} ${text}` : text));
      else showHint('🎤 未识别到内容');
    } catch (e) {
      showHint(`🎤 转写失败：${e instanceof Error ? e.message : String(e)}`, 8000);
    } finally {
      setVoiceState('idle');
      recorderRef.current = null;
    }
  };

  return (
    <div className="mchat">
      <div className="mchat-topbar">
        <button className="mchat-back" onClick={() => navigate('/m')} title="返回">←</button>
        <span className="mchat-title">{sessionTitle || (loaded ? '会话' : '加载中…')}</span>
        <button className="mchat-new" onClick={() => void newSession()} title="新会话">＋</button>
      </div>

      <div className="mchat-msgs">
        {msgs.map((m, i) => {
          if (m.type === 'user') return <div key={i} className="mchat-msg user"><div className="bubble">{m.content}</div></div>;
          if (m.type === 'tool') {
            return <div key={i} className="mchat-msg tool">{m.running ? '⏳' : '🔧'} {m.toolName} {m.running ? m.content : ''}</div>;
          }
          if (m.type === 'subagent') {
            return <div key={i} className="mchat-msg tool">🤖 {m.subagentName} {m.running ? '运行中…' : '完成'}</div>;
          }
          return (
            <div key={i} className="mchat-msg ai">
              <div className="bubble md-content" dangerouslySetInnerHTML={{ __html: md(m.content || '') }} />
              {m.running && <span className="cursor">▍</span>}
            </div>
          );
        })}
        {running && msgs[msgs.length - 1]?.type !== 'text' && <div className="mchat-msg ai"><span className="cursor">▍</span></div>}
        <div ref={bottomRef} />
      </div>

      {(error || hint) && <div className={`mchat-hint ${error ? 'err' : ''}`}>{error || hint}</div>}

      {pending && (
        <div className="mchat-confirm">
          <div className="mchat-confirm-title">⚠️ 工具执行确认</div>
          {pending.tools.map((t, i) => (
            <div key={i} className="mchat-confirm-tool">🔧 {t.name}</div>
          ))}
          <div className="mchat-confirm-actions">
            <button className="btn small" onClick={() => decideConfirm('once')}>允许一次</button>
            <button className="btn small" onClick={() => decideConfirm('turn')}>本回合</button>
            <button className="btn small" onClick={() => decideConfirm('always')}>总是</button>
            <button className="btn small danger" onClick={() => decideConfirm('deny')}>拒绝</button>
          </div>
        </div>
      )}

      <div className="mchat-input">
        <button
          className={`mchat-voice${voiceState === 'recording' ? ' rec' : ''}`}
          onClick={() => void toggleVoice()}
          disabled={voiceState === 'transcribing'}
          title={voiceState === 'recording' ? '停止并转写' : '语音输入'}
        >{voiceState === 'recording' ? `${voiceSeconds}s` : voiceState === 'transcribing' ? '…' : '🎤'}</button>
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.nativeEvent.isComposing) return;
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
          }}
          placeholder={running ? '运行中…' : '输入消息'}
          rows={1}
        />
        {running
          ? <button className="mchat-send stop" onClick={stop} title="停止">⏹</button>
          : <button className="mchat-send" onClick={send} disabled={!input.trim()} title="发送">➤</button>}
      </div>
    </div>
  );
}
