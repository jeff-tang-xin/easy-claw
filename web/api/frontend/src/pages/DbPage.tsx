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

/** 报表列表元数据（GET /api/db/reports 元素，不含 HTML 大字段） */
interface ReportMeta {
  id: number;
  kind: string | null;
  title: string;
  serverName: string | null;
  dbType: string | null;
  databaseName: string | null;
  createdAt: string;
  sizeBytes: number;
}

/** 历史消息（GET /api/chat/history 元素；结构对齐 ChatPage.BoxMessage） */
interface BoxMessage {
  id?: string; type: string; content: string; toolName?: string;
  toolArgs?: string; toolResult?: string; seq: number;
}

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
  /** 表清单（schema.table 复合名；连接后自动加载，undefined = 未加载） */
  tables?: string[];
  tablesLoading?: boolean;
  /** 展开的表结构（点击表名；再点收起） */
  tableDetail?: { name: string; text: string } | null;
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
  /** 表清单过滤词（前端过滤，切换连接不清空——用户自行清除） */
  const [tableFilter, setTableFilter] = useState('');

  const sockRef = useRef<OpsSocket | null>(null);
  const sockOpenRef = useRef(false);
  const tabsRef = useRef<DbTab[]>([]);
  tabsRef.current = tabs;
  const activeTabRef = useRef<string | null>(null);
  activeTabRef.current = activeTabKey;
  const workspaceIdRef = useRef(workspaceId);
  workspaceIdRef.current = workspaceId;
  const streamRef = useRef<Record<string, string>>({}); // sessionId → 进行中 text 段索引 key
  /** 消息流滚动容器：新消息/流式增量时自动跟随到底部（用户上翻看历史时不打扰） */
  const scrollRef = useRef<HTMLDivElement | null>(null);

  const activeTab = tabs.find((t) => t.connKey === activeTabKey) ?? null;
  const activeConfirm = activeTab ? pendingConfirms[activeTab.connKey] : undefined;
  /** 表清单过滤结果（前端过滤；大小写不敏感） */
  const visibleTables = activeTab
    ? (activeTab.tables ?? []).filter((t) => t.toLowerCase().includes(tableFilter.trim().toLowerCase()))
    : [];

  // ==================== 报表中心（V32） ====================
  const [reportsOpen, setReportsOpen] = useState(false);
  const [reports, setReports] = useState<ReportMeta[]>([]);
  const [reportsLoading, setReportsLoading] = useState(false);
  const [reportDetail, setReportDetail] = useState<{ id: number; kind: string | null; title: string; html: string } | null>(null);

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

  const createSessionFor = useCallback(async (title: string, boundKey?: string): Promise<string> => {
    const wsId = workspaceIdRef.current;
    const s = await postJson<{ id: string }>(
      `/api/workspaces/${wsId}/sessions`, {title, boundKey});
    return s.id;
  }, []);

  /** 解析连接的会话：先按 boundKey（结构化外键 = connKey，hub 下发连接唯一键）反查复用
   * 旧会话并回放历史——重连/刷新恢复不丢对话；无旧会话则新建（标题纯人读，
   * 归属由 boundKey 承担，同名连接不会串会话）。创建失败返回空 sid，由调用方提示。 */
  const resolveSessionFor = async (conn: ActiveDbConn): Promise<{ sid: string; replayed: DbMsg[] | null }> => {
    const wsId = workspaceIdRef.current;
    if (!wsId) return {sid: '', replayed: null};
    try {
      const sessions = await getJson<{ id: string; title: string; boundKey: string | null }[]>(
        `/api/workspaces/${wsId}/sessions`);
      const old = (sessions ?? []).find((s) => s.boundKey === conn.connKey);
      if (old) {
        return {sid: old.id, replayed: await replayHistory(wsId, old.id)};
      }
    } catch { /* 反查/回放失败降级为新建 */ }
    try {
      return {sid: await createSessionFor(`数据库 · ${conn.serverName}/${conn.database}`, conn.connKey), replayed: null};
    } catch {
      return {sid: '', replayed: null};
    }
  };

  /** 页面刷新恢复：服务端仍活跃的连接补建 tab（按 connKey 去重，不抢激活） */
  useEffect(() => {
    void (async () => {
      const conns = await fetchStatus();
      for (const c of conns) {
        if (tabsRef.current.some((t) => t.connKey === c.connKey)) continue;
        const {sid, replayed} = await resolveSessionFor(c);
        if (!sid) {
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
          messages: replayed ?? [],
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

  /** 历史回放：BoxMessage[] → DbMsg[]（映射规则对齐 ChatPage.loadHistory；
   * BLACKBOARD/SUBAGENT 为团队场景段型，DB 页不产生，跳过） */
  const replayHistory = async (wid: string, sid: string): Promise<DbMsg[]> => {
    const box = await getJson<BoxMessage[]>(`/api/chat/history?workspaceId=${wid}&sessionId=${sid}`);
    const msgs: DbMsg[] = [];
    for (const b of box) {
      if (b.type === 'USER') {
        msgs.push({kind: 'user', content: b.content});
      } else if (b.type === 'AI_TEXT') {
        msgs.push({kind: 'text', content: b.content});
      } else if (b.type === 'THINKING') {
        msgs.push({kind: 'reasoning', content: b.content});
      } else if (b.type === 'TOOL_CALL') {
        msgs.push({kind: 'tool', content: '', toolName: b.toolName, toolArgs: b.toolArgs || '', running: false});
      } else if (b.type === 'TOOL_RESULT') {
        // 配对到最近的 tool 条目（历史里调用与结果分两条）
        const lastTool = msgs[msgs.length - 1];
        if (lastTool && lastTool.kind === 'tool') {
          lastTool.toolResult = b.toolResult || '';
        } else {
          msgs.push({kind: 'tool', content: '', toolName: b.toolName, toolResult: b.toolResult || '', running: false});
        }
      } else if (b.type === 'SYSTEM') {
        msgs.push({kind: 'info', content: b.content});
      }
    }
    return msgs;
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
      // 会话解析：按 boundKey（= connKey 结构化外键）反查复用旧会话并回放历史；
      // 无旧会话则新建（归属由 boundKey 承担，标题纯人读）
      const {sid, replayed} = await resolveSessionFor(conn);
      if (!sid) {
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
        messages: [
          {
            kind: 'info',
            content: `已连接 ${conn.serverName} / ${conn.database}（${conn.product} ${firstLine(conn.version)}）`
              + (conn.readonlyHint ? ' · 只读' : ''),
          },
          ...(replayed ?? []),
        ],
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

  // ==================== 报表中心（右侧伪 tab：列表 + 预览 + 下载） ====================

  const loadReports = async () => {
    if (!workspaceIdRef.current) return;
    setReportsLoading(true);
    try {
      const r = await getJson<{ reports: ReportMeta[] }>(
        `/api/db/reports?workspaceId=${encodeURIComponent(workspaceIdRef.current)}`);
      setReports(r.reports ?? []);
    } catch (e) {
      setPageError(String(e));
    } finally {
      setReportsLoading(false);
    }
  };

  const openReport = async (id: number) => {
    if (!workspaceIdRef.current) return;
    setReportDetail({id, kind: null, title: '加载中…', html: ''});
    try {
      const r = await getJson<{ report: { id: number; kind: string | null; title: string; htmlContent: string } }>(
        `/api/db/reports/${id}?workspaceId=${encodeURIComponent(workspaceIdRef.current)}`);
      if (r.report.kind === 'dashboard') {
        await refreshDashboard(id, r.report.title);
        return;
      }
      setReportDetail({id: r.report.id, kind: r.report.kind, title: r.report.title, html: r.report.htmlContent});
    } catch (e) {
      setReportDetail(null);
      setPageError(String(e));
    }
  };

  /** 看板刷新：实时执行全部区块 SQL 并渲染（「每次打开就查询一次」的落点） */
  const refreshDashboard = async (id: number, title: string) => {
    if (!workspaceIdRef.current) return;
    try {
      const r = await postJson<{ html: string }>(
        `/api/db/reports/${id}/refresh?workspaceId=${encodeURIComponent(workspaceIdRef.current)}`, {});
      setReportDetail({id, kind: 'dashboard', title, html: r.html});
    } catch (e) {
      setReportDetail(null);
      setPageError(String(e));
    }
  };

  const deleteReport = async (id: number) => {
    if (!workspaceIdRef.current) return;
    try {
      await postJson(`/api/db/reports/${id}/delete?workspaceId=${encodeURIComponent(workspaceIdRef.current)}`, {});
      setReports((prev) => prev.filter((r) => r.id !== id));
      if (reportDetail?.id === id) setReportDetail(null);
    } catch (e) {
      setPageError(String(e));
    }
  };

  /** 下载：a[download] 触发浏览器保存（Content-Disposition attachment 由后端设置） */
  const downloadReport = (r: ReportMeta) => {
    if (!workspaceIdRef.current) return;
    const a = document.createElement('a');
    a.href = `/api/db/reports/${r.id}/download?workspaceId=${encodeURIComponent(workspaceIdRef.current)}`;
    a.download = `${r.title}.html`;
    document.body.appendChild(a);
    a.click();
    a.remove();
  };

  // ==================== 表清单（右侧面板：连接后直接展示，无需再操作） ====================

  const loadTables = async (connKey: string) => {
    if (!workspaceIdRef.current) return;
    setTabs((prev) => prev.map((t) => (t.connKey === connKey ? {...t, tablesLoading: true} : t)));
    try {
      const r = await postJson<{ tables: string[] }>(
        `/api/db/tables?workspaceId=${encodeURIComponent(workspaceIdRef.current)}&connKey=${encodeURIComponent(connKey)}`,
        {});
      setTabs((prev) => prev.map((t) => (t.connKey === connKey ? {...t, tables: r.tables ?? [], tablesLoading: false} : t)));
    } catch {
      // 表清单失败不阻断对话：置空清单，错误只在展开详情时可见
      setTabs((prev) => prev.map((t) => (t.connKey === connKey ? {...t, tables: [], tablesLoading: false} : t)));
    }
  };

  const toggleTable = async (connKey: string, table: string) => {
    const cur = tabsRef.current.find((t) => t.connKey === connKey);
    if (cur?.tableDetail?.name === table) {
      setTabs((prev) => prev.map((t) => (t.connKey === connKey ? {...t, tableDetail: null} : t)));
      return;
    }
    setTabs((prev) => prev.map((t) => (t.connKey === connKey ? {...t, tableDetail: {name: table, text: '加载中…'}} : t)));
    try {
      const r = await postJson<{ schema: string }>(
        `/api/db/tables?workspaceId=${encodeURIComponent(workspaceIdRef.current)}`
        + `&connKey=${encodeURIComponent(connKey)}&table=${encodeURIComponent(table)}`,
        {});
      setTabs((prev) => prev.map((t) => (t.connKey === connKey ? {...t, tableDetail: {name: table, text: r.schema ?? ''}} : t)));
    } catch (e) {
      setTabs((prev) => prev.map((t) => (t.connKey === connKey ? {...t, tableDetail: {name: table, text: String(e)}} : t)));
    }
  };

  // 活跃 tab 首次展示时拉表清单（连接成功 setActiveTabKey 后自动触发）
  useEffect(() => {
    if (!activeTabKey) return;
    const tab = tabsRef.current.find((t) => t.connKey === activeTabKey);
    if (tab && tab.tables === undefined && !tab.tablesLoading) {
      void loadTables(tab.connKey);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTabKey]);

  // ==================== 消息流自动滚动 ====================

  // tab 切换：无条件滚到最新（用户切 tab 就是想看最新对话）
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [activeTabKey]);

  // 消息更新：仅当用户接近底部时跟随（上翻看历史时不强行拉底）。
  // 信号取最后一条消息各文本字段长度——流式增量不改变 messages.length，
  // 只依赖条数会漏掉「同一条消息内容持续增长」的滚动时机
  const msgs = activeTab?.messages;
  const lastMsgSig = msgs && msgs.length > 0
    ? `${msgs.length}:${msgs[msgs.length - 1]?.content.length ?? 0}`
      + `:${msgs[msgs.length - 1]?.toolResult?.length ?? 0}`
      + `:${msgs[msgs.length - 1]?.toolArgs?.length ?? 0}`
    : '0';
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 120) {
      el.scrollTop = el.scrollHeight;
    }
  }, [lastMsgSig]);

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
                className={'ops-tab' + (!reportsOpen && t.connKey === activeTabKey ? ' active' : '')}
                onClick={() => { setActiveTabKey(t.connKey); setReportsOpen(false); }}
                title={`${t.serverName}/${t.database} @ ${t.host}:${t.port}`}
              >
                <span className="ops-tab-dot" style={{background: t.busy ? '#faad14' : '#52c41a'}}/>
                <span>{dbIcon(t.dbType)} {t.serverName}/{t.database}</span>
                {t.busy && <span className="db-tab-busy">运行中</span>}
              </div>
            ))}
            {tabs.length === 0 && <span className="ops-empty">尚无连接 —— 在右侧选择连接与库</span>}
            {/* 报表中心伪 tab：固定靠右，与连接 tab 互斥高亮 */}
            <div
              className={'ops-tab db-reports-tab' + (reportsOpen ? ' active' : '')}
              onClick={() => {
                const next = !reportsOpen;
                setReportsOpen(next);
                if (next) void loadReports();
              }}
              title="AI 生成的分析报表（展示与下载）"
            >
              <span>📊 报表{reports.length > 0 ? ` (${reports.length})` : ''}</span>
            </div>
          </div>

          {/* 报表中心面板：伪 tab 选中时替换消息流 */}
          {reportsOpen && (
            <div className="db-reports-panel">
              <div className="db-reports-list">
                {reportsLoading && <div className="ops-empty">加载中…</div>}
                {!reportsLoading && reports.length === 0 && (
                  <div className="ops-empty">暂无报表 —— 让 AI 分析数据，完成后它会自动保存到这里</div>
                )}
                {reports.map((r) => (
                  <div key={r.id} className={'db-report-item' + (reportDetail?.id === r.id ? ' active' : '')} onClick={() => void openReport(r.id)}>
                    <div className="db-report-title">{r.kind === 'dashboard' ? '📊' : '📄'} {r.title}</div>
                    <div className="db-report-meta">
                      {[r.serverName, r.databaseName].filter(Boolean).join('/') || '未知来源'}
                      {r.dbType ? ` · ${r.dbType}` : ''}
                      {` · ${new Date(r.createdAt).toLocaleString()}`}
                      {` · ${r.sizeBytes > 1024 ? `${(r.sizeBytes / 1024).toFixed(1)} KB` : `${r.sizeBytes} B`}`}
                    </div>
                    <div className="db-report-actions">
                      {r.kind === 'dashboard' && (
                        <button onClick={(e) => { e.stopPropagation(); void refreshDashboard(r.id, r.title); }}>刷新</button>
                      )}
                      <button onClick={(e) => { e.stopPropagation(); downloadReport(r); }}>下载</button>
                      <button className="danger" onClick={(e) => { e.stopPropagation(); void deleteReport(r.id); }}>删除</button>
                    </div>
                  </div>
                ))}
              </div>
              {reportDetail && (
                <div className="db-report-preview-wrap">
                  {reportDetail.kind === 'dashboard' && (
                    <div className="db-report-preview-bar">
                      <span>实时看板 · 打开/刷新即查询最新数据</span>
                      <button onClick={() => void refreshDashboard(reportDetail.id, reportDetail.title)}>🔄 刷新</button>
                    </div>
                  )}
                  <iframe className="db-report-preview" sandbox="allow-scripts" srcDoc={reportDetail.html} title={reportDetail.title}/>
                </div>
              )}
            </div>
          )}

          {/* 消息流：当前 tab 的对话（AI 回复 Markdown 渲染，db_query 结果即其中的表格） */}
          {!reportsOpen && (
          <div className="db-stream" ref={scrollRef}>
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
                    {/* 运行中强制展开看进度；完成后非受控（用户自由切换，默认折叠收起轨迹噪音） */}
                    <details className="db-tool-details" open={m.running ? true : undefined}>
                      <summary className="db-tool-head">
                        {m.running ? '⚙' : '✓'} {m.toolName}
                        {m.toolArgs && <code className="db-tool-args">{m.toolArgs}</code>}
                      </summary>
                      {m.toolResult && <pre className="db-tool-result">{m.toolResult}</pre>}
                    </details>
                  </div>
                );
              }
              // text：AI 回复（Markdown；db_query 结果以表格呈现在回复里）
              return <div key={i} className="db-msg db-msg-text" dangerouslySetInnerHTML={{__html: md(m.content)}}/>;
            })}
          </div>
          )}

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
              <textarea
                value={curtain}
                onChange={(e) => {
                  setCurtain(e.target.value);
                  // 自动增高：随内容 1→5 行生长，封顶 120px 后内部滚动
                  e.target.style.height = 'auto';
                  e.target.style.height = Math.min(e.target.scrollHeight, 120) + 'px';
                }}
                onKeyDown={(e) => {
                  // Enter 发送 / Shift+Enter 换行；输入法组合中不触发
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault();
                    sendChat();
                  }
                }}
                rows={1}
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

        {/* 右侧栏：当前连接的表清单（连接后直接展示）+ 平台数据库连接清单 */}
        <aside className="ops-side">
          {activeTab && (
            <div className="db-tables-panel">
              <div className="ops-side-head">
                📋 {activeTab.database} 的表（{activeTab.tablesLoading ? '…' : (activeTab.tables?.length ?? 0)}）
              </div>
              <input
                className="db-table-search"
                value={tableFilter}
                onChange={(e) => setTableFilter(e.target.value)}
                placeholder="🔍 过滤表名…"
                disabled={activeTab.tablesLoading}
              />
              {activeTab.tablesLoading && <div className="ops-empty">正在读取表清单…</div>}
              {!activeTab.tablesLoading && (activeTab.tables?.length ?? 0) === 0 && (
                <div className="ops-empty">没有可见表（或账号无权限）</div>
              )}
              {!activeTab.tablesLoading && (activeTab.tables?.length ?? 0) > 0 && visibleTables.length === 0 && (
                <div className="ops-empty">无匹配表</div>
              )}
              <ul className="db-table-list">
                {visibleTables.map((t) => (
                  <li
                    key={t}
                    className={activeTab.tableDetail?.name === t ? 'active' : ''}
                    onClick={() => void toggleTable(activeTab.connKey, t)}
                    title="点击查看表结构"
                  >
                    {t}
                  </li>
                ))}
              </ul>
              {activeTab.tableDetail && (
                <pre className="db-table-detail">{activeTab.tableDetail.text}</pre>
              )}
            </div>
          )}
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
                <li key={c.serverKey} className={(expanded ? 'active' : '') + (inUse ? ' in-use' : '')}>
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


