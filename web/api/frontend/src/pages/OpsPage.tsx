// 运维工作区主页：SSH 交互终端（多连接多 tab）+ 智能幕布（agent curtain）
// 交互模型（用户澄清后的定稿）：
//   - 每条活跃 SSH 连接一个终端 tab：远程 shell 的真实输出（含 ANSI）原样渲染；
//     顶栏「上传文件 / 断开」作用于当前激活 tab 的连接；
//   - 底部一条「智能幕布」输入框，按首字符分流：
//       中文开头 → 发给智能体（chat WS），回复/工具调用/确认提示都渲染进当前激活 tab；
//       其他内容 → 直接写进当前激活 tab 的远程 shell（term_data），不经过 LLM；
//   - 右侧栏：hub 下发的运维服务器清单（一键连接 / 当次密码连接 + 文件上传）。
//     服务器只来自平台（GET /api/spoke/ops-servers），spoke 不维护本地连接配置与凭证；
//     运维工作区不绑定 hub 项目、没有知识库/黑板（那是 SOLO 工作区的能力）。
// 确认按钮只给「允许一次 / 拒绝」：ops 模式白名单机制关闭（AgentOrchestrator.whitelistEnabled=false），
// 回合/永久授权后端一律不生效，渲染出来只会造成「点了没反应」的错觉。
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useParams} from 'react-router-dom';
import {Terminal} from '@xterm/xterm';
import {FitAddon} from '@xterm/addon-fit';
import Zmodem from 'zmodem.js/src/zmodem_browser';
import type {ZmodemDetection, ZmodemSentry, ZmodemSession, ZmodemTransfer} from 'zmodem.js/src/zmodem_browser';
import '@xterm/xterm/css/xterm.css';
import {getJson, postJson} from '../api';
import {createOpsSocket, OpsChatEvent, OpsSocket} from '../opsSocket';
import {CloudOpsServer, useCloudConfig} from '../cloudConfig';
import {encryptPassword, getOpsPublicKey} from '../opsCrypto';
import '../ops.css';

/** 一条活跃连接（SshConnectionService.status.connections 元素；serverKey 供匹配回下发清单） */
interface ActiveConn {
  connId: number;
  serverKey?: string | null;
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
  /** 对应的下发服务器标识（status 快照带回；用于右侧栏「使用中」徽标匹配） */
  serverKey: string | null;
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

/** agent 模式下可直接执行的日常运维 shell 命令白名单：命中直写远程 shell，不调用智能体。
 * 定位是「查看/排查类」快捷通道——日常运维以看状态、看日志、查资源为主，这些命令
 * 无副作用，直接执行省去绕智能体的一圈；写操作/进程控制/电源操作（rm、systemctl
 * restart/stop、kill 等）一律走智能体（保留确认机制），防误操作。
 * 交互式全屏程序（top/htop/less/vim/watch）刻意不放：agent 模式键盘不直通 PTY，
 * 进去无法操作也无法退出，请切 SH 模式使用；持续输出命令（ping、tail -f）同理，
 * 需终止时切 SH 模式按 Ctrl+C。 */
const SHELL_SHORTCUTS = new Set([
  // 导航/回显
  'ls', 'll', 'la', 'l', 'pwd', 'cd', 'clear', 'cls', 'echo', 'printf', 'history',
  // 身份/系统信息
  'whoami', 'id', 'groups', 'hostname', 'uname', 'arch', 'date', 'cal', 'uptime',
  'nproc', 'lscpu', 'lsmod', 'lspci', 'lsusb', 'lsblk', 'dmidecode', 'env', 'printenv',
  // 内存/磁盘/IO
  'free', 'df', 'du', 'vmstat', 'iostat', 'mpstat', 'pidstat',
  // 进程（只查不杀）
  'ps', 'pgrep', 'pidof', 'pstree',
  // 文件查看/检索/文本处理（find/sed 的写参数在 isShellShortcut 里单独排除）
  'cat', 'head', 'tail', 'tac', 'rev', 'grep', 'egrep', 'fgrep', 'zcat', 'zgrep',
  'find', 'stat', 'file', 'tree', 'wc', 'sort', 'uniq', 'cut', 'tr', 'comm',
  'column', 'fold', 'fmt', 'sed',
  // 网络诊断
  'ping', 'ss', 'netstat', 'ifconfig', 'traceroute', 'tracepath', 'mtr',
  'dig', 'nslookup', 'host', 'arp',
  // 日志
  'journalctl', 'dmesg',
  // 哈希/编码/路径
  'md5sum', 'sha1sum', 'sha256sum', 'sha512sum', 'cksum', 'xxd', 'od', 'hexdump',
  'base64', 'readlink', 'realpath', 'dirname', 'basename',
  // 其他只读
  'which', 'whereis', 'type', 'alias', 'seq', 'expr', 'sleep',
]);

/** 子命令级放行：首词命中且第二个词在允许集。用于「命令本身常用、但子命令危险分化大」
 * 的多面手工具——只放只读/诊断子命令，restart/stop/rm 等写子命令仍走智能体确认。
 * 第二词是全局 flag 的写法（如 docker -H tcp://… ps）不命中，走智能体，可接受 */
const SHELL_SUBCOMMANDS: Record<string, Set<string>> = {
  systemctl: new Set([
    'status', 'show', 'is-active', 'is-enabled', 'is-failed', 'is-system-running',
    'list-units', 'list-unit-files', 'list-timers', 'list-dependencies', 'list-sockets',
    'cat', 'get-default', 'show-environment',
  ]),
  service: new Set(['status', '--status-all']),
  docker: new Set([
    'ps', 'images', 'image', 'logs', 'inspect', 'stats', 'top', 'version', 'info',
    'port', 'diff', 'history', 'search', 'exec',
  ]),
  podman: new Set([
    'ps', 'images', 'image', 'logs', 'inspect', 'stats', 'top', 'version', 'info',
    'port', 'diff', 'history', 'search', 'exec',
  ]),
  crictl: new Set(['ps', 'images', 'pods', 'info', 'logs', 'inspect', 'stats', 'version', 'imagefsinfo']),
  kubectl: new Set([
    'get', 'describe', 'logs', 'top', 'version', 'cluster-info',
    'api-resources', 'api-versions', 'explain', 'events', 'exec',
  ]),
  ip: new Set(['addr', 'a', 'link', 'l', 'route', 'r', 'neigh', 'n', 'netns']),
  git: new Set(['status', 'log', 'diff', 'show', 'branch']),
};

/** agent 模式白名单判定：首词在白名单（或首词+子命令命中），且整行无管道/重定向/链/
 * 命令替换（`|<>;&`、反引号、$() 会改变命令语义，如 `cat > file`、`echo x; rm`，
 * 必须交给智能体或 SH 模式）。
 * 两个参数级特判：find 的 -delete/-exec/-ok 与 sed 的 -i/--in-place 是写操作，
 * 命中即整行交给智能体（保留确认）。
 * 白名单数据来源由调用方传入：cloud 模式用平台下发的 shellCommands 构建，
 * local 模式（或下发为空）回退上方内置常量——安全拦截规则两种模式完全一致。 */
const isShellShortcut = (s: string, shortcuts: Set<string>, subsByCmd: Record<string, Set<string>>) => {
  if (/[|<>&;`]|\$\(/.test(s)) return false;
  const tokens = s.trim().split(/\s+/);
  const first = (tokens[0] ?? '').toLowerCase();
  // 写参数特判：find -delete/-exec、sed -i 会删文件/改文件/执行任意命令
  if (first === 'find' && /\s(-delete|-exec|-ok|-fls|-fprint\d*)\b/.test(s)) return false;
  if (first === 'sed' && /\s(-i\b|--in-place)/.test(s)) return false;
  if (shortcuts.has(first)) return true;
  const subs = subsByCmd[first];
  return subs !== undefined && subs.has((tokens[1] ?? '').toLowerCase());
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

/** 原始字节 → base64（term_data 的 data_b64 字段；ZMODEM 二进制帧上行专用，
 * 后端 Base64.getDecoder 标准变体解码 → writeShellBytes 原样写 PTY） */
function bytesToB64(bytes: ArrayLike<number>): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

export default function OpsPage() {
  const params = useParams<{ workspaceId: string }>();
  // 运维工作区是系统内置唯一固定 workspace：路由未带 id（/ops）时用写死的默认 id。
  // 该 id 与后端 AppConstants.DEFAULT_OPS_WORKSPACE_ID 保持一致
  const workspaceId = params.workspaceId || 'default-ops';

  // 连接面板状态
  const [opsError, setOpsError] = useState('');

  // cloud 模式：平台下发的运维配置（local 模式 cloudMode=false，无服务器来源）
  const cloud = useCloudConfig();
  /** 一键连接进行中的服务器 serverKey（防重复点击） */
  const [connectingKey, setConnectingKey] = useState<string | null>(null);

  /** 生效的白名单：cloud 下发非空用下发，否则回退内置常量（local 模式恒走内置） */
  const shellWhitelist = useMemo(() => {
    const cmds = cloud.shellCommands ?? [];
    if (cloud.cloudMode && cmds.length > 0) {
      const shortcuts = new Set<string>();
      const subs: Record<string, Set<string>> = {};
      for (const c of cmds) {
        const cmd = c.cmd.toLowerCase();
        shortcuts.add(cmd);
        if (c.subcommands?.length) subs[cmd] = new Set(c.subcommands.map((x) => x.toLowerCase()));
      }
      return {shortcuts, subs};
    }
    return {shortcuts: SHELL_SHORTCUTS, subs: SHELL_SUBCOMMANDS};
  }, [cloud.cloudMode, cloud.shellCommands]);

  /** cloud 模式生效的服务器清单：直接展示 hub 下发全部服务器——hub 侧已按「组织 +
   * 当前 appkey 用户有效授权」过滤（GET /api/spoke/ops-servers），spoke 端不做任何二次过滤 */
  const cloudServers = useMemo(() => {
    if (!cloud.cloudMode) return [];
    return cloud.opsServers ?? [];
  }, [cloud.cloudMode, cloud.opsServers]);

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
  /** terminalId → SH 模式行缓冲（键盘直通的逐键输入拼成整行，回车时上报命令记录；
   * tab 补全/↑↓ 历史由远端 shell 处理，补全内容不进本地缓冲，记录可能缺补全部分——审计可接受） */
  const shLineBufRef = useRef<Map<string, string>>(new Map());
  /** terminalId → 终端容器 div（JSX ref 回填，供 xterm.open 挂载） */
  const containersRef = useRef<Map<string, HTMLDivElement | null>>(new Map());
  const sockRef = useRef<OpsSocket | null>(null);
  /** WS 是否已建立（refreshStatus 晚于 onOpen 到达时决定要不要补 reattach） */
  const sockOpenRef = useRef(false);
  const fileRef = useRef<HTMLInputElement | null>(null);
  /** terminalId → 常驻 ZMODEM Sentry：下行字节先过它，普通输出透传上屏，ZMODEM 帧头截获成会话 */
  const sentriesRef = useRef<Map<string, ZmodemSentry>>(new Map());
  /** terminalId → 活跃 ZMODEM 会话（传输期间抑制键盘/幕布直发，防击键破坏协议帧） */
  const zmSessionsRef = useRef<Map<string, ZmodemSession>>(new Map());
  /** terminalId → 远端 rz 等待我们发文件的会话（文件选定后 send_files；顶栏上传兜底入口） */
  const zmSendPendingRef = useRef<Map<string, ZmodemSession>>(new Map());
  /** ZMODEM 发送文件选择器（与 SFTP 上传的 fileRef 分开：onChange 行为不同） */
  const zmFileRef = useRef<HTMLInputElement | null>(null);

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
  const upsertTab = useCallback((conn: ActiveConn,
                                 sessionId: string, activate = true) => {
    const terminalId = nextTerminalId(conn.connId);
    setTabs((prev) => [...prev, {
      terminalId,
      connId: conn.connId,
      serverKey: conn.serverKey ?? null,
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
      // 后端 PTY 已关 ONLCR（ZMODEM 二进制透传需要），\n 不再被内核转成 \r\n：
      // 由 xterm 侧把 \n 当 \r\n 渲染，补偿普通输出的换行显示
      convertEol: true,
      theme: {background: '#101418'},
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(el);
    // 常驻 ZMODEM 哨兵：下行字节先 consume 进 Sentry——普通输出透传上屏（路径不变），
    // 疑似 ZMODEM 帧头被截获成会话（sz 自动收文件落盘 / rz 弹文件选择器发送）。
    // 上行 sender 走 term_data 的 data_b64（二进制帧不能走文本 data 字段）
    const sentry = new Zmodem.Sentry({
      to_terminal: (octets) => {
        term.write(octets instanceof Uint8Array ? octets : Uint8Array.from(octets));
      },
      sender: (octets) => {
        sockRef.current?.send({
          type: 'term_data', workspaceId, terminalId: active.terminalId,
          data_b64: bytesToB64(octets),
        });
      },
      on_detect: (detection) => onZmodemDetect(active.terminalId, detection),
      on_retract: () => { /* 帧头误判回缩：Sentry 已把字节重新排队送 to_terminal，无需处理 */ },
    });
    sentriesRef.current.set(active.terminalId, sentry);
    term.writeln(`\x1b[90m${active.title}（${active.username}@${active.host}）\x1b[0m`);
    term.writeln(`\x1b[90m徽标 AI（默认）：在此直接打字与智能体对话，白名单内命令(ls/df/systemctl status等)自动直执行；徽标 SH：键盘逐键直通远程 shell（Ctrl+C/Tab/↑ 可用）。点 tab 徽标切换\x1b[0m`);
    // 键盘分流：AI 模式本地行编辑（回车提交分流，不直通 PTY）；SH 模式逐键直通远程 shell
    term.onData((data) => {
      const tab = tabsRef.current.find((t) => t.terminalId === active.terminalId);
      if (!tab?.connected) return;
      if (tab.mode === 'agent') {
        agentKeystroke(tab, data);
      } else if (zmSessionsRef.current.has(active.terminalId)) {
        // ZMODEM 传输中：击键混进协议流会破坏传输，丢弃
      } else {
        shKeystroke(tab, data); // 行缓冲拼整行供审计上报（不改变直通行为）
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
        sentriesRef.current.delete(tid);
        zmSessionsRef.current.delete(tid);
        zmSendPendingRef.current.delete(tid);
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
      // 下行先过 ZMODEM 哨兵：普通输出透传上屏，ZMODEM 帧头截获成会话（sz/rz 自动收发）
      onData: (tid, b64) => {
        const bytes = b64ToBytes(b64);
        const sentry = sentriesRef.current.get(tid);
        if (sentry) sentry.consume(bytes);
        else termWrite(tid, bytes);
      },
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
    void refreshStatus();
  }, [refreshStatus]);

  // 授权守卫感知轮询：spoke 侧 OpsConnectionGuard 撤销授权后强制断开连接，
  // status 快照里 tab 的 connId 消失 → 标记断开 + 终端灰字提示 + 移除 tab。
  // termWrite/removeTab 非稳定引用，经 ref 取最新版，interval 只随 workspaceId 重建
  const guardFnsRef = useRef({termWrite, removeTab});
  guardFnsRef.current = {termWrite, removeTab};
  useEffect(() => {
    if (!workspaceId) return;
    const timer = setInterval(async () => {
      try {
        const st = await getJson<OpsStatus>(`/api/ops/status?workspaceId=${encodeURIComponent(workspaceId)}`);
        const alive = new Set((st.connections ?? []).map((c) => c.connId));
        for (const t of tabsRef.current) {
          if (t.connected && !alive.has(t.connId)) {
            setTabs((prev) => prev.map((x) => (x.terminalId === t.terminalId ? {...x, connected: false} : x)));
            guardFnsRef.current.termWrite(t.terminalId, '\r\n\x1b[33m—— 连接已断开（服务器授权可能已被管理员撤销）——\x1b[0m\r\n');
            guardFnsRef.current.removeTab(t.terminalId);
          }
        }
      } catch { /* 状态接口失败不阻塞页面 */ }
    }, 5000);
    return () => clearInterval(timer);
  }, [workspaceId]);

  /** 连接一台 hub 下发服务器：POST /api/ops/connect（不落本地配置，密码仅当次认证使用）。
   * 密码链路（不落网明文）：hub 随目录下发了密码 → spoke 服务端快照自取（请求只带 serverKey）；
   * 未下发 → 弹窗当次输入，RSA-OAEP 加密后上送（encryptedPassword），网络路径无明文。
   * 已有该服务器的活跃 tab：直接切换过去，不重连（重连会丢 shell 状态） */
  const connectServer = async (s: CloudOpsServer) => {
    if (!workspaceId || connectingKey) return;
    const existing = tabsRef.current.find((t) => t.serverKey === s.serverKey && t.connected);
    if (existing) {
      setActiveTabId(existing.terminalId);
      return;
    }
    // 请求体不含明文密码：serverKey 让 spoke 从快照取凭证；encryptedPassword 仅手输场景
    const body: Record<string, unknown> = {workspaceId, serverKey: s.serverKey};
    if (!s.hasPassword) {
      const input = window.prompt(
        `「${s.name}」未随目录下发密码，请输入 ${s.username}@${s.host} 的登录密码（仅本次连接使用，不保存）`);
      if (input === null) return; // 用户取消
      if (!input) {
        setOpsError('密码不能为空');
        return;
      }
      try {
        const publicKey = await getOpsPublicKey();
        body.encryptedPassword = await encryptPassword(publicKey, input);
      } catch (e) {
        setOpsError(`密码加密失败：${String(e)}`);
        return;
      }
    }
    setOpsError('');
    setConnectingKey(s.serverKey);
    try {
      const st = await postJson<OpsStatus>('/api/ops/connect', body);
      const c = (st.connections ?? []).find((x) => x.serverKey === s.serverKey);
      if (c) {
        // 该服务器此前的断开 tab 已失效（新 tab 新终端新会话），先移除
        for (const stale of tabsRef.current.filter((t) => t.serverKey === s.serverKey && !t.connected)) {
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
    } finally {
      setConnectingKey(null);
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

  const uploadDirRef = useRef('');

  // ==================== ZMODEM（sz 自动收 / rz 弹选发送） ====================

  /** 下行检测到 ZMODEM 帧头：receive=远端 sz（自动收文件落盘）；send=远端 rz（弹文件选择器） */
  const onZmodemDetect = (terminalId: string, detection: ZmodemDetection) => {
    const zsession = detection.confirm();
    zmSessionsRef.current.set(terminalId, zsession);
    const finish = () => {
      zmSessionsRef.current.delete(terminalId);
      zmSendPendingRef.current.delete(terminalId);
    };
    zsession.on('session_end', finish);
    if (zsession.type === 'receive') {
      zsession.on('offer', (xfer: ZmodemTransfer) => {
        const name = xfer.get_details().name;
        termWrite(terminalId, `\r\n\x1b[36m⇣ ZMODEM 接收 ${name}…\x1b[0m\r\n`);
        xfer.accept().then((payloads) => {
          Zmodem.Browser.save_to_disk(payloads, name);
          const total = payloads.reduce((n, p) => n + p.length, 0);
          termWrite(terminalId, `\x1b[36m⇣ 已保存 ${name}（${total} 字节）\x1b[0m\r\n`);
        }).catch((e) => {
          termWrite(terminalId, `\r\n\x1b[31m✗ ZMODEM 接收失败：${String(e)}\x1b[0m\r\n`);
        });
      });
      zsession.start();
    } else {
      // 远端 rz 等我们发文件：自动弹文件选择器（用户刚敲 rz，页面有激活态，可弹出）；
      // 若被浏览器拦截，点顶栏「上传文件」兜底（upload 里优先喂挂起的 rz 会话）
      zmSendPendingRef.current.set(terminalId, zsession);
      termWrite(terminalId, `\r\n\x1b[36m⇡ 远端请求接收文件（rz）—— 请选择要发送的文件\x1b[0m\r\n`);
      zmFileRef.current?.click();
    }
  };

  /** 把选定的文件经 ZMODEM 发给挂起的 rz 会话 */
  const zmodemSend = async (terminalId: string, zsession: ZmodemSession, files: File[]) => {
    zmSendPendingRef.current.delete(terminalId);
    try {
      await Zmodem.Browser.send_files(zsession, files, {
        on_file_complete: (xfer) => {
          termWrite(terminalId, `\x1b[36m⇡ ${xfer.get_details().name} 发送完成\x1b[0m\r\n`);
        },
      });
      zsession.close();
    } catch (e) {
      termWrite(terminalId, `\r\n\x1b[31m✗ ZMODEM 发送失败：${String(e)}\x1b[0m\r\n`);
      zmSessionsRef.current.delete(terminalId);
    }
  };

  const upload = async (f: File) => {
    const tab = currentTab();
    if (!tab || !workspaceId) return;
    // 该 tab 有挂起的 rz 会话：文件喂给 ZMODEM 而非 SFTP（远端正在等协议数据流）
    const pending = zmSendPendingRef.current.get(tab.terminalId);
    if (pending) {
      await zmodemSend(tab.terminalId, pending, [f]);
      return;
    }
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

  // ==================== 文件下载（SFTP 流式） ====================

  /** 下载当前 tab 连接上的远程文件：GET /api/ops/download（SFTP 流式）→ 浏览器落盘。
   * 文件名取路径末段；失败（不存在/无权限）以 opsError 提示 */
  const downloadFile = async () => {
    const tab = currentTab();
    if (!tab || !workspaceId) return;
    const path = window.prompt('下载远程哪个文件？（绝对路径，如 /var/log/app.log）');
    if (path === null) return; // 用户取消
    const p = path.trim();
    if (!p) return;
    setOpsError('');
    try {
      const qs = `workspaceId=${encodeURIComponent(workspaceId)}&connId=${tab.connId}`
        + `&path=${encodeURIComponent(p)}`;
      const res = await fetch(`/api/ops/download?${qs}`);
      if (!res.ok) {
        const detail = (await res.text()).trim();
        throw new Error(detail || `HTTP ${res.status}`);
      }
      const blob = await res.blob();
      const name = p.split('/').pop() || 'download';
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = name;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      termWrite(tab.terminalId, `\x1b[90m已下载 ${name}（${blob.size} 字节）\x1b[0m\r\n`);
    } catch (e) {
      setOpsError(`下载失败：${String(e)}`);
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
      shLineBufRef.current.set(terminalId, '');
      termWrite(terminalId, '\r\x1b[K\x1b[90m—— SH 模式：键盘直通远程 shell ——\x1b[0m\r\n');
    } else if (prevMode === 'shell') {
      shLineBufRef.current.set(terminalId, '');
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

  /** 上报一条用户命令记录（异步 fire-and-forget：审计上报失败不影响命令执行）。
   * 后端按 connId 从连接运行时补全服务器信息并入队转发 hub（POST /api/spoke/ops-command-logs） */
  const reportUserCommand = (tab: TermTab, command: string) => {
    if (!workspaceId || !command.trim()) return;
    postJson('/api/ops/command-log', {
      workspaceId,
      connId: tab.connId,
      command: command.length > 2000 ? command.slice(0, 2000) : command,
    }).catch(() => {/* 审计上报失败静默：不干扰终端操作 */});
  };

  /** SH 模式键盘直通的行缓冲：拼出完整命令行供审计上报。
   * 可打印字符累积；\r 提交上报；退格删尾；Ctrl+C/U/W 清行；Tab/ESC 序列/其他控制字符跳过 */
  const shKeystroke = (tab: TermTab, data: string) => {
    let buf = shLineBufRef.current.get(tab.terminalId) ?? '';
    for (const ch of data) {
      const code = ch.charCodeAt(0);
      if (ch === '\r') {
        if (buf.trim()) reportUserCommand(tab, buf);
        buf = '';
      } else if (code === 0x7f) {
        buf = buf.slice(0, -1);
      } else if (code === 0x03 || code === 0x15 || code === 0x17) {
        // Ctrl+C / Ctrl+U / Ctrl+W：行作废（Ctrl+W 删词简化为清行，审计粒度可接受）
        buf = '';
      } else if (code === 0x1b || code === 0x09 || code < 0x20) {
        // ESC 序列开头（↑↓ 历史等）/ Tab 补全 / 其他控制字符：不进缓冲
        if (code === 0x1b) break; // ESC 后跟的序列字符一并跳过
      } else {
        buf += ch;
      }
    }
    shLineBufRef.current.set(tab.terminalId, buf);
  };

  /** 一行输入的分流执行（终端行编辑与底部幕布输入共用）：
   * SH 模式或 AI 模式命中只读命令白名单 → 直写远程 shell；其余一律发给智能体 */
  const dispatchLine = (tab: TermTab, text: string) => {
    const sock = sockRef.current;
    if (!sock || !workspaceId) return;
    if (tab.mode === 'shell' || isShellShortcut(text, shellWhitelist.shortcuts, shellWhitelist.subs)) {
      if (!tab.connected) {
        termWrite(tab.terminalId, '\x1b[31m该连接已断开 —— 请重新连接\x1b[0m\r\n');
        return;
      }
      // 直接写进该 tab 的远程 shell：远端回显即用户所见，本地不再重复打印
      // ZMODEM 传输中：幕布文本混进协议流会破坏传输，丢弃
      if (zmSessionsRef.current.has(tab.terminalId)) return;
      reportUserCommand(tab, text); // 幕布 SH 模式/白名单命令：整行上报审计
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
          <button disabled={!activeTab} onClick={() => void downloadFile()}>📥 下载文件</button>
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
        {/* rz 挂起会话的文件选择器（onZmodemDetect 自动弹出；被浏览器拦截时点「上传文件」兜底） */}
        <input
          ref={zmFileRef}
          type="file"
          multiple
          hidden
          onChange={(e) => {
            const files = e.target.files ? Array.from(e.target.files) : [];
            const tid = activeTabRef.current;
            const pending = tid ? zmSendPendingRef.current.get(tid) : undefined;
            if (files.length && tid && pending) void zmodemSend(tid, pending, files);
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
                  : (cloud.cloudMode
                    ? 'AI 模式：自然语言下指令；平台白名单内命令自动直执行'
                    : 'AI 模式：自然语言下指令；日常运维命令(ls/df/systemctl status等)自动直接执行')}
              />
              <button onClick={submitCurtain} disabled={!curtain.trim()}>
                {activeBusy ? '思考中…' : activeTab?.mode === 'shell' ? '执行' : '发送'}
              </button>
            </div>
          </div>
        </section>

        <aside className="ops-side">
          <div className="ops-side-head">
            <span>服务器（平台下发）</span>
          </div>

          {!cloud.cloudMode && (
            <p className="ops-bind-hint">
              运维服务器由平台（hub）统一下发；当前为本地模式，无服务器来源。
            </p>
          )}

          <ul className="ops-conn-list">
            {cloudServers.map((s) => {
              // 活跃 tab 按 serverKey 匹配（/api/ops/status 快照带回）
              const tab = tabs.find((t) => t.serverKey === s.serverKey);
              return (
                <li key={s.serverKey} className={tab && tab.terminalId === activeTabId ? 'active' : ''}>
                  <div className="ops-conn-info">
                    <b>{s.name}</b>
                    <span>{s.username}@{s.host}:{s.port}{s.description ? ` · ${s.description}` : ''}</span>
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
                      <button
                        disabled={connectingKey === s.serverKey}
                        onClick={() => void connectServer(s)}
                      >
                        {connectingKey === s.serverKey ? '连接中…' : s.hasPassword ? '一键连接' : '连接'}
                      </button>
                    )}
                  </div>
                </li>
              );
            })}
            {cloud.cloudMode && cloudServers.length === 0 && (
              <li className="ops-empty">平台未下发服务器清单</li>
            )}
          </ul>

        </aside>
      </div>
    </div>
  );
}
