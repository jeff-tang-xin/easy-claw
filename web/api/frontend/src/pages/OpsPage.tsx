// 运维工作区主页：SSH 交互终端（多连接多 tab）+ 智能幕布（agent curtain）
// 交互模型（用户澄清后的定稿）：
//   - 每条活跃 SSH 连接一个终端 tab：远程 shell 的真实输出（含 ANSI）原样渲染；
//     顶栏「上传文件 / 断开」作用于当前激活 tab 的连接；
//   - 底部一条「智能幕布」输入框，按首字符分流：
//       中文开头 → 发给智能体（chat WS），回复/工具调用/确认提示都渲染进当前激活 tab；
//       其他内容 → 直接写进当前激活 tab 的远程 shell（term_data），不经过 LLM；
//   - 右侧栏：SSH 连接配置管理（CRUD + 连接/断开 + 文件上传）。
// 确认按钮只给「允许一次 / 拒绝」：ops 模式白名单机制关闭（AgentOrchestrator.whitelistEnabled=false），
// 回合/永久授权后端一律不生效，渲染出来只会造成「点了没反应」的错觉。
import {useCallback, useEffect, useRef, useState} from 'react';
import {useParams} from 'react-router-dom';
import {Terminal} from '@xterm/xterm';
import {FitAddon} from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import {del, getJson, postJson, putJson} from '../api';
import {createOpsSocket, OpsChatEvent, OpsSocket} from '../opsSocket';
import '../ops.css';

/** 连接配置（脱敏视图：不含任何凭证字段，只有 has* 布尔位） */
interface Conn {
  id: number;
  workspaceId: string;
  name: string;
  host: string;
  port: number;
  username: string;
  authType: 'password' | 'key';
  hasPassword: boolean;
  hasKey: boolean;
}

/** 一条活跃连接（SshConnectionService.status.connections 元素） */
interface ActiveConn {
  connId: number;
  connName: string;
  host: string;
  username: string;
  connectedAt: string;
}

/** 连接状态（SshConnectionService.status 的 JSON） */
interface OpsStatus {
  connections: ActiveConn[];
}

/** 一个已打开的终端 tab：对应一条活跃 SSH 连接 + 其专属智能体会话（一个连接一个 session） */
interface TermTab {
  terminalId: string;
  connId: number;
  /** 该连接专属的智能体会话（对话事件按 sessionId 路由回本 tab） */
  sessionId: string;
  title: string;
  host: string;
  username: string;
  connected: boolean;
  /** 该 tab 的智能体回合是否执行中（幕布发送/确认按 tab 各自判断） */
  busy: boolean;
  /** 幕布输入模式：agent=自然语言给智能体（默认）；shell=命令直写远程 shell。终端键盘直通不受此影响 */
  mode: 'agent' | 'shell';
}

/** confirm 事件 content：{replyId, tools:[{id,name,input}]}（AgentService.buildConfirmJson） */
interface PendingConfirm {
  replyId: string;
  tools: { id: string; name: string; input: unknown }[];
}

/** tab 的 terminalId：每 tab 实例唯一（内嵌 connId 便于排查）。
 * 同一连接将来开多个 tab（复制 tab）时各自持有独立 PTY 与会话：
 * 后端 terminals 按 terminalId 索引且条目自带 connId，会话绑定按 sessionId 键，均不受影响 */
let tabSeq = 0;
const nextTerminalId = (connId: number) => `ops-conn-${connId}-${++tabSeq}`;

/** agent 模式下可直接执行的只读 shell 命令白名单：命中直写远程 shell，不调用智能体。
 * 只收无副作用的查看/导航类命令；写操作/进程控制/电源操作一律走智能体（保留确认机制） */
const SHELL_SHORTCUTS = new Set([
  'ls', 'll', 'la', 'l', 'pwd', 'cd', 'clear', 'cls', 'echo', 'history',
  'whoami', 'id', 'hostname', 'uname', 'date', 'uptime', 'free', 'df', 'du',
  'ps', 'cat', 'head', 'tail', 'grep', 'wc', 'which',
]);

/** agent 模式白名单判定：首词在白名单，且整行无管道/重定向/链/命令替换
 * （`|<>;&`、反引号、$() 会改变命令语义，如 `cat > file`、`echo x; rm`，必须交给智能体或 SH 模式） */
const isShellShortcut = (s: string) => {
  if (/[|<>&;`]|\$\(/.test(s)) return false;
  const first = s.trim().split(/\s+/)[0] ?? '';
  return SHELL_SHORTCUTS.has(first);
};

/** AI 模式终端行编辑的提示符（本地回显，非远程 shell） */
const AGENT_PROMPT = '\x1b[36m🤖 ›\x1b[0m ';

/** 终端里渲染工具入参/结果时截断，避免一条长输出刷屏 */
const truncate = (s: string, n = 300) => (s.length > n ? s.slice(0, n) + '…' : s);
/** 幕布文本进终端前统一换行：xterm 里裸 \n 只下移不回车（LF≠CRLF），流式段落会打成乱阶梯 */
const toTermText = (s: string) => (s ?? '').replace(/\r?\n/g, '\r\n');

/** base64 → 原始字节（term_data 的 data 字段；含 ANSI 序列与非 UTF-8 字节，不能走文本解码） */
function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

interface ConnForm {
  name: string;
  host: string;
  port: string;
  username: string;
  authType: 'password' | 'key';
  password: string;
  privateKey: string;
  keyPassphrase: string;
}

const EMPTY_FORM: ConnForm = {
  name: '', host: '', port: '22', username: '',
  authType: 'password', password: '', privateKey: '', keyPassphrase: '',
};

export default function OpsPage() {
  const {workspaceId} = useParams<{ workspaceId: string }>();

  // 连接面板状态
  const [conns, setConns] = useState<Conn[]>([]);
  const [opsError, setOpsError] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<ConnForm>(EMPTY_FORM);
  const [savingConn, setSavingConn] = useState(false);

  // 终端 tab 状态（tabsRef/activeTabRef 是回调闭包里读最新值的镜像）
  const [tabs, setTabs] = useState<TermTab[]>([]);
  const [activeTabId, setActiveTabId] = useState<string | null>(null);
  const tabsRef = useRef<TermTab[]>([]);
  const activeTabRef = useRef<string | null>(null);
  useEffect(() => { tabsRef.current = tabs; }, [tabs]);
  useEffect(() => { activeTabRef.current = activeTabId; }, [activeTabId]);

  // 智能幕布（对话）状态：会话按 tab 各自持有（TermTab.sessionId），页面不再有全局会话
  const [curtain, setCurtain] = useState('');
  /** terminalId → 待确认请求（多 tab 并行回合时各自独立） */
  const [pendingConfirms, setPendingConfirms] = useState<Record<string, PendingConfirm>>({});

  // 终端实例 / WS / 文件选择（ref：不参与渲染，且回调闭包里要读到最新值）
  /** terminalId → 已创建的 xterm 实例（tab 激活时惰性创建，切走后保留滚动回溯） */
  const termsRef = useRef<Map<string, {term: Terminal; fit: FitAddon}>>(new Map());
  /** terminalId → AI 模式行编辑缓冲（已键入未提交；SH 模式不经过此缓冲） */
  const lineBufRef = useRef<Map<string, string>>(new Map());
  /** terminalId → 终端容器 div（JSX ref 回填，供 xterm.open 挂载） */
  const containersRef = useRef<Map<string, HTMLDivElement | null>>(new Map());
  const sockRef = useRef<OpsSocket | null>(null);
  /** WS 是否已建立（refreshStatus 晚于 onOpen 到达时决定要不要补 reattach） */
  const sockOpenRef = useRef(false);
  const fileRef = useRef<HTMLInputElement | null>(null);

  /** 当前激活 tab（闭包里用 activeTabRef + tabsRef 查） */
  const currentTab = (): TermTab | null =>
    tabsRef.current.find((t) => t.terminalId === activeTabRef.current) ?? null;

  /** 往指定终端写内容（tab 不存在时静默丢弃） */
  const termWrite = (terminalId: string | null, text: string | Uint8Array) => {
    if (!terminalId) return;
    termsRef.current.get(terminalId)?.term.write(text);
  };

  /** 为一条连接创建专属智能体会话（一个连接一个 session，对话与输出互不串扰） */
  const createSessionFor = useCallback(async (connName: string): Promise<string> => {
    if (!workspaceId) return '';
    const s = await postJson<{ id: string }>(
      `/api/workspaces/${workspaceId}/sessions`, {title: `运维 · ${connName}`});
    return s.id;
  }, [workspaceId]);

  /** 新增/更新 tab（terminalId 每 tab 实例唯一）；activate=false 用于刷新恢复不抢焦点。
   * 建立后立即注册其会话（事件据此定向投递回该 tab） */
  const upsertTab = useCallback((conn: {connId: number; connName: string; host: string; username: string},
                                 sessionId: string, activate = true) => {
    const terminalId = nextTerminalId(conn.connId);
    setTabs((prev) => [...prev, {
      terminalId,
      connId: conn.connId,
      sessionId,
      title: conn.connName,
      host: conn.host,
      username: conn.username,
      connected: true,
      busy: false,
      mode: 'agent',
    }]);
    if (activate) {
      setActiveTabId(terminalId);
    }
    // WS 未就绪时 send 静默丢弃，onOpen 的 registerAllSessions 兜底
    if (sessionId) {
      sockRef.current?.send({type: 'register', workspaceId, sessionId, connId: conn.connId});
    }
    return terminalId;
  }, [workspaceId]);

  /** 移除 tab（终端实例由下方的清理 effect 统一 dispose） */
  const removeTab = (terminalId: string) => {
    setTabs((prev) => prev.filter((t) => t.terminalId !== terminalId));
    containersRef.current.delete(terminalId);
    setPendingConfirms((prev) => {
      if (!(terminalId in prev)) return prev;
      const next = {...prev};
      delete next[terminalId];
      return next;
    });
    if (activeTabRef.current === terminalId) {
      setActiveTabId((cur) => {
        if (cur !== terminalId) return cur;
        const rest = tabsRef.current.filter((t) => t.terminalId !== terminalId);
        return rest.length ? rest[rest.length - 1].terminalId : null;
      });
    }
  };

  // ==================== 终端生命周期（每 tab 一个 xterm，激活时惰性创建） ====================

  useEffect(() => {
    const active = tabs.find((t) => t.terminalId === activeTabId);
    if (!active) return;
    if (termsRef.current.has(active.terminalId)) {
      // 已有实例：切 tab 时重新量尺寸并聚焦
      const inst = termsRef.current.get(active.terminalId)!;
      window.setTimeout(() => {
        try { inst.fit.fit(); } catch { /* 容器未就绪，忽略 */ }
        if (activeTabRef.current === active.terminalId) inst.term.focus();
      }, 30);
      return;
    }
    const el = containersRef.current.get(active.terminalId);
    if (!el) return;
    const term = new Terminal({
      fontFamily: '"Cascadia Mono", Consolas, "Courier New", monospace',
      fontSize: 13,
      cursorBlink: true,
      theme: {background: '#101418'},
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(el);
    term.writeln(`\x1b[90m${active.title}（${active.username}@${active.host}）\x1b[0m`);
    term.writeln(`\x1b[90m徽标 AI（默认）：在此直接打字与智能体对话，ls/pwd 等只读命令直接执行；徽标 SH：键盘逐键直通远程 shell（Ctrl+C/Tab/↑ 可用）。点 tab 徽标切换\x1b[0m`);
    // 键盘分流：AI 模式本地行编辑（回车提交分流，不直通 PTY）；SH 模式逐键直通远程 shell
    term.onData((data) => {
      const tab = tabsRef.current.find((t) => t.terminalId === active.terminalId);
      if (!tab?.connected) return;
      if (tab.mode === 'agent') {
        agentKeystroke(tab, data);
      } else {
        sockRef.current?.send({type: 'term_data', workspaceId, terminalId: active.terminalId, data});
      }
    });
    // 尺寸同步：本地 fit 后通知远端 PTY（当前 SSHD 版本仅记录日志，升级后即生效）
    term.onResize(({cols, rows}) => {
      if (!workspaceId) return;
      sockRef.current?.send({type: 'term_resize', workspaceId, terminalId: active.terminalId, cols, rows});
    });
    termsRef.current.set(active.terminalId, {term, fit});
    // 布局稳定后再 fit + 请求打开远程 shell（PTY 行列数取自 fit 后的实际尺寸）。
    // 不在 effect cleanup 里 clearTimeout：快速切 tab / tabs 变化会触发 cleanup，
    // 清掉定时器会丢 term_open（远程 shell 没开、终端黑屏）；term_open 幂等，晚到无害
    const timer = window.setTimeout(() => {
      try { fit.fit(); } catch { /* 忽略 */ }
      if (workspaceId) {
        sockRef.current?.send({
          type: 'term_open', workspaceId, connId: active.connId,
          terminalId: active.terminalId, cols: term.cols, rows: term.rows,
        });
      }
      if (activeTabRef.current === active.terminalId) term.focus();
    }, 50);
  }, [tabs, activeTabId, workspaceId]);

  // tab 被移除时释放其 xterm 实例
  useEffect(() => {
    const alive = new Set(tabs.map((t) => t.terminalId));
    for (const [tid, inst] of termsRef.current) {
      if (!alive.has(tid)) {
        inst.term.dispose();
        termsRef.current.delete(tid);
      }
    }
  }, [tabs]);

  // 窗口尺寸变化：只 fit 当前激活 tab（隐藏 tab 的尺寸为 0，fit 无意义）
  useEffect(() => {
    const onResize = () => {
      const inst = termsRef.current.get(activeTabRef.current ?? '');
      if (inst) {
        try { inst.fit.fit(); } catch { /* 忽略 */ }
      }
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  // ==================== 会话与 WS ====================

  // 会话模型：一个连接一个智能体会话（TermTab.sessionId，连接建立时创建）。
  // 对话事件按其 sessionId 路由回所属 tab，切换 tab 不会把 A 连接的回复串到 B 连接的屏幕；
  // remote_shell 亦按会话绑定定向执行（后端 bindSession，chat 消息携带 connId）

  /** WS 建立后注册全部 tab 的会话（后端订阅映射随旧 WS 关闭而清空，重连后需重注册） */
  const registerAllSessions = useCallback(() => {
    const sock = sockRef.current;
    if (!sock || !workspaceId) return;
    for (const tab of tabsRef.current) {
      if (tab.sessionId) {
        sock.send({type: 'register', workspaceId, sessionId: tab.sessionId, connId: tab.connId});
      }
    }
  }, [workspaceId]);

  /** WS（重）连后为全部已连接 tab 重新打开远程 shell（后端按 terminalId 先关旧再开新） */
  const reattachAll = useCallback(() => {
    const sock = sockRef.current;
    if (!sock || !workspaceId) return;
    for (const tab of tabsRef.current) {
      if (!tab.connected) continue;
      const inst = termsRef.current.get(tab.terminalId);
      sock.send({
        type: 'term_open', workspaceId, connId: tab.connId, terminalId: tab.terminalId,
        cols: inst?.term.cols ?? 80, rows: inst?.term.rows ?? 24,
      });
    }
  }, [workspaceId]);

  /** 对话事件按其所属会话路由回对应 tab 的终端屏（一个连接一个会话，互不串扰） */
  const renderChatEvent = useCallback((sid: string, ev: OpsChatEvent) => {
    const tab = tabsRef.current.find((t) => t.sessionId === sid) ?? null;
    const term = tab ? termsRef.current.get(tab.terminalId)?.term ?? null : null;
    // 回合结束只复位「发起该回合的 tab」的 busy；tab 已被移除时无需复位
    const clearBusy = () => {
      if (!tab) return;
      setTabs((prev) => prev.map((t) => (t.sessionId === sid ? {...t, busy: false} : t)));
      // 回合结束补对话提示符（AI 模式），引导继续输入
      const latest = tabsRef.current.find((t) => t.sessionId === sid);
      if (latest?.mode === 'agent') term?.write(AGENT_PROMPT);
    };
    switch (ev.type) {
      case 'text':
        term?.write(`\x1b[92m${toTermText(ev.content ?? '')}\x1b[0m`);
        break;
      case 'reasoning':
        term?.write(`\x1b[90m${toTermText(ev.content ?? '')}\x1b[0m`);
        break;
      case 'tool':
        term?.write(`\r\n\x1b[33m⚙ ${ev.content ?? ''}\x1b[0m\r\n`);
        break;
      case 'tool_args':
        term?.write(`\x1b[90m  ↳ ${truncate(ev.content ?? '')}\x1b[0m\r\n`);
        break;
      case 'tool_result':
        term?.write(`\x1b[90m  ⇐ ${truncate(ev.content ?? '')}\x1b[0m\r\n`);
        break;
      case 'confirm': {
        try {
          const parsed = JSON.parse(ev.content ?? '') as PendingConfirm;
          if (tab) {
            setPendingConfirms((prev) => ({...prev, [tab.terminalId]: parsed}));
            term?.write(`\r\n\x1b[36m⏸ 待确认：${parsed.tools.map((t) => t.name).join('、')}\x1b[0m\r\n`);
          }
        } catch { /* 坏包忽略 */ }
        break;
      }
      case 'error':
        term?.write(`\r\n\x1b[31m✗ ${ev.content ?? '执行出错'}\x1b[0m\r\n`);
        clearBusy();
        break;
      case 'end':
      case 'stopped':
        term?.write('\r\n');
        clearBusy();
        break;
      default:
        // tool_end / context / file_changed / status 等终端页不渲染
        break;
    }
  }, []);

  useEffect(() => {
    if (!workspaceId) return;
    const sock = createOpsSocket({
      onOpen: () => {
        sockOpenRef.current = true;
        // WS（重）连后：重新注册全部 tab 会话 + 为所有仍活跃的连接重新挂载远程 shell
        registerAllSessions();
        reattachAll();
      },
      // 终端输出按 terminalId 路由到对应 tab（term_* 是裸 JSON，terminalId 由后端原样带回）
      onData: (tid, b64) => termWrite(tid, b64ToBytes(b64)),
      onError: (tid, msg) => termWrite(tid, `\r\n\x1b[31m${msg}\x1b[0m\r\n`),
      onChatEvent: renderChatEvent,
      onClose: () => {
        sockOpenRef.current = false;
        for (const inst of termsRef.current.values()) {
          inst.term.write('\r\n\x1b[90m—— WebSocket 已断开，请刷新页面 ——\x1b[0m\r\n');
        }
      },
    });
    sockRef.current = sock;
    return () => {
      sockOpenRef.current = false;
      sock.close();
      sockRef.current = null;
    };
  }, [workspaceId, registerAllSessions, reattachAll, renderChatEvent]);

  // ==================== 连接管理 ====================

  const loadConns = useCallback(async () => {
    if (!workspaceId) return;
    try {
      setConns(await getJson<Conn[]>(`/api/ops/connections?workspaceId=${encodeURIComponent(workspaceId)}`));
    } catch (e) {
      setOpsError(String(e));
    }
  }, [workspaceId]);

  const refreshStatus = useCallback(async () => {
    if (!workspaceId) return;
    try {
      const st = await getJson<OpsStatus>(`/api/ops/status?workspaceId=${encodeURIComponent(workspaceId)}`);
      // 页面刷新恢复：服务端仍活跃的连接补建 tab（按 connId 去重；各配一个新会话，首个 tab 抢激活）
      for (const c of st.connections ?? []) {
        if (tabsRef.current.some((t) => t.connId === c.connId)) continue;
        let sid = '';
        try {
          sid = await createSessionFor(c.connName);
        } catch {
          setOpsError(`「${c.connName}」的智能体会话创建失败 —— 终端可用，幕布对话不可用（重连可重试）`);
        }
        upsertTab(c, sid, activeTabRef.current === null);
      }
      // 状态到达晚于 WS 建立时，onOpen 里的 reattachAll 已跑过 —— 这里补一次挂载
      if (sockOpenRef.current) reattachAll();
    } catch { /* 状态接口失败不阻塞页面 */ }
  }, [workspaceId, reattachAll, createSessionFor, upsertTab]);

  useEffect(() => {
    void loadConns();
    void refreshStatus();
  }, [loadConns, refreshStatus]);

  const connect = async (id: number) => {
    if (!workspaceId) return;
    // 已有该连接的活跃 tab：直接切换过去，不重连（重连会丢 shell 状态）。
    // 「同连接一个 tab」目前是 UX 规则而非结构限制：tab 身份（terminalId）每实例唯一，
    // 将来放开为「复制 tab」只需在这里允许并存并各建会话，其余链路不变
    const existing = tabsRef.current.find((t) => t.connId === id);
    if (existing?.connected) {
      setActiveTabId(existing.terminalId);
      return;
    }
    setOpsError('');
    try {
      const st = await postJson<OpsStatus>(
        `/api/ops/connections/${id}/connect?workspaceId=${encodeURIComponent(workspaceId)}`, {});
      const c = (st.connections ?? []).find((x) => x.connId === id);
      if (c) {
        // 该连接此前的断开 tab 已失效（新 tab 新终端新会话），先移除
        for (const stale of tabsRef.current.filter((t) => t.connId === id && !t.connected)) {
          removeTab(stale.terminalId);
        }
        let sid = '';
        try {
          sid = await createSessionFor(c.connName);
        } catch {
          setOpsError('智能体会话创建失败 —— 终端可用，幕布对话不可用（重连可重试）');
        }
        upsertTab(c, sid);
      } else {
        setOpsError('连接未建立（服务端无该活跃连接）');
      }
    } catch (e) {
      setOpsError(String(e));
    }
  };

  const disconnect = async () => {
    const tab = currentTab();
    if (!tab || !workspaceId) return;
    try {
      // 先标记断开：立即停掉该 tab 的键盘直通
      setTabs((prev) => prev.map((t) => (t.terminalId === tab.terminalId ? {...t, connected: false} : t)));
      await postJson(
        `/api/ops/disconnect?workspaceId=${encodeURIComponent(workspaceId)}&connId=${tab.connId}`, {});
      termWrite(tab.terminalId, '\r\n\x1b[90m—— 已断开 SSH 连接 ——\x1b[0m\r\n');
      removeTab(tab.terminalId);
    } catch (e) {
      setOpsError(String(e));
      // 断开失败：连接其实还在，恢复标记
      setTabs((prev) => prev.map((t) => (t.terminalId === tab.terminalId ? {...t, connected: true} : t)));
    }
  };

  const openForm = (c?: Conn) => {
    setOpsError('');
    if (c) {
      setEditingId(c.id);
      setForm({
        name: c.name, host: c.host, port: String(c.port), username: c.username,
        authType: c.authType, password: '', privateKey: '', keyPassphrase: '',
      });
    } else {
      setEditingId(null);
      setForm(EMPTY_FORM);
    }
    setShowForm(true);
  };

  const closeForm = () => {
    setShowForm(false);
    setEditingId(null);
  };

  const saveConn = async () => {
    if (!workspaceId) return;
    if (!form.name.trim() || !form.host.trim() || !form.username.trim()) {
      setOpsError('名称 / 主机 / 用户名不能为空');
      return;
    }
    setSavingConn(true);
    setOpsError('');
    try {
      const body: Record<string, unknown> = {
        workspaceId,
        name: form.name.trim(),
        host: form.host.trim(),
        port: Number(form.port) || 22,
        username: form.username.trim(),
        authType: form.authType,
      };
      // 凭证只在填写时提交；编辑留空 = 保持原值（后端 applySecrets 语义）
      if (form.authType === 'password' && form.password) body.password = form.password;
      if (form.authType === 'key' && form.privateKey) body.privateKey = form.privateKey;
      if (form.keyPassphrase) body.keyPassphrase = form.keyPassphrase;
      if (editingId == null) {
        await postJson('/api/ops/connections', body);
      } else {
        await putJson(`/api/ops/connections/${editingId}`, body);
      }
      closeForm();
      setForm(EMPTY_FORM);
      await loadConns();
    } catch (e) {
      setOpsError(String(e));
    } finally {
      setSavingConn(false);
    }
  };

  const removeConn = async (id: number) => {
    if (!workspaceId || !window.confirm('确定删除该连接配置？已保存的凭证将一并删除。')) return;
    try {
      // 该连接还开着终端：先断开再删，避免留下指向已删配置的活连接
      const tab = tabsRef.current.find((t) => t.connId === id);
      if (tab) {
        await postJson(
          `/api/ops/disconnect?workspaceId=${encodeURIComponent(workspaceId)}&connId=${id}`, {});
        termWrite(tab.terminalId, '\r\n\x1b[90m—— 连接配置已删除，终端已关闭 ——\x1b[0m\r\n');
        removeTab(tab.terminalId);
      }
      await del(`/api/ops/connections/${id}?workspaceId=${encodeURIComponent(workspaceId)}`);
      await loadConns();
    } catch (e) {
      setOpsError(String(e));
    }
  };

  const uploadDirRef = useRef('');

  const upload = async (f: File) => {
    const tab = currentTab();
    if (!tab || !workspaceId) return;
    // 目标目录：弹窗询问并记住上次输入；留空 = 远程家目录（SFTP "."）
    const dir = window.prompt('上传到远程哪个目录？（留空 = 家目录）', uploadDirRef.current || '.');
    if (dir === null) return; // 用户取消
    const targetDir = dir.trim();
    uploadDirRef.current = targetDir;
    setOpsError('');
    try {
      const fd = new FormData();
      fd.append('file', f);
      const qs = `workspaceId=${encodeURIComponent(workspaceId)}&connId=${tab.connId}`
        + (targetDir ? `&targetDir=${encodeURIComponent(targetDir)}` : '');
      const res = await fetch(`/api/ops/upload?${qs}`, {method: 'POST', body: fd});
      if (!res.ok) {
        const detail = (await res.text()).trim();
        throw new Error(detail || (res.status === 413 ? '文件超过大小限制（500MB）' : `HTTP ${res.status}`));
      }
      termWrite(tab.terminalId, `\x1b[90m已上传 ${f.name}（${f.size} 字节）-> ${targetDir || '家目录'}\x1b[0m\r\n`);
    } catch (e) {
      setOpsError(String(e));
    }
  };

  // ==================== 智能幕布 ====================

  /** 切换 tab 的幕布输入模式（agent ↔ shell）；点 tab 上的模式徽标触发 */
  const toggleMode = (terminalId: string) => {
    // 读切换前的 mode：agent→shell 清行缓冲交还远程；shell→agent 补对话提示符
    const prevMode = tabsRef.current.find((t) => t.terminalId === terminalId)?.mode;
    setTabs((prev) => prev.map((t) => (
      t.terminalId === terminalId ? {...t, mode: t.mode === 'agent' ? 'shell' : 'agent'} : t
    )));
    if (prevMode === 'agent') {
      lineBufRef.current.set(terminalId, '');
      termWrite(terminalId, '\r\x1b[K\x1b[90m—— SH 模式：键盘直通远程 shell ——\x1b[0m\r\n');
    } else if (prevMode === 'shell') {
      termWrite(terminalId, `\r\n${AGENT_PROMPT}`);
    }
  };

  /** AI 模式终端 keystroke：本地行编辑（远程 PTY 不收键），回车提交分流。
   * 中文 IME 由 xterm 处理：composition 确认后 onData 收到完整串，按普通字符回显 */
  const agentKeystroke = (tab: TermTab, data: string) => {
    let buf = lineBufRef.current.get(tab.terminalId) ?? '';
    for (const ch of data) {
      if (ch === '\r') {
        const line = buf.trim();
        buf = '';
        lineBufRef.current.set(tab.terminalId, '');
        // 擦掉本地回显行：shell 路径由远程回显重打，AI 路径由对话回显重打
        termWrite(tab.terminalId, '\r\x1b[K');
        if (line) dispatchLine(tab, line);
        else termWrite(tab.terminalId, AGENT_PROMPT);
        return;
      }
      if (ch === '\x7f') {            // 退格（按码点删，emoji/扩展中文不残留半字符）
        const cps = Array.from(buf);
        cps.pop();
        buf = cps.join('');
      } else if (ch === '\x03') {     // Ctrl+C：放弃本行
        buf = '';
        lineBufRef.current.set(tab.terminalId, '');
        termWrite(tab.terminalId, '^C\r\n' + AGENT_PROMPT);
        return;
      } else if (ch >= ' ') {         // 可打印字符（含 IME 提交的中文串）；其余控制字符忽略
        buf += ch;
      }
    }
    lineBufRef.current.set(tab.terminalId, buf);
    // 整行重绘（中文宽字符列宽交给终端算，比逐字符退格可靠）
    termWrite(tab.terminalId, `\r\x1b[K${AGENT_PROMPT}${buf}`);
  };

  /** 一行输入的分流执行（终端行编辑与底部幕布输入共用）：
   * SH 模式或 AI 模式命中只读命令白名单 → 直写远程 shell；其余一律发给智能体 */
  const dispatchLine = (tab: TermTab, text: string) => {
    const sock = sockRef.current;
    if (!sock || !workspaceId) return;
    if (tab.mode === 'shell' || isShellShortcut(text)) {
      if (!tab.connected) {
        termWrite(tab.terminalId, '\x1b[31m该连接已断开 —— 请重新连接\x1b[0m\r\n');
        return;
      }
      // 直接写进该 tab 的远程 shell：远端回显即用户所见，本地不再重复打印
      sock.send({type: 'term_data', workspaceId, terminalId: tab.terminalId, data: text + '\r'});
      return;
    }
    if (!tab.sessionId) {
      termWrite(tab.terminalId, '\x1b[31m该 tab 的智能体会话未就绪 —— 重新连接可重试\x1b[0m\r\n');
      return;
    }
    if (tab.busy) {
      termWrite(tab.terminalId, '\x1b[33m智能体正在执行中，请等当前回合结束\x1b[0m\r\n');
      return;
    }
    termWrite(tab.terminalId, `\r\n\x1b[36m🤖 你：\x1b[0m ${text}\r\n`);
    setTabs((prev) => prev.map((t) => (t.terminalId === tab.terminalId ? {...t, busy: true} : t)));
    // connId 随消息上行：后端把该会话绑定到这条连接，remote_shell 定向执行（一个连接一个会话）
    sock.send({type: 'chat', workspaceId, sessionId: tab.sessionId, connId: tab.connId, message: text});
  };

  const submitCurtain = () => {
    const text = curtain.trim();
    if (!text || !workspaceId) return;
    const tab = currentTab();
    if (!tab) {
      setOpsError('未连接远程主机 —— 先在右侧建立 SSH 连接');
      return;
    }
    dispatchLine(tab, text);
    setCurtain('');
  };

  const sendConfirm = (action: 'once' | 'deny') => {
    const tab = currentTab();
    const pc = tab ? pendingConfirms[tab.terminalId] : undefined;
    if (!tab || !pc) return;
    sockRef.current?.send({
      type: 'confirm',
      workspaceId,
      sessionId: tab.sessionId,
      connId: tab.connId,
      toolNames: pc.tools.map((t) => t.name),
      action,
    });
    setPendingConfirms((prev) => {
      const next = {...prev};
      delete next[tab.terminalId];
      return next;
    });
  };

  const activeTab = tabs.find((t) => t.terminalId === activeTabId) ?? null;
  const activeConfirm = activeTab ? pendingConfirms[activeTab.terminalId] : undefined;
  const activeBusy = activeTab?.busy ?? false;
  return (
    <div className="ops-page">
      <header className="ops-topbar">
        <span className="ops-title">🖥️ 运维终端</span>
        <span className={'ops-status ' + (activeTab ? 'on' : 'off')}>
          {activeTab ? `已连接 ${activeTab.username}@${activeTab.host}` : '未连接'}
        </span>
        <div className="ops-topbar-actions">
          <button disabled={!activeTab} onClick={() => fileRef.current?.click()}>📤 上传文件</button>
          <button disabled={!activeTab} onClick={disconnect}>断开</button>
        </div>
        <input
          ref={fileRef}
          type="file"
          hidden
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) void upload(f);
            e.target.value = '';
          }}
        />
      </header>

      {opsError && <div className="ops-error">{opsError}</div>}

      <div className="ops-main">
        <section className="ops-terminal-col">
          {/* 终端 tab 栏：一条活跃连接一个 tab */}
          {tabs.length > 0 && (
            <div className="ops-tabs">
              {tabs.map((t) => (
                <button
                  key={t.terminalId}
                  className={'ops-tab' + (t.terminalId === activeTabId ? ' active' : '')}
                  onClick={() => setActiveTabId(t.terminalId)}
                  title={`${t.username}@${t.host}`}
                >
                  <span className="ops-tab-dot" />
                  {t.title}
                  <span
                    className={'ops-tab-mode ' + t.mode}
                    onClick={(e) => {
                      // 只切模式不切 tab；stopPropagation 防止触发外层 tab 激活
                      e.stopPropagation();
                      toggleMode(t.terminalId);
                    }}
                    title="幕布输入模式：AI=自然语言给智能体，SH=命令直写远程 shell。点击切换"
                  >
                    {t.mode === 'agent' ? 'AI' : 'SH'}
                  </span>
                </button>
              ))}
            </div>
          )}
          {/* 每个 tab 一个常驻容器：切 tab 只切显隐，滚动回溯与远端输出都不丢 */}
          {tabs.map((t) => (
            <div
              key={t.terminalId}
              ref={(el) => { containersRef.current.set(t.terminalId, el); }}
              className={'ops-terminal' + (t.terminalId === activeTabId ? '' : ' hidden')}
            />
          ))}
          {tabs.length === 0 && (
            <div className="ops-terminal ops-terminal-empty">在右侧选择连接并点击「连接」后开始操作</div>
          )}
          {/* 确认条 fixed 悬浮：不随终端/幕布布局滚动，保证任何状态下都可见可点 */}
          {activeConfirm && (
            <div className="ops-confirm">
              <span>⏸ 待确认：{activeConfirm.tools.map((t) => t.name).join('、')}</span>
              <button className="ops-confirm-allow" onClick={() => sendConfirm('once')}>允许一次</button>
              <button className="ops-confirm-deny" onClick={() => sendConfirm('deny')}>拒绝</button>
            </div>
          )}
          <div className="ops-curtain">
            <div className="ops-curtain-row">
              <input
                value={curtain}
                onChange={(e) => setCurtain(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.nativeEvent.isComposing) submitCurtain();
                }}
                placeholder={activeTab?.mode === 'shell'
                  ? 'SH 模式：命令回车后直接在该 tab 的远程 shell 执行'
                  : 'AI 模式：自然语言下指令；简单命令(ls/cd/clear等)自动直接执行'}
              />
              <button onClick={submitCurtain} disabled={!curtain.trim()}>
                {activeBusy ? '思考中…' : activeTab?.mode === 'shell' ? '执行' : '发送'}
              </button>
            </div>
          </div>
        </section>

        <aside className="ops-side">
          <div className="ops-side-head">
            <span>连接配置</span>
            <button onClick={() => openForm()}>＋ 新建</button>
          </div>
          <ul className="ops-conn-list">
            {conns.map((c) => {
              const tab = tabs.find((t) => t.connId === c.id);
              return (
                <li key={c.id} className={tab && tab.terminalId === activeTabId ? 'active' : ''}>
                  <div className="ops-conn-info">
                    <b>{c.name}</b>
                    <span>{c.username}@{c.host}:{c.port} · {c.authType === 'key' ? '密钥' : '密码'}</span>
                  </div>
                  <div className="ops-conn-actions">
                    {tab ? (
                      <span
                        className="ops-badge"
                        title="点击切换到该终端"
                        onClick={() => setActiveTabId(tab.terminalId)}
                      >
                        使用中
                      </span>
                    ) : (
                      <button onClick={() => connect(c.id)}>连接</button>
                    )}
                    <button title="编辑" onClick={() => openForm(c)}>✎</button>
                    <button title="删除" onClick={() => removeConn(c.id)}>🗑</button>
                  </div>
                </li>
              );
            })}
            {conns.length === 0 && <li className="ops-empty">暂无连接配置，点击「＋ 新建」添加</li>}
          </ul>

          {showForm && (
            <form
              className="ops-conn-form"
              onSubmit={(e) => {
                e.preventDefault();
                void saveConn();
              }}
            >
              <b>{editingId == null ? '新建连接' : '编辑连接'}</b>
              <label>
                名称
                <input value={form.name} onChange={(e) => setForm({...form, name: e.target.value})} />
              </label>
              <label>
                主机
                <input value={form.host} onChange={(e) => setForm({...form, host: e.target.value})} />
              </label>
              <label>
                端口
                <input value={form.port} onChange={(e) => setForm({...form, port: e.target.value})} />
              </label>
              <label>
                用户名
                <input value={form.username} onChange={(e) => setForm({...form, username: e.target.value})} />
              </label>
              <label>
                认证方式
                <select
                  value={form.authType}
                  onChange={(e) => setForm({...form, authType: e.target.value as 'password' | 'key'})}
                >
                  <option value="password">密码</option>
                  <option value="key">私钥</option>
                </select>
              </label>
              {form.authType === 'password' ? (
                <label>
                  密码{editingId != null ? '（留空保持原值）' : ''}
                  <input
                    type="password"
                    value={form.password}
                    onChange={(e) => setForm({...form, password: e.target.value})}
                  />
                </label>
              ) : (
                <>
                  <label>
                    私钥{editingId != null ? '（留空保持原值）' : ''}
                    <textarea
                      rows={4}
                      value={form.privateKey}
                      onChange={(e) => setForm({...form, privateKey: e.target.value})}
                    />
                  </label>
                  <label>
                    私钥口令（可选）
                    <input
                      type="password"
                      value={form.keyPassphrase}
                      onChange={(e) => setForm({...form, keyPassphrase: e.target.value})}
                    />
                  </label>
                </>
              )}
              <div className="ops-form-actions">
                <button type="submit" disabled={savingConn}>{savingConn ? '保存中…' : '保存'}</button>
                <button type="button" onClick={closeForm}>取消</button>
              </div>
            </form>
          )}
        </aside>
      </div>
    </div>
  );
}
