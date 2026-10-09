// 数据库工作台（V30，照 OpsPage 交互模型 1:1 复刻，无终端）：
//   - 两级选择器（连接 → 库）：每个 (serverKey, database) 组合 = 独立物理连接 + 独立智能体会话；
//     未完成两级选择前「连接」按钮禁用；未建立任何连接前底部输入框禁用（先选择才能对话）；
//   - 连接只来自平台（GET /api/settings/cloud-config 的 dbConnections），spoke 不维护本地
//     连接配置与凭证；连接时只传 serverKey + database，密码链路同 ops（hub 下发 → 服务端
//     快照自取；未下发 → 当次手输 RSA-OAEP 加密上送）；
//   - 消息流渲染智能体回合：AI 回复（Markdown 表格 = db_query 结果）、工具调用轨迹、
//     确认弹窗（真实 SQL + 允许一次/拒绝——db 场景白名单机制关闭，回合/永久授权不生效，
//     只渲染「允许一次 / 拒绝」两个按钮，避免「点了没反应」的错觉）；
//   - 顶栏版本徽标：连接建立时后端经 DatabaseMetaData 探测（dbType + version）。
// 授权守卫感知：5s 轮询 /api/db/status，活跃 connKey 消失（管理员撤销授权被强制断开）
// → 移除 tab 并灰字提示。
import {useCallback, useEffect, useRef, useState} from 'react';
import {useParams} from 'react-router-dom';
import {marked} from 'marked';
import DOMPurify from 'dompurify';
import {getJson, postJson} from '../api';
import {createOpsSocket, OpsChatEvent, OpsSocket} from '../opsSocket';
import {CloudDbConnection, useCloudConfig} from '../cloudConfig';
import {encryptPassword, getOpsPublicKey} from '../opsCrypto';
import '../ops.css';
import '../db.css';

/** 一条活跃数据库连接（DbConnectionService.status 元素） */
interface ActiveDbConn {
  connKey: string;
  serverKey: string;
  serverName: string;
  dbType: string;
  host: string;
  port: number;
  database: string;
  username: string;
  readonlyHint: boolean;
  product: string;
  version: string;
  connectedAt: string;
  alive: boolean;
}

interface DbStatus {
  connections: ActiveDbConn[];
}

/** 消息流条目（简化自 ChatPage 段模型：DB 页不需要子 Agent/图片等段型） */
interface DbMsg {
  kind: 'user' | 'text' | 'reasoning' | 'tool' | 'tool_result' | 'error' | 'info';
  content: string;
  /** tool 条目专用：工具名（tool_args/tool_result 归并进同一条 tool 消息） */
  toolName?: string;
  toolArgs?: string;
  toolResult?: string;
  running?: boolean;
}

/** 一个已打开的连接 tab：对应一条活跃 JDBC 连接 + 其专属智能体会话 */
interface DbTab {
  connKey: string;
  serverKey: string;
  serverName: string;
  dbType: string;
  host: string;
  port: number;
  database: string;
  username: string;
  readonlyHint: boolean;
  /** 版本徽标：DatabaseMetaData 探测结果（product 首行 + version 首行） */
  product: string;
  version: string;
  sessionId: string;
  busy: boolean;
  messages: DbMsg[];
}

/** confirm 事件 content：{replyId, tools:[{id,name,input}]}（AgentService.buildConfirmJson） */
interface PendingConfirm {
  replyId: string;
  tools: { id: string; name: string; input: unknown }[];
}

/** 右侧栏「库选择」展开状态：serverKey → 该连接实例的库清单（懒加载） */
interface DbPickerState {
  serverKey: string;
  loading: boolean;
  databases: string[];
  selected: string;
  error: string;
}

const DB_TYPE_ICON: Record<string, string> = {
  mysql: '🐬',
  postgresql: '🐘',
  sqlserver: '🟦',
  oracle: '🅾️',
};

function dbIcon(dbType: string): string {
  return DB_TYPE_ICON[dbType] ?? '🗄️';
}

/** 版本串取首行（Oracle 的 version 可能多行） */
function firstLine(s: string | undefined | null): string {
  if (!s) return '';
  return s.split('\n')[0].trim();
}

const mdCache = new Map<string, string>();
const mdCacheMax = 200;

/** Markdown → 净化 HTML（同 ChatPage.md：marked + DOMPurify，带 LRU 缓存） */
function md(raw: string): string {
  if (!raw) return '';
  const cached = mdCache.get(raw);
  if (cached !== undefined) return cached;
  const html = DOMPurify.sanitize(marked.parse(raw, {breaks: true}) as string);
  if (mdCache.size >= mdCacheMax) {
    const firstKey = mdCache.keys().next().value;
    if (firstKey !== undefined) mdCache.delete(firstKey);
  }
  mdCache.set(raw, html);
  return html;
}

/** 确认弹窗里展示的工具入参（db_query 的 sql 等）：对象 → 紧凑文本 */
function toolInputText(input: unknown): string {
  if (input == null) return '';
  if (typeof input === 'string') return input;
  try {
    return JSON.stringify(input, null, 0);
  } catch {
    return String(input);
  }
}

export default function DbPage() {
  const params = useParams<{ workspaceId?: string }>();
  // DB 工作区是固定内置工作区：路由无参时用默认 id（与 ops 的 default-ops 同模式）
  const workspaceId = params.workspaceId || 'default-db';
  const cloud = useCloudConfig();

  const [tabs, setTabs] = useState<DbTab[]>([]);
  const [activeTabKey, setActiveTabKey] = useState<string | null>(null);
  const [pendingConfirms, setPendingConfirms] = useState<Record<string, PendingConfirm>>({});
  const [picker, setPicker] = useState<DbPickerState | null>(null);
  const [connectingKey, setConnectingKey] = useState<string | null>(null);
  const [pageError, setPageError] = useState('');
  const [curtain, setCurtain] = useState('');

  const sockRef = useRef<OpsSocket | null>(null);
  const sockOpenRef = useRef(false);
  const tabsRef = useRef<DbTab[]>([]);
  tabsRef.current = tabs;
  const activeTabRef = useRef<string | null>(null);
  activeTabRef.current = activeTabKey;
  const workspaceIdRef = useRef(workspaceId);
  workspaceIdRef.current = workspaceId;
  const streamRef = useRef<Record<string, string>>({}); // sessionId → 进行中 text 段索引 key

  const activeTab = tabs.find((t) => t.connKey === activeTabKey) ?? null;
  const activeConfirm = activeTab ? pendingConfirms[activeTab.connKey] : undefined;

  // ==================== 消息流操作 ====================

  const appendMsg = useCallback((connKey: string, msg: DbMsg) => {
    setTabs((prev) => prev.map((t) => (
      t.connKey === connKey ? {...t, messages: [...t.messages, msg]} : t
    )));
  }, []);

  /** 归并式追加：text/reasoning 增量并入最后一条同 kind 消息（避免每个 delta 一条） */
  const appendDelta = useCallback((connKey: string, kind: 'text' | 'reasoning', delta: string) => {
    setTabs((prev) => prev.map((t) => {
      if (t.connKey !== connKey) return t;
      const msgs = [...t.messages];
      const last = msgs[msgs.length - 1];
      if (last && last.kind === kind && !last.running) {
        msgs[msgs.length - 1] = {...last, content: last.content + delta};
      } else {
        msgs.push({kind, content: delta});
      }
      return {...t, messages: msgs};
    }));
  }, []);

  /** 工具轨迹归并：tool 开卡 → tool_args 并入 → tool_result 收口 */
  const mergeTool = useCallback((connKey: string, ev: OpsChatEvent) => {
    setTabs((prev) => prev.map((t) => {
      if (t.connKey !== connKey) return t;
      const msgs = [...t.messages];
      if (ev.type === 'tool') {
        msgs.push({kind: 'tool', content: ev.content ?? '', toolName: ev.content ?? '', running: true});
      } else {
        // tool_args / tool_result：并入最后一条 tool 消息
        for (let i = msgs.length - 1; i >= 0; i--) {
          if (msgs[i].kind === 'tool') {
            if (ev.type === 'tool_args') {
              msgs[i] = {...msgs[i], toolArgs: ev.content ?? ''};
            } else {
              msgs[i] = {...msgs[i], toolResult: ev.content ?? '', running: false};
            }
            break;
          }
        }
      }
      return {...t, messages: msgs};
    }));
  }, []);

  const setBusy = useCallback((connKey: string, busy: boolean) => {
    setTabs((prev) => prev.map((t) => (t.connKey === connKey ? {...t, busy} : t)));
  }, []);

  // ==================== WS 对话事件路由 ====================

  const renderChatEvent = useCallback((sid: string, ev: OpsChatEvent) => {
    const tab = tabsRef.current.find((t) => t.sessionId === sid);
    if (!tab) return; // 事件路由不到 tab（tab 已移除）直接丢弃
    const key = tab.connKey;
    switch (ev.type) {
      case 'text':
        appendDelta(key, 'text', ev.content ?? '');
        break;
      case 'reasoning':
        appendDelta(key, 'reasoning', ev.content ?? '');
        break;
      case 'tool':
      case 'tool_args':
      case 'tool_result':
        mergeTool(key, ev);
        break;
      case 'confirm': {
        try {
          const parsed = JSON.parse(ev.content ?? '') as PendingConfirm;
          setPendingConfirms((prev) => ({...prev, [key]: parsed}));
        } catch { /* 坏包忽略 */ }
        break;
      }
      case 'error':
        appendMsg(key, {kind: 'error', content: ev.content ?? '执行出错'});
        setBusy(key, false);
        // 终态清挂起确认：确认超时清扫走 error+end（「工具确认超时，已自动取消」），
        // 确认条残留会「点了没反应」
        setPendingConfirms((prev) => {
          if (!(key in prev)) return prev;
          const next = {...prev};
          delete next[key];
          return next;
        });
        break;
      case 'end':
      case 'stopped':
        // 回合结束/被终止：复位 busy；挂起的确认已无意义（残留会「点了没反应」）
        setBusy(key, false);
        setPendingConfirms((prev) => {
          if (!(key in prev)) return prev;
          const next = {...prev};
          delete next[key];
          return next;
        });
        break;
      default:
        // tool_end / context / file_changed / status 等本页不渲染
        break;
    }
  }, [appendDelta, appendMsg, mergeTool, setBusy]);

  /** WS 建立后注册全部 tab 的会话（重连后需重注册；connKey 随消息上行恢复会话→连接绑定） */
  const registerAllSessions = useCallback(() => {
    const sock = sockRef.current;
    const wsId = workspaceIdRef.current;
    if (!sock || !wsId) return;
    for (const tab of tabsRef.current) {
      if (tab.sessionId) {
        sock.send({type: 'register', workspaceId: wsId, sessionId: tab.sessionId, connKey: tab.connKey});
      }
    }
  }, []);

  useEffect(() => {
    const sock = createOpsSocket({
      onOpen: () => {
        sockOpenRef.current = true;
        registerAllSessions();
      },
      onData: () => { /* DB 页无终端，term_* 消息忽略 */ },
      onError: () => { /* 同上 */ },
      onChatEvent: renderChatEvent,
      onClose: () => {
        sockOpenRef.current = false;
      },
    });
    sockRef.current = sock;
    return () => {
      sockOpenRef.current = false;
      sock.close();
      sockRef.current = null;
    };
  }, [registerAllSessions, renderChatEvent]);

  // ==================== 连接管理 ====================

  /** 活跃连接快照（页面刷新恢复 + 授权守卫感知共用） */
  const fetchStatus = useCallback(async (): Promise<ActiveDbConn[]> => {
    const wsId = workspaceIdRef.current;
    if (!wsId) return [];
    try {
      const st = await getJson<DbStatus>(`/api/db/status?workspaceId=${encodeURIComponent(wsId)}`);
      return st.connections ?? [];
    } catch {
      return [];
    }
  }, []);

  const createSessionFor = useCallback(async (title: string): Promise<string> => {
    const wsId = workspaceIdRef.current;
    const s = await postJson<{ id: string }>(
      `/api/workspaces/${wsId}/sessions`, {title});
    return s.id;
  }, []);

  /** 页面刷新恢复：服务端仍活跃的连接补建 tab（按 connKey 去重，不抢激活） */
  useEffect(() => {
    void (async () => {
      const conns = await fetchStatus();
      for (const c of conns) {
        if (tabsRef.current.some((t) => t.connKey === c.connKey)) continue;
        let sid = '';
        try {
          sid = await createSessionFor(`数据库 · ${c.serverName}/${c.database}`);
        } catch {
          setPageError(`「${c.serverName}/${c.database}」的智能体会话创建失败 —— 重连可重试`);
        }
        setTabs((prev) => prev.some((t) => t.connKey === c.connKey) ? prev : [...prev, {
          connKey: c.connKey,
          serverKey: c.serverKey,
          serverName: c.serverName,
          dbType: c.dbType,
          host: c.host,
          port: c.port,
          database: c.database,
          username: c.username,
          readonlyHint: c.readonlyHint,
          product: c.product,
          version: firstLine(c.version),
          sessionId: sid,
          busy: false,
          messages: [],
        }]);
      }
    })();
    // 仅挂载时恢复一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 授权守卫感知轮询：spoke 侧 DbConnectionGuard 撤销授权后强制断开连接，
   * status 快照里 connKey 消失 → 移除 tab + 灰字提示 */
  useEffect(() => {
    const timer = setInterval(async () => {
      const conns = await fetchStatus();
      const alive = new Set(conns.map((c) => c.connKey));
      for (const t of tabsRef.current) {
        if (!alive.has(t.connKey)) {
          setTabs((prev) => prev.filter((x) => x.connKey !== t.connKey));
          setPendingConfirms((prev) => {
            if (!(t.connKey in prev)) return prev;
            const next = {...prev};
            delete next[t.connKey];
            return next;
          });
          if (activeTabRef.current === t.connKey) {
            setActiveTabKey(null);
            setPageError(`「${t.serverName}/${t.database}」连接已断开（平台授权可能已被管理员撤销）`);
          }
        }
      }
    }, 5000);
    return () => clearInterval(timer);
  }, [fetchStatus]);

  /** 展开右侧栏「库选择」：拉取该连接实例的库清单（懒加载，每次点开都刷新） */
  const openPicker = async (c: CloudDbConnection) => {
    setPageError('');
    setPicker({serverKey: c.serverKey, loading: true, databases: [], selected: c.databaseName, error: ''});
    try {
      let encryptedPassword: string | undefined;
      if (!c.hasPassword) {
        const input = window.prompt(
          `「${c.name}」未随目录下发密码，请输入 ${c.username}@${c.host} 的密码（仅本次使用，不保存）`);
        if (input === null) {
          setPicker(null);
          return;
        }
        if (!input) {
          setPicker(null);
          setPageError('密码不能为空');
          return;
        }
        const publicKey = await getOpsPublicKey();
        encryptedPassword = await encryptPassword(publicKey, input);
      }
      // 密文走 POST body：查询串会进访问日志/反向代理留存，URL 不携带密码（含密文）
      const r = await postJson<{ databases: string[]; defaultDatabase: string }>(
        '/api/db/databases',
        {serverKey: c.serverKey, encryptedPassword: encryptedPassword || null});
      setPicker({
        serverKey: c.serverKey,
        loading: false,
        databases: r.databases ?? [],
        selected: r.defaultDatabase || (r.databases ?? [])[0] || '',
        error: '',
      });
    } catch (e) {
      setPicker({serverKey: c.serverKey, loading: false, databases: [], selected: '', error: String(e)});
    }
  };

  /** 建立连接：POST /api/db/connect（只传 serverKey + database；密码链路同 ops）。
   * 已有同 connKey 的活跃 tab：直接切换过去，不重连 */
  const connectDb = async (c: CloudDbConnection, database: string) => {
    if (!workspaceId || connectingKey) return;
    const connKey = `${c.serverKey}/${database}`;
    const existing = tabsRef.current.find((t) => t.connKey === connKey);
    if (existing) {
      setActiveTabKey(connKey);
      setPicker(null);
      return;
    }
    setConnectingKey(c.serverKey);
    setPageError('');
    try {
      let encryptedPassword: string | undefined;
      if (!c.hasPassword) {
        const input = window.prompt(
          `「${c.name}」未随目录下发密码，请输入 ${c.username}@${c.host} 的密码（仅本次连接使用，不保存）`);
        if (input === null) return;
        if (!input) {
          setPageError('密码不能为空');
          return;
        }
        const publicKey = await getOpsPublicKey();
        encryptedPassword = await encryptPassword(publicKey, input);
      }
      const st = await postJson<{ connKey: string; connections: ActiveDbConn[] }>('/api/db/connect', {
        workspaceId,
        serverKey: c.serverKey,
        database,
        ...(encryptedPassword ? {encryptedPassword} : {}),
      });
      const conn = (st.connections ?? []).find((x) => x.connKey === st.connKey);
      if (!conn) {
        setPageError('连接未建立（服务端无该活跃连接）');
        return;
      }
      let sid = '';
      try {
        sid = await createSessionFor(`数据库 · ${conn.serverName}/${conn.database}`);
      } catch {
        setPageError('智能体会话创建失败 —— 连接可用，对话不可用（重连可重试）');
      }
      setTabs((prev) => [...prev, {
        connKey: conn.connKey,
        serverKey: conn.serverKey,
        serverName: conn.serverName,
        dbType: conn.dbType,
        host: conn.host,
        port: conn.port,
        database: conn.database,
        username: conn.username,
        readonlyHint: conn.readonlyHint,
        product: conn.product,
        version: firstLine(conn.version),
        sessionId: sid,
        busy: false,
        messages: [{
          kind: 'info',
          content: `已连接 ${conn.serverName} / ${conn.database}（${conn.product} ${firstLine(conn.version)}）`
            + (conn.readonlyHint ? ' · 只读' : ''),
        }],
      }]);
      setActiveTabKey(conn.connKey);
      setPicker(null);
      // 立即注册会话（不等 WS onOpen——连接已建立，事件需定向投递）
      if (sockOpenRef.current && sid) {
        sockRef.current?.send({type: 'register', workspaceId, sessionId: sid, connKey: conn.connKey});
      }
    } catch (e) {
      setPageError(String(e));
    } finally {
      setConnectingKey(null);
    }
  };

  const disconnectTab = async (tab: DbTab) => {
    if (!workspaceId) return;
    try {
      await postJson(
        `/api/db/disconnect?workspaceId=${encodeURIComponent(workspaceId)}&connKey=${encodeURIComponent(tab.connKey)}`,
        {});
      setTabs((prev) => prev.filter((x) => x.connKey !== tab.connKey));
      setPendingConfirms((prev) => {
        const next = {...prev};
        delete next[tab.connKey];
        return next;
      });
      if (activeTabRef.current === tab.connKey) setActiveTabKey(null);
    } catch (e) {
      setPageError(String(e));
    }
  };

  // ==================== 对话 ====================

  const sendChat = () => {
    const text = curtain.trim();
    const tab = tabsRef.current.find((t) => t.connKey === activeTabRef.current);
    if (!text || !workspaceId) return;
    if (!tab) {
      setPageError('未连接数据库 —— 先在右侧完成「连接 → 库」两级选择并连接');
      return;
    }
    if (!tab.sessionId) {
      setPageError('该连接的智能体会话未就绪 —— 断开后重新连接可重试');
      return;
    }
    if (tab.busy) {
      setPageError('智能体正在执行中，请等当前回合结束');
      return;
    }
    setPageError('');
    appendMsg(tab.connKey, {kind: 'user', content: text});
    setTabs((prev) => prev.map((t) => (t.connKey === tab.connKey ? {...t, busy: true} : t)));
    // connKey 随消息上行：后端把该会话绑定到这条连接，db_query 定向执行（一个连接一个会话）
    sockRef.current?.send({type: 'chat', workspaceId, sessionId: tab.sessionId, connKey: tab.connKey, message: text});
    setCurtain('');
  };

  const sendConfirm = (action: 'once' | 'deny') => {
    const tab = tabsRef.current.find((t) => t.connKey === activeTabRef.current);
    const pc = tab ? pendingConfirms[tab.connKey] : undefined;
    if (!tab || !pc) return;
    sockRef.current?.send({
      type: 'confirm',
      workspaceId,
      sessionId: tab.sessionId,
      connKey: tab.connKey,
      toolNames: pc.tools.map((t) => t.name),
      action,
    });
    setPendingConfirms((prev) => {
      const next = {...prev};
      delete next[tab.connKey];
      return next;
    });
  };

  // ==================== 渲染 ====================

  const dbConns = cloud.dbConnections ?? [];
  return (
    <div className="ops-page db-page">
      <header className="ops-topbar">
        <span className="ops-title">🗄️ 数据库工作台</span>
        <span className={'ops-status ' + (activeTab ? 'on' : 'off')}>
          {activeTab
            ? `已连接 ${activeTab.serverName}/${activeTab.database} @ ${activeTab.host}`
            : '未连接 —— 先在右侧完成「连接 → 库」两级选择'}
        </span>
        {activeTab && (
          <span className="db-version-badge" title={activeTab.version}>
            {dbIcon(activeTab.dbType)} {activeTab.dbType} {firstLine(activeTab.version) || activeTab.product}
            {activeTab.readonlyHint ? ' · 只读' : ''}
          </span>
        )}
        <div className="ops-topbar-actions">
          {activeTab && (
            <button onClick={() => void disconnectTab(activeTab)}>⛔ 断开当前连接</button>
          )}
        </div>
      </header>

      <div className="ops-main">
        <div className="db-main-col">
          {/* 连接 tab 条：每个 (serverKey, database) 一个 tab */}
          <div className="ops-tabs">
            {tabs.map((t) => (
              <div
                key={t.connKey}
                className={'ops-tab' + (t.connKey === activeTabKey ? ' active' : '')}
                onClick={() => setActiveTabKey(t.connKey)}
                title={`${t.serverName}/${t.database} @ ${t.host}:${t.port}`}
              >
                <span className="ops-tab-dot" style={{background: t.busy ? '#f59e0b' : '#22c55e'}}/>
                <span>{dbIcon(t.dbType)} {t.serverName}/{t.database}</span>
                {t.busy && <span className="db-tab-busy">运行中</span>}
              </div>
            ))}
            {tabs.length === 0 && <span className="ops-empty">尚无连接 —— 在右侧选择连接与库</span>}
          </div>

          {/* 消息流：当前 tab 的对话（AI 回复 Markdown 渲染，db_query 结果即其中的表格） */}
          <div className="db-stream">
            {!activeTab && (
              <div className="ops-terminal-empty">
                <p>先选择才能对话：</p>
                <p>1️⃣ 右侧点选一个数据库连接 → 2️⃣ 选择要操作的库 → 3️⃣ 点击「连接」</p>
                <p>连接建立后在这里用自然语言查询，AI 生成的每条 SQL 都会先请你确认。</p>
              </div>
            )}
            {activeTab?.messages.map((m, i) => {
              if (m.kind === 'user') {
                return <div key={i} className="db-msg db-msg-user">🧑 {m.content}</div>;
              }
              if (m.kind === 'info') {
                return <div key={i} className="db-msg db-msg-info">{m.content}</div>;
              }
              if (m.kind === 'error') {
                return <div key={i} className="db-msg db-msg-error">✗ {m.content}</div>;
              }
              if (m.kind === 'reasoning') {
                return <div key={i} className="db-msg db-msg-reasoning">{m.content}</div>;
              }
              if (m.kind === 'tool') {
                return (
                  <div key={i} className={'db-msg db-msg-tool' + (m.running ? ' running' : '')}>
                    <div className="db-tool-head">
                      {m.running ? '⚙' : '✓'} {m.toolName}
                      {m.toolArgs && <code className="db-tool-args">{m.toolArgs}</code>}
                    </div>
                    {m.toolResult && <pre className="db-tool-result">{m.toolResult}</pre>}
                  </div>
                );
              }
              // text：AI 回复（Markdown；db_query 结果以表格呈现在回复里）
              return <div key={i} className="db-msg db-msg-text" dangerouslySetInnerHTML={{__html: md(m.content)}}/>;
            })}
          </div>

          {/* 确认条：真实 SQL + 允许一次/拒绝（db 场景无白名单机制，不渲染永久授权按钮） */}
          {activeConfirm && activeTab && (
            <div className="ops-confirm db-confirm">
              <div className="db-confirm-body">
                <strong>⏸ 待确认（{activeConfirm.tools.map((t) => t.name).join('、')}）</strong>
                {activeConfirm.tools.map((t) => (
                  <pre key={t.id} className="db-confirm-sql">{toolInputText(t.input)}</pre>
                ))}
              </div>
              <button className="ops-confirm-allow" onClick={() => sendConfirm('once')}>允许一次</button>
              <button className="ops-confirm-deny" onClick={() => sendConfirm('deny')}>拒绝</button>
            </div>
          )}

          {/* 底部输入框：未连接禁用（先选择才能对话） */}
          <div className="ops-curtain">
            {pageError && <div className="ops-error">{pageError}</div>}
            <div className="ops-curtain-row">
              <input
                value={curtain}
                onChange={(e) => setCurtain(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.nativeEvent.isComposing) sendChat();
                }}
                disabled={!activeTab || (activeTab?.busy ?? false)}
                placeholder={activeTab
                  ? (activeTab.busy ? '智能体执行中…' : `向智能体提问（作用于 ${activeTab.database}）…`)
                  : '未连接数据库 —— 先在右侧完成两级选择并连接'}
              />
              <button onClick={sendChat} disabled={!activeTab || (activeTab?.busy ?? false) || !curtain.trim()}>
                发送
              </button>
            </div>
          </div>
        </div>

        {/* 右侧栏：平台下发的数据库连接清单 + 两级选择器 */}
        <aside className="ops-side">
          <div className="ops-side-head">平台数据库连接（{dbConns.length}）</div>
          {dbConns.length === 0 && (
            <div className="ops-empty">
              {cloud.available
                ? '平台未下发数据库连接 —— 请联系管理员在 hub 配置并授权'
                : '本地模式 —— 数据库连接由平台（hub）统一下发，本地模式不可用'}
            </div>
          )}
          <ul className="ops-conn-list">
            {dbConns.map((c) => {
              const inUse = tabs.some((t) => t.serverKey === c.serverKey);
              const expanded = picker?.serverKey === c.serverKey;
              return (
                <li key={c.serverKey} className={expanded ? 'active' : ''}>
                  <div className="db-conn-row" onClick={() => void openPicker(c)}>
                    <div className="ops-conn-info">
                      <b>{dbIcon(c.dbType)} {c.name}{inUse ? ' · 使用中' : ''}</b>
                      <span>
                        {c.dbType} · {c.host}:{c.port} · {c.username}
                        {c.readonlyHint ? ' · 只读' : ''}
                        {c.description ? ` · ${c.description}` : ''}
                      </span>
                    </div>
                    <span className="db-picker-arrow">{expanded ? '▾' : '▸'}</span>
                  </div>
                  {expanded && picker && (
                    <div className="db-picker">
                      {picker.loading && <span className="ops-empty">正在读取库清单…</span>}
                      {picker.error && <span className="ops-error">{picker.error}</span>}
                      {!picker.loading && !picker.error && (
                        <>
                          <label className="db-picker-label">选择库（每个库一条独立连接）</label>
                          <select
                            className="db-picker-select"
                            value={picker.selected}
                            onChange={(e) => setPicker({...picker, selected: e.target.value})}
                          >
                            {picker.databases.length === 0 && <option value="">（无可见库）</option>}
                            {picker.databases.map((d) => (
                              <option key={d} value={d}>{d}</option>
                            ))}
                          </select>
                          <button
                            className="db-picker-connect"
                            disabled={!picker.selected || connectingKey === c.serverKey}
                            onClick={() => void connectDb(c, picker.selected)}
                          >
                            {connectingKey === c.serverKey ? '连接中…' : `连接 ${picker.selected}`}
                          </button>
                        </>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        </aside>
      </div>
    </div>
  );
}


