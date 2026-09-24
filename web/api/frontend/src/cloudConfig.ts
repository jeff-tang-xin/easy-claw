import {useSyncExternalStore} from 'react';
import {getJson} from './api';

/**
 * 云端（hub）配置下发（GET /api/settings/cloud-config 的前端消费）。
 * 启动时拉取一次，之后 cloud 模式下每 {@link POLL_INTERVAL_MS} 轮询重拉（后端按需刷新快照，
 * hub 配置变更/启动时 hub 未就绪的故障恢复后，菜单/服务器清单自动跟上，无需刷新页面）；
 * 拉取失败（本地模式/老后端/未启动）静默回退默认值。
 * 消费方：App.tsx 侧边栏菜单（cloud 模式渲染下发菜单树）、OpsPage（服务器清单 + shell 白名单）。
 */

/** 轮询间隔：cloud 模式定期重拉配置（轻量接口；与后端 60s 刷新节流配合，30s 轮询无害） */
const POLL_INTERVAL_MS = 5_000;

/** 平台下发的菜单项（树）：path 空 = 分组标题节点，非空 = 可跳转叶子 */
export interface CloudMenuItem {
  menuKey: string;
  label: string;
  icon: string;
  path: string;
  requiredPerm: string;
  visibleRoles: string[];
  children: CloudMenuItem[];
}

/** 平台下发的运维服务器（凭证不下发：password 已从浏览器协议移除，连接凭证由 spoke 服务端快照自取；
 * 未配置密码的服务器在连接时当次手输，RSA 加密上送） */
export interface CloudOpsServer {
  serverKey: string;
  name: string;
  host: string;
  port: number;
  username: string;
  description: string;
  /** 归属的 hub 项目 id：数据归属参考；服务器清单不再按项目过滤（hub 已按授权下发全部） */
  projectId: number;
  /** hub 侧是否配置了密码：true=一键连接（spoke 服务端快照自取凭证）；false=连接时当次手输 */
  hasPassword?: boolean;
}

/** 平台下发的 agent 模式 shell 白名单命令；subcommands 空 = 整命令放行，非空 = 仅放行这些子命令 */
export interface CloudShellCommand {
  cmd: string;
  subcommands: string[];
}

export interface CloudConfig {
  cloudMode: boolean;
  available: boolean;
  menu: CloudMenuItem[];
  opsServers: CloudOpsServer[];
  shellCommands: CloudShellCommand[];
}

const DEFAULT_CONFIG: CloudConfig = {
  cloudMode: false,
  available: false,
  menu: [],
  opsServers: [],
  shellCommands: [],
};

let current: CloudConfig = DEFAULT_CONFIG;
const listeners = new Set<() => void>();

export function getCloudConfig(): CloudConfig {
  return current;
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/** React 组件订阅云端配置（加载完成后自动重渲染） */
export function useCloudConfig(): CloudConfig {
  return useSyncExternalStore(subscribe, getCloudConfig);
}

/** 应用启动时调用一次；失败静默保留默认值（本地模式/老后端不阻塞 UI） */
export async function loadCloudConfig(): Promise<void> {
  try {
    const c = await getJson<Partial<CloudConfig>>('/api/settings/cloud-config');
    current = {...DEFAULT_CONFIG, ...c};
  } catch {
    return;
  }
  listeners.forEach((fn) => fn());
}

let pollTimer: ReturnType<typeof setInterval> | null = null;

/**
 * 启动 cloud 配置轮询（应用挂载时调用一次）：cloud 模式下每 {@link POLL_INTERVAL_MS} 重拉，
 * 后端快照恢复/更新后前端自动渲染 hub 菜单；切回本地模式（cloudMode=false）即停止轮询。
 * 不重复启动：已有定时器时直接复用。
 */
export function startCloudConfigPolling(): void {
  if (pollTimer !== null) {
    return;
  }
  pollTimer = setInterval(() => {
    void loadCloudConfig().then(() => {
      if (!current.cloudMode && pollTimer !== null) {
        clearInterval(pollTimer);
        pollTimer = null;
      }
    });
  }, POLL_INTERVAL_MS);
}
