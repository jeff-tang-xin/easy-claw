import {useSyncExternalStore} from 'react';
import {getJson} from './api';

/**
 * 云端（hub）接入状态（GET /api/settings/cloud-status 的前端消费）。
 * 启动时拉取一次；拉取失败（本地模式/老后端/未启动）静默回退默认值。
 * 当前唯一用途：attachmentsAllowed=false 时隐藏聊天页附件入口（spec §4.5）。
 */
export interface CloudStatus {
  /** appkey 是否已配置 */
  configured: boolean;
  /** 最近一次 bootstrap 是否成功拿到快照 */
  available: boolean;
  appKeyPrefix: string | null;
  orgName: string | null;
  orgSlug: string | null;
  models: string[];
  permissions: string[];
  /** 组织是否允许附件/图片上传；本地模式/无快照/拉取失败恒为 true（与后端放行语义一致） */
  attachmentsAllowed: boolean;
  lastError: string | null;
  lastAttemptAt: string | null;
}

const DEFAULT_STATUS: CloudStatus = {
  configured: false,
  available: false,
  appKeyPrefix: null,
  orgName: null,
  orgSlug: null,
  models: [],
  permissions: [],
  attachmentsAllowed: true,
  lastError: null,
  lastAttemptAt: null,
};

let current: CloudStatus = DEFAULT_STATUS;
const listeners = new Set<() => void>();

export function getCloudStatus(): CloudStatus {
  return current;
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/** React 组件订阅云端状态（加载完成后自动重渲染） */
export function useCloudStatus(): CloudStatus {
  return useSyncExternalStore(subscribe, getCloudStatus);
}

/** 应用启动时调用一次；失败静默保留默认值（本地模式/老后端不阻塞 UI） */
export async function loadCloudStatus(): Promise<void> {
  try {
    const s = await getJson<Partial<CloudStatus>>('/api/settings/cloud-status');
    current = {...DEFAULT_STATUS, ...s};
  } catch {
    return;
  }
  listeners.forEach((fn) => fn());
}
